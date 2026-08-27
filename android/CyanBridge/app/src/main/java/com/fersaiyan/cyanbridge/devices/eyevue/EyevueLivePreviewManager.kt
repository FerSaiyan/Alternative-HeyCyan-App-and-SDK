package com.fersaiyan.cyanbridge.devices.eyevue

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import com.fersaiyan.cyanbridge.ota.LivePreviewState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/** Eyevue live-mode flow: command BLE, join vendor Wi-Fi, then play the model URL. */
class EyevueLivePreviewManager(
    private val context: Context,
    private val eyevueManager: EyevueManager,
) {
    companion object {
        private const val TAG = "EyevueLive"
        private val CLIENT = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    private val _uiState = MutableStateFlow(LivePreviewState())
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val transport = EyevueWifiTransport(context)
    private var job: Job? = null
    private var player: ExoPlayer? = null
    private var playerListener: Player.Listener? = null
    private var playbackFailure: CompletableDeferred<Throwable>? = null
    private var onSessionFinished: () -> Unit = {}
    private var finishedNotified = true

    val uiState: StateFlow<LivePreviewState> = _uiState.asStateFlow()
    val isActive: Boolean get() = job?.isActive == true

    fun start(onSessionFinished: () -> Unit) {
        if (isActive) return
        this.onSessionFinished = onSessionFinished
        finishedNotified = false
        job = scope.launch { run() }
    }

    fun stop() {
        val activeJob = job
        if (activeJob?.isActive == true) {
            activeJob.cancel()
        } else {
            resetState()
            notifyFinished()
        }
    }

    fun release() {
        stop()
        scope.cancel()
    }

    fun getPlayer(): ExoPlayer? = player

    private suspend fun run() {
        var failed = false
        var liveCommandAttempted = false
        try {
            if (!eyeVueConnected()) throw IOException("Eyevue BLE is not connected")
            val project = eyevueManager.awaitProject()
                ?: throw IOException("Eyevue did not report its project/model")
            val profile = EyevueMediaProfile.fromProject(project)
            updateState("Starting live mode", "Sending Eyevue 0x67 command", scanning = true)
            liveCommandAttempted = true
            val ssid = eyevueManager.startLiveAndAwaitSsid(profile.mode == EyevueWifiMode.AP)
                ?: throw IOException("Eyevue did not report the live Wi-Fi SSID")

            updateState("Connecting Wi-Fi", "Joining $ssid", scanning = true)
            transport.connect(
                mode = profile.mode,
                ssid = ssid,
                password = "12345678",
                baseIp = profile.baseIp,
            ).getOrElse { throw it }

            profile.liveControlUrl?.let { controlUrl ->
                updateState("Starting stream", "Requesting Eyevue HTTP live endpoint", scanning = true)
                requestLiveEndpoint(controlUrl)
            }

            val streamUrl = profile.liveStreamUrl
            val streamFailure = CompletableDeferred<Throwable>()
            playbackFailure = streamFailure
            val ready = withTimeoutOrNull(20_000L) {
                playUntilReady(streamUrl, streamFailure)
                true
            } == true
            if (!ready) throw IOException("Timed out waiting for the Eyevue RTSP stream")
            _uiState.value = LivePreviewState(
                stateLabel = "Playing",
                detail = streamUrl,
                isPlaying = true,
                streamUrl = streamUrl,
                canStart = false,
                canStop = true,
            )
            throw streamFailure.await()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            failed = true
            Log.e(TAG, "Eyevue live preview failed", error)
            updateState("Error", error.message ?: "Eyevue live preview failed", scanning = false)
        } finally {
            withContext(NonCancellable) {
                releasePlayer()
                if (liveCommandAttempted && eyevueManager.isConnected()) {
                    eyevueManager.stopLiveBlocking()
                }
                transport.disconnect()
                if (!failed) resetState()
                job = null
                notifyFinished()
            }
        }
    }

    private fun eyeVueConnected(): Boolean = eyevueManager.isConnected()

    private suspend fun requestLiveEndpoint(url: String) = withContext(Dispatchers.IO) {
        var lastError: IOException? = null
        repeat(5) { attempt ->
            try {
                CLIENT.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
                    if (response.isSuccessful) return@withContext
                    lastError = IOException("Eyevue live HTTP request failed: ${response.code}")
                }
            } catch (error: IOException) {
                lastError = error
            }
            if (attempt < 4) delay(500L)
        }
        throw lastError ?: IOException("Eyevue live HTTP request failed")
    }

    @OptIn(UnstableApi::class)
    private suspend fun playUntilReady(
        streamUrl: String,
        streamFailure: CompletableDeferred<Throwable>,
    ) {
        val mediaSource = RtspMediaSource.Factory()
            .setForceUseRtpTcp(true)
            .createMediaSource(MediaItem.fromUri(Uri.parse(streamUrl)))
        suspendCancellableCoroutine<Unit> { continuation ->
            val exoPlayer = ExoPlayer.Builder(context).build()
            val listener = object : Player.Listener {
                private var ready = false

                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY && !ready) {
                        ready = true
                        if (continuation.isActive) continuation.resume(Unit)
                    } else if (playbackState == Player.STATE_ENDED) {
                        val error = IOException("Eyevue RTSP stream ended")
                        if (continuation.isActive) {
                            continuation.resumeWith(Result.failure(error))
                        } else {
                            streamFailure.complete(error)
                        }
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    val failure = IOException("Eyevue RTSP error: ${error.errorCodeName}", error)
                    if (continuation.isActive) {
                        continuation.resumeWith(Result.failure(failure))
                    } else {
                        streamFailure.complete(failure)
                    }
                }
            }
            player = exoPlayer
            playerListener = listener
            exoPlayer.addListener(listener)
            continuation.invokeOnCancellation {
                if (player === exoPlayer) releasePlayer()
            }
            exoPlayer.setMediaSource(mediaSource)
            exoPlayer.playWhenReady = true
            exoPlayer.prepare()
        }
    }

    private fun releasePlayer() {
        playbackFailure?.cancel()
        playbackFailure = null
        playerListener?.let { listener -> player?.removeListener(listener) }
        playerListener = null
        player?.release()
        player = null
    }

    private fun updateState(label: String, detail: String, scanning: Boolean) {
        _uiState.value = LivePreviewState(
            stateLabel = label,
            detail = detail,
            isScanning = scanning,
            canStart = !scanning,
            canStop = scanning,
        )
    }

    private fun resetState() {
        _uiState.value = LivePreviewState()
    }

    private fun notifyFinished() {
        if (finishedNotified) return
        finishedNotified = true
        onSessionFinished()
    }
}
