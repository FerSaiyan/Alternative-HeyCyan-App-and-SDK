package com.fersaiyan.cyanbridge.shared.live

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fersaiyan.cyanbridge.shared.platform.PlatformLogger
import com.fersaiyan.cyanbridge.shared.platform.platformCurrentTimeMillis
import com.fersaiyan.cyanbridge.shared.viewmodel.DialogButton
import com.fersaiyan.cyanbridge.shared.viewmodel.ScreenDialog
import com.fersaiyan.cyanbridge.shared.viewmodel.ScreenEffect
import com.fersaiyan.cyanbridge.shared.viewmodel.ScreenEffects
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** States surfaced by the preview UI; no audio payload is retained in these states. */
enum class GeminiLiveState {
    IDLE,
    REQUESTING_TOKEN,
    CONNECTING,
    LISTENING,
    RECONNECTING,
    STOPPED,
    ERROR,
}

/** The live session behind the preview screen: the Live connection, glasses vision and the AI-photo button. */
interface GeminiLiveSession {
    fun microphoneGranted(): Boolean

    /** False when the platform would stop a background session the user cannot see (no notification). */
    fun notificationsGranted(): Boolean

    /** Starts glasses vision, then the Live connection in the app language. */
    fun start()
    fun stop()
    fun startVision()
    fun stopVision()
    fun pause()
    fun resume()
    fun setImageButtonEnabled(enabled: Boolean)

    /** Sends the image and prompt the screen was opened with, once the session is set up. */
    fun sendInitialTurn()
    fun close()
}

/** One press of the glasses' AI-photo button. */
interface LiveImageRequest {
    /** Captures the photo and sends it; true when the session queued it. */
    suspend fun captureAndSend(): Result<Boolean>

    /** Releases the photo after a capture attempt. */
    fun finish()

    /** Drops a press that cannot be used. */
    fun cancel()
}

data class GeminiLiveScreenState(
    /** Null texts keep what the screen shows before the session reports anything. */
    val status: String? = null,
    val elapsed: String? = null,
    val network: String? = null,
    val indicators: String = "Microphone: off   Glasses vision: off",
    /** Shows Stop instead of Start. */
    val active: Boolean = false,
)

data object RequestMicrophone : ScreenEffect

/** The visible Gemini Live session: status lines, timers, start and stop. */
class GeminiLiveViewModel(
    private val session: GeminiLiveSession,
    private val hasPaidPlan: () -> Boolean,
) : ViewModel() {
    private val _state = MutableStateFlow(GeminiLiveScreenState())
    val state: StateFlow<GeminiLiveScreenState> = _state.asStateFlow()
    private val effects = ScreenEffects()
    val effectFlow get() = effects.flow

    private var useRelayForFreeTier = !hasPaidPlan()
    private var listening = false
    private var resumed = false
    private var startedAtMs = 0L
    private var visionStatus = "Glasses vision: waiting"
    private var initialTurnSent = false
    private var autoStarted = false
    private var imageCaptureInProgress = false
    private var tickerJob: Job? = null
    private var idleStopJob: Job? = null

    private fun setStatus(text: String) = _state.update { it.copy(status = text) }

    /** Starts at once when the screen was opened to auto-start (only on its first creation). */
    fun onCreate(autoStart: Boolean) {
        if (!autoStart || autoStarted) return
        autoStarted = true
        if (session.microphoneGranted()) startLive() else effects.send(RequestMicrophone)
    }

    fun onMicrophoneResult(granted: Boolean) {
        if (granted) startLive() else effects.toast("Microphone permission is required for Gemini Live", long = true)
    }

    /** Start: explains the Pro direct or Free relay route, then asks for the microphone. */
    fun requestStart() {
        // Pro connects directly with a constrained token. Free connects to the authenticated
        // CyanBridge WebSocket proxy, which retains both Google credentials on Vercel.
        val isPro = hasPaidPlan()
        useRelayForFreeTier = !isPro
        val message = if (isPro) {
            "Gemini Live (Pro) uses an ephemeral token + GCP Vertex paid route. Your microphone streams " +
                "directly to Google with a short-lived token (no API key on device). Glasses vision is streamed live."
        } else {
            "Gemini Live (Free) routes continuously through the CyanBridge server. Vercel holds the " +
                "Google API key and ephemeral token while proxying selected speech and images. " +
                "No Google credential is stored on this device."
        }
        effects.send(
            ScreenDialog(
                title = if (isPro) "Gemini Live — Pro (direct)" else "Gemini Live — Free (relay)",
                message = message + " Compatible streaming glasses contribute visual context. Other Pro models are unaffected.",
                positive = DialogButton("Continue") {
                    if (session.microphoneGranted()) startLive() else effects.send(RequestMicrophone)
                },
                negative = DialogButton("Cancel"),
            ),
        )
    }

    fun stop() {
        cancelResponseIdle()
        session.stopVision()
        session.stop()
    }

    fun onResume() {
        session.resume()
        if (listening) session.startVision()
    }

    fun onPostResume() {
        resumed = true
        updateImageButton()
    }

    fun onPause() {
        resumed = false
        updateImageButton()
        // Without notifications this is a foreground-only session: never keep the socket alive
        // when the user locks the phone or leaves the preview.
        if (!session.notificationsGranted()) {
            cancelResponseIdle()
            session.stop()
        }
        session.stopVision()
        session.pause()
    }

    override fun onCleared() {
        cancelResponseIdle()
        tickerJob?.cancel()
        session.setImageButtonEnabled(false)
        session.close()
    }

    private fun startLive() {
        visionStatus = if (useRelayForFreeTier) "Glasses vision: relay (server holds key)" else "Glasses vision: preparing"
        renderIndicators()
        setStatus(if (useRelayForFreeTier) "Connecting to CyanBridge Live relay" else "Connecting to Gemini Live")
        session.start()
    }

    // ── Session events (main thread) ──

    fun onStateChanged(state: GeminiLiveState, detail: String) {
        setStatus(detail.ifBlank { state.name.lowercase().replaceFirstChar(Char::uppercase) })
        listening = state == GeminiLiveState.LISTENING
        updateImageButton()

        if (listening) {
            session.startVision()
            if (startedAtMs == 0L) {
                startedAtMs = platformCurrentTimeMillis()
                startTicker()
            }
        }
        if (state == GeminiLiveState.STOPPED || state == GeminiLiveState.ERROR) {
            cancelResponseIdle()
            session.stopVision()
            startedAtMs = 0L
            tickerJob?.cancel()
            _state.update { it.copy(elapsed = "Session not running") }
        }
        renderIndicators()
        _state.update {
            it.copy(
                active = listening ||
                    state == GeminiLiveState.CONNECTING ||
                    state == GeminiLiveState.RECONNECTING ||
                    state == GeminiLiveState.REQUESTING_TOKEN,
            )
        }
    }

    fun onInterrupted() = setStatus("Gemini was interrupted. Listening for you.")

    fun onNetworkChanged(available: Boolean) = _state.update {
        it.copy(network = if (available) "Network: connected" else "Network: lost, reconnecting when available")
    }

    fun onVisionStatus(message: String) {
        visionStatus = message
        renderIndicators()
    }

    /** User speech, model output and input transcription all keep the session open. */
    fun onActivity() = cancelResponseIdle()

    fun onModelTurnComplete() {
        cancelResponseIdle()
        if (listening) {
            idleStopJob = viewModelScope.launch {
                delay(POST_RESPONSE_IDLE_MS)
                if (listening) {
                    PlatformLogger.i(TAG, "Closing after 30 seconds following completed model turn")
                    session.stopVision()
                    session.stop()
                }
            }
        }
    }

    fun onSetupComplete() {
        if (initialTurnSent) return
        initialTurnSent = true
        session.sendInitialTurn()
    }

    fun onImageButton(request: LiveImageRequest) {
        if (!listening) {
            request.cancel()
            return
        }
        cancelResponseIdle()
        if (imageCaptureInProgress) {
            request.cancel()
            return
        }
        imageCaptureInProgress = true
        setStatus("Receiving glasses AI photo")
        viewModelScope.launch {
            try {
                request.captureAndSend()
                    .onSuccess { queued ->
                        if (queued) {
                            visionStatus = "Glasses vision: manual AI-photo queued"
                            setStatus("Image queued for Gemini Live")
                        } else {
                            visionStatus = "Glasses vision: image was not queued"
                            setStatus("Live connection is not ready for an image")
                        }
                        renderIndicators()
                    }
                    .onFailure { effects.toast(it.message ?: "Glasses image capture failed", long = true) }
            } finally {
                request.finish()
                imageCaptureInProgress = false
            }
        }
    }

    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = viewModelScope.launch {
            while (startedAtMs > 0L) {
                val seconds = (platformCurrentTimeMillis() - startedAtMs) / 1_000L
                _state.update { it.copy(elapsed = "Session ${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}") }
                delay(1_000L)
            }
        }
    }

    private fun cancelResponseIdle() {
        idleStopJob?.cancel()
        idleStopJob = null
    }

    private fun renderIndicators() = _state.update {
        it.copy(indicators = if (listening) "Microphone: on   $visionStatus" else "Microphone: off   Glasses vision: off")
    }

    private fun updateImageButton() = session.setImageButtonEnabled(listening && resumed)

    companion object {
        /** A finished answer keeps the session open this long for a follow-up. */
        const val POST_RESPONSE_IDLE_MS = 30_000L
        private const val TAG = "GeminiLiveActivity"
    }
}

/** Where the Live permission check sends the user. */
enum class GeminiLiveRoute {
    /** No microphone: nothing starts. */
    DENIED,

    /** Notification-driven session that survives the screen turning off. */
    BACKGROUND_SERVICE,

    /** Notifications are off: only the open preview may hold the microphone. */
    VISIBLE_PREVIEW;

    val message: String?
        get() = when (this) {
            DENIED -> "Microphone permission is required for Gemini Live"
            BACKGROUND_SERVICE -> null
            VISIBLE_PREVIEW -> "Notifications are off: Live will run only in the open preview. Use its Stop button."
        }

    companion object {
        fun of(microphoneGranted: Boolean, notificationsAllowed: Boolean) = when {
            !microphoneGranted -> DENIED
            notificationsAllowed -> BACKGROUND_SERVICE
            else -> VISIBLE_PREVIEW
        }
    }
}
