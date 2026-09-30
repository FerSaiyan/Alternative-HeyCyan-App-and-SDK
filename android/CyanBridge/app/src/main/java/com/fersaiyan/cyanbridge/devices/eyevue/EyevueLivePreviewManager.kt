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
    private var liveProxy: RtspPlayRewriteProxy? = null
    private var dummySurface: android.view.Surface? = null
    private var dummyTexture: android.graphics.SurfaceTexture? = null
    /** Fires when the per-session relay address is known (stable for the session). */
    var onRelayUrlChanged: ((String?) -> Unit)? = null
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

            // Single-session fan-out relay: one shared stream with the glasses
            // (both tracks, like the vendor) fanned out to every local viewer,
            // so viewers never disturb each other or the box.
            val proxy = RtspPlayRewriteProxy(profile.baseIp, 554)
            val localPort = proxy.start()
            liveProxy = proxy
            val streamUrl = "rtsp://127.0.0.1:$localPort/xxx.mov"
            Log.i(TAG, "Eyevue relay up: $streamUrl -> ${profile.baseIp}:554 (open in VLC while live runs)")
            runCatching { onRelayUrlChanged?.invoke(streamUrl) }
            var attempt = 0
            var lastError: Throwable? = null
            while (attempt < 4) {
                val streamFailure = CompletableDeferred<Throwable>()
                playbackFailure = streamFailure
                val ready = withTimeoutOrNull(30_000L) {
                    playUntilReady(streamUrl, streamFailure)
                    true
                } == true
                if (!ready) {
                    lastError = IOException("Timed out waiting for the Eyevue RTSP stream")
                    Log.w(TAG, "Eyevue handshake attempt ${attempt + 1} not ready; retrying")
                    releasePlayer()
                    attempt++
                    delay(1_000L)
                    continue
                }
                _uiState.value = LivePreviewState(
                    stateLabel = "Playing",
                    detail = streamUrl,
                    isPlaying = true,
                    streamUrl = streamUrl,
                    canStart = false,
                    canStop = true,
                )
                try {
                    throw streamFailure.await()
                } catch (reconnect: Throwable) {
                    if (reconnect is CancellationException) throw reconnect
                    lastError = reconnect
                    Log.w(TAG, "Eyevue stream hiccup (attempt ${attempt + 1}); re-handshaking: ${reconnect.message}")
                    releasePlayer()
                    attempt++
                    if (attempt >= 4) throw reconnect
                    updateState("Buffering", "Reopening Eyevue stream", scanning = true)
                    delay(1_000L)
                }
            }
            throw lastError ?: IOException("Eyevue live preview failed")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            failed = true
            Log.e(TAG, "Eyevue live preview failed", error)
            updateState("Error", error.message ?: "Eyevue live preview failed", scanning = false)
        } finally {
            withContext(NonCancellable) {
                releasePlayer()
                liveProxy?.stop()
                liveProxy = null
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
            .setDebugLoggingEnabled(true)
            .createMediaSource(MediaItem.fromUri(Uri.parse(streamUrl)))
        suspendCancellableCoroutine<Unit> { continuation ->
            val exoPlayer = ExoPlayer.Builder(context).build()
            // Video-only preview: the glasses' sound track keeps our player
            // stuck in buffering (first picture shows, then freeze).
            // Vendor's own player handles both; we unblock video first.
            exoPlayer.trackSelectionParameters =
                exoPlayer.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_AUDIO, true)
                    .build()
            // Dummy surface so the decoder has output even before the
            // picture box attaches. PlayerView replaces it once visible.
            val texture = android.graphics.SurfaceTexture(0)
            val surface = android.view.Surface(texture)
            dummyTexture = texture
            dummySurface = surface
            exoPlayer.setVideoSurface(surface)
            val listener = object : Player.Listener {
                private var ready = false

                override fun onPlaybackStateChanged(playbackState: Int) {
                    val name = when (playbackState) {
                        Player.STATE_IDLE -> "IDLE"
                        Player.STATE_BUFFERING -> "BUFFERING"
                        Player.STATE_READY -> "READY"
                        Player.STATE_ENDED -> "ENDED"
                        else -> playbackState.toString()
                    }
                    Log.i(TAG, "Eyevue player state: $name playWhenReady=${exoPlayer.playWhenReady}")
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

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    Log.i(TAG, "Eyevue player isPlaying=$isPlaying")
                }

                override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                    Log.i(TAG, "Eyevue video size: ${videoSize.width}x${videoSize.height}")
                }

                override fun onRenderedFirstFrame() {
                    Log.i(TAG, "Eyevue player rendered first frame")
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
            // Publish now that the player exists, so the inline video box
            // never receives a null player. Gated UI shows it only for
            // EyeVue after live starts.
            _uiState.value = LivePreviewState(
                stateLabel = "Buffering",
                detail = streamUrl,
                isPlaying = true,
                streamUrl = streamUrl,
                canStart = false,
                canStop = true,
            )
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
        player?.setVideoSurface(null)
        player?.release()
        player = null
        runCatching { dummySurface?.release() }
        dummySurface = null
        runCatching { dummyTexture?.release() }
        dummyTexture = null
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
