package com.fersaiyan.cyanbridge.devices.eyevue

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import java.io.IOException

/**
 * Eyevue live-mode flow, vendor-matched: command BLE, join vendor Wi-Fi,
 * request the HTTP live endpoint, then play video and audio with LibVLC.
 * A single upstream session supplies the inline player and external VLC.
 */
class EyevueLivePreviewManager internal constructor(
    private val context: Context,
    private val connection: EyevueLiveConnection,
) {
    constructor(context: Context, eyevueManager: EyevueManager) :
        this(context, EyevueGlassesLiveConnection(context, eyevueManager))
    companion object {
        private const val TAG = "EyevueLive"
    }

    private val _uiState = MutableStateFlow(LivePreviewState())
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var job: Job? = null
    private var libVlc: LibVLC? = null
    private var player: MediaPlayer? = null
    private var playbackFailure: CompletableDeferred<Throwable>? = null
    private var videoView: EyevueLiveVideoView? = null
    private var viewAttached: CompletableDeferred<Unit>? = null
    private var frameOutput: EyevueVideoFrameOutput? = null
    private var frameConsumer: ((Bitmap) -> Unit)? = null
    private var audioMuted = false
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

    /** Headless video; caller owns delivered bitmaps. Audio is muted for spoken warnings. */
    fun startFrames(onFrame: (Bitmap) -> Unit, onSessionFinished: () -> Unit) {
        check(!isActive) { "Eyevue stream is already active" }
        frameConsumer = onFrame
        setAudioMuted(true)
        start(onSessionFinished)
    }

    suspend fun stopAndJoin() {
        val active = job
        stop()
        active?.join()
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

    /** Attach the vendor's view helper before starting the decoder. Main thread only. */
    fun attachVideoView(view: EyevueLiveVideoView) {
        if (videoView !== view) videoView?.bindPlayer(null)
        videoView = view
        player?.let {
            view.bindPlayer(it)
            viewAttached?.complete(Unit)
        }
    }

    fun detachVideoView(view: EyevueLiveVideoView) {
        view.bindPlayer(null)
        if (videoView === view) videoView = null
    }

    fun setAudioMuted(muted: Boolean) {
        audioMuted = muted
        player?.setVolume(if (muted) 0 else 100)
    }

    private suspend fun run() {
        var failed = false
        try {
            val streamUrl = connection.open { label, detail -> updateState(label, detail, scanning = true) }
            Log.i(TAG, "Eyevue shared video+audio: $streamUrl")
            runCatching { onRelayUrlChanged?.invoke(streamUrl) }
            var attempt = 0
            var lastError: Throwable? = null
            while (attempt < 4) {
                val streamFailure = CompletableDeferred<Throwable>()
                playbackFailure = streamFailure
                try {
                    val ready = withTimeoutOrNull(30_000L) {
                        playUntilPlaying(streamUrl, streamFailure)
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
                // Cancel relay reads first so LibVLC stop/release can't wait for a
                // stalled local handshake. Native teardown runs off the UI thread.
                try {
                    connection.close()
                } finally {
                    releasePlayer()
                    frameConsumer = null
                    if (!failed) resetState()
                    job = null
                    notifyFinished()
                }
            }
        }
    }

    private suspend fun playUntilPlaying(
        streamUrl: String,
        streamFailure: CompletableDeferred<Throwable>,
    ) {
        val ready = CompletableDeferred<Unit>()
        val attached = CompletableDeferred<Unit>()
        viewAttached = attached
        val vlc = LibVLC(context)
        libVlc = vlc
        val vlcPlayer = MediaPlayer(vlc)
        player = vlcPlayer
        try {
            val media = Media(vlc, Uri.parse(streamUrl))
            // The Samsung trace reports an unknown MediaCodec output format and
            // decoder buffer deadlocks. External VLC successfully chose avcodec.
            // Software H264/AAC decoding is sufficient for this 640x480 preview.
            media.addOption(":codec=avcodec")
            media.addOption(":network-caching=1000")
            media.addOption(":rtsp-tcp")
            media.addOption(":file-caching=1000")
            media.addOption(":live-caching=100")
            media.addOption(":drop-late-frames=true")
            media.addOption(":skip-frames=true")
            vlcPlayer.media = media
            media.release()
            vlcPlayer.setVolume(if (audioMuted) 0 else 100)
            var videoOutputReady = false
            var playing = false
            fun completeWhenVideoIsReady() {
                if (playing && videoOutputReady) ready.complete(Unit)
            }
            vlcPlayer.setEventListener(
                MediaPlayer.EventListener { event ->
                    when (event.type) {
                        MediaPlayer.Event.Playing -> {
                            Log.i(TAG, "Eyevue player event: Playing")
                            playing = true
                            completeWhenVideoIsReady()
                        }
                        MediaPlayer.Event.Paused,
                        MediaPlayer.Event.Stopped,
                        -> Log.i(TAG, "Eyevue player event: ${event.type}")
                        MediaPlayer.Event.EndReached -> {
                            val error = IOException("Eyevue RTSP stream ended")
                            ready.completeExceptionally(error)
                            streamFailure.complete(error)
                        }
                        MediaPlayer.Event.EncounteredError -> {
                            val failure = IOException("Eyevue RTSP error")
                            ready.completeExceptionally(failure)
                            streamFailure.complete(failure)
                        }
                        MediaPlayer.Event.Vout -> {
                            if (event.voutCount > 0 && !videoOutputReady) {
                                videoOutputReady = true
                                val track = vlcPlayer.currentVideoTrack
                                Log.i(TAG, "Eyevue video output ready: ${track?.width}x${track?.height}, audio tracks=${vlcPlayer.audioTracksCount}")
                                completeWhenVideoIsReady()
                            }
                        }
                        MediaPlayer.Event.Buffering -> Log.d(TAG, "Eyevue buffering: ${event.buffering}%")
                        else -> Unit
                    }
                },
            )
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
            val consumer = frameConsumer
            if (consumer != null) {
                frameOutput = EyevueVideoFrameOutput(
                    onFrame = consumer,
                    onError = { error ->
                        ready.completeExceptionally(error)
                        streamFailure.complete(error)
                    },
                ).also { it.attach(vlcPlayer) }
            } else {
                videoView?.let { attachVideoView(it) }
                attached.await() // Compose builds the inline view from the published state.
            }
            vlcPlayer.play()
            ready.await()
        } finally {
            viewAttached = null
        }
    }

    private suspend fun releasePlayer() {
        playbackFailure?.cancel()
        playbackFailure = null
        val oldPlayer = player
        val oldVlc = libVlc
        oldPlayer?.setEventListener(null)
        videoView?.bindPlayer(null)
        frameOutput?.close(oldPlayer)
        frameOutput = null
        player = null
        libVlc = null
        withContext(NonCancellable + Dispatchers.IO) {
            runCatching { oldPlayer?.stop() }.onFailure { Log.w(TAG, "LibVLC stop failed", it) }
            runCatching { oldPlayer?.release() }.onFailure { Log.w(TAG, "LibVLC release failed", it) }
            runCatching { oldVlc?.release() }
        }
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
