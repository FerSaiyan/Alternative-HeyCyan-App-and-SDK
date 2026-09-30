package com.fersaiyan.cyanbridge.devices.eyevue

import android.content.Context
import android.net.Uri
import android.util.Log
import android.view.Surface
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
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * Eyevue live-mode flow, vendor-matched: command BLE, join vendor Wi-Fi,
 * request the HTTP live endpoint, then play the stream URL directly with
 * LibVLC (same engine and options as the official app). Single box session,
 * picture and sound.
 */
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
    private var libVlc: LibVLC? = null
    private var player: MediaPlayer? = null
    private var playbackFailure: CompletableDeferred<Throwable>? = null
    private var videoSurface: Surface? = null
    /** Fires when the session stream address is known (stable for the session). */
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

    fun getPlayer(): MediaPlayer? = player

    /** Hands the dashboard picture surface to the vendor engine. */
    fun attachVideoSurface(surface: Surface) {
        videoSurface = surface
        player?.let { attachSurfaceTo(it, surface) }
    }

    fun detachVideoSurface() {
        videoSurface = null
        player?.let {
            runCatching { it.vlcVout.detachViews() }
        }
    }

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

            // Vendor path: play the box URL directly with LibVLC, one session.
            val streamUrl = profile.liveStreamUrl
            Log.i(TAG, "Eyevue live direct: $streamUrl (single box session)")
            runCatching { onRelayUrlChanged?.invoke(streamUrl) }
            var attempt = 0
            var lastError: Throwable? = null
            while (attempt < 4) {
                val streamFailure = CompletableDeferred<Throwable>()
                playbackFailure = streamFailure
                val ready = withTimeoutOrNull(30_000L) {
                    playUntilPlaying(streamUrl, streamFailure)
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

    private suspend fun playUntilPlaying(
        streamUrl: String,
        streamFailure: CompletableDeferred<Throwable>,
    ) {
        suspendCancellableCoroutine<Unit> { continuation ->
            val vlc = LibVLC(context)
            val vlcPlayer = MediaPlayer(vlc)
            videoSurface?.let { attachSurfaceTo(vlcPlayer, it) }
            val media = Media(vlc, Uri.parse(streamUrl))
            media.setHWDecoderEnabled(true, false)
            media.addOption(":network-caching=1000")
            media.addOption(":rtsp-tcp")
            media.addOption(":file-caching=1000")
            media.addOption(":live-caching=100")
            media.addOption(":drop-late-frames=true")
            media.addOption(":skip-frames=true")
            vlcPlayer.media = media
            media.release()
            var ready = false
            var firstFrame = false
            vlcPlayer.setEventListener(
                MediaPlayer.EventListener { event ->
                    when (event.type) {
                        MediaPlayer.Event.Playing -> {
                            Log.i(TAG, "Eyevue player event: Playing")
                            if (!ready) {
                                ready = true
                                if (continuation.isActive) continuation.resume(Unit)
                            }
                        }
                        MediaPlayer.Event.Paused,
                        MediaPlayer.Event.Stopped,
                        -> Log.i(TAG, "Eyevue player event: ${event.type}")
                        MediaPlayer.Event.EndReached -> {
                            val error = IOException("Eyevue RTSP stream ended")
                            if (continuation.isActive) {
                                continuation.resumeWith(Result.failure(error))
                            } else {
                                streamFailure.complete(error)
                            }
                        }
                        MediaPlayer.Event.EncounteredError -> {
                            val failure = IOException("Eyevue RTSP error")
                            if (continuation.isActive) {
                                continuation.resumeWith(Result.failure(failure))
                            } else {
                                streamFailure.complete(failure)
                            }
                        }
                        MediaPlayer.Event.Vout -> {
                            if (event.voutCount > 0 && !firstFrame) {
                                firstFrame = true
                                Log.i(TAG, "Eyevue player video output ready")
                            }
                        }
                        else -> Unit
                    }
                },
            )
            libVlc = vlc
            player = vlcPlayer
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
                if (player === vlcPlayer) releasePlayer()
            }
            vlcPlayer.play()
        }
    }

    private fun attachSurfaceTo(vlcPlayer: MediaPlayer, surface: Surface) {
        runCatching {
            vlcPlayer.vlcVout.setVideoSurface(surface, null)
            vlcPlayer.vlcVout.attachViews()
        }
    }

    private fun releasePlayer() {
        playbackFailure?.cancel()
        playbackFailure = null
        player?.let { vlcPlayer ->
            runCatching { vlcPlayer.stop() }
            runCatching { vlcPlayer.vlcVout.detachViews() }
            runCatching { vlcPlayer.release() }
        }
        player = null
        libVlc?.let { runCatching { it.release() } }
        libVlc = null
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
