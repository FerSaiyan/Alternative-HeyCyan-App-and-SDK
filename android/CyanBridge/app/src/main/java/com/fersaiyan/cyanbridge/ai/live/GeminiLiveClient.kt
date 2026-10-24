package com.fersaiyan.cyanbridge.ai.live

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import com.fersaiyan.cyanbridge.agent.ProSubscriptionAiPrefs
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

/**
 * Direct Gemini Live WebSocket client. The relay only issues the short-lived token.
 *
 * This client is instantiated only by the explicit Gemini Live flow. Being a Pro user does not
 * activate Live Mode or change routing for any other model/provider.
 *
 * It is the façade over [GeminiLiveProtocol] (wire format), [GeminiLiveAudioIo] (microphone,
 * playback, routing, focus), [GeminiLiveSessionState] (conversation state and metering),
 * [GeminiLiveVisualAudioHold] (turn-tail hold for fresh images) and [GeminiLiveRelayReporter]
 * (relay billing/diagnostics). It owns the socket, the timers and the lifecycle decisions.
 */
class GeminiLiveClient(
    context: Context,
    private val listener: Listener,
    private val tokenProvider: GeminiLiveTokenProvider? = null,
) {
    interface Listener {
        fun onStateChanged(state: GeminiLiveState, detail: String = "")
        fun onInterrupted()
        fun onNetworkChanged(available: Boolean)
        fun onUserSpeechActivity(active: Boolean) = Unit
        fun onTranscription(input: Boolean, text: String) = Unit
        fun onSetupComplete() = Unit
        fun onModelOutputStarted() = Unit
        fun onModelTurnComplete() = Unit
        fun onAnnouncement(kind: GeminiLiveAnnouncement) = Unit
    }

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
    private val session = GeminiLiveSessionState()
    private val audio = GeminiLiveAudioIo(
        context = appContext,
        scope = scope,
        onFocusLost = { stop() },
        onFocusLostTransiently = { pauseCapture() },
        onFocusGained = { resumeCapture() },
        onRouteChanged = { notifyRouteChange() },
        onCaptureError = { detail -> setState(GeminiLiveState.ERROR, detail) },
    )
    private val relay = GeminiLiveRelayReporter(appContext, http, scope)
    private val connectivity = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val visualAudioHold = GeminiLiveVisualAudioHold()
    private val speechDetector = GeminiLiveSpeechActivityDetector { speaking ->
        // Always retain the local energy state. During Gemini playback we suppress the
        // vision callback to avoid speaker echo triggering a picture, but the state is
        // still used to confirm that a server-side interruption was backed by local speech.
        session.speechActive.set(speaking)
        if (session.modelSpeaking.get()) {
            Log.d(TAG, "Local speech energy during Gemini playback active=$speaking (callback suppressed)")
        } else {
            Log.d(TAG, "Local speech energy active=$speaking")
            listener.onUserSpeechActivity(speaking)
        }
    }

    private val effectiveTokenProvider: GeminiLiveTokenProvider by lazy {
        tokenProvider ?: DefaultGeminiLiveTokenProvider(appContext, http)
    }
    private var socket: WebSocket? = null
    private var reconnectJob: Job? = null
    private var sessionResumptionJob: Job? = null
    private var meteredSessionLimitJob: Job? = null
    private var visionReportJob: Job? = null

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            listener.onNetworkChanged(true)
            if (session.active.get() && socket == null) connectOrReconnect()
        }

        override fun onLost(network: Network) {
            if (!hasInternet()) {
                listener.onNetworkChanged(false)
                socket?.cancel()
            }
        }
    }

    init {
        connectivity.registerDefaultNetworkCallback(networkCallback)
        audio.registerDeviceCallback()
    }

    fun start(language: String, imagePrompt: String) {
        if (!session.active.compareAndSet(false, true)) return
        meteredSessionLimitJob?.cancel()
        meteredSessionLimitJob = null
        session.resetForStart()
        clearDelayedAudio()
        speechDetector.reset()
        // Store for setup to match token's bidiGenerateContentSetup
        session.language = language.ifBlank { "en" }
        session.imagePrompt = imagePrompt
        session.systemPrompt = ProSubscriptionAiPrefs.getSystemPrompt(appContext)
        setState(GeminiLiveState.REQUESTING_TOKEN, "Requesting secure Live session")
        Log.i(TAG, "Live start requested language=$language prompt=${imagePrompt.take(80)}")
        scope.launch {
            runCatching { requestToken(language, imagePrompt) }
                .onSuccess {
                    if (!session.active.get()) return@onSuccess
                    session.tokenConfig = it
                    session.visionReportConfig = it
                    Log.i(TAG, "Live token ok model=${it.model} reservation=${it.reservationId} url=${it.websocketUrl.take(80)} expires=${it.expiresAtMs} auth=${it.authorizationHeader?.take(20)} apiKey=${if (it.apiKey.isNullOrBlank()) "none" else "present"}")
                    connectOrReconnect()
                }
                .onFailure {
                    if (!session.active.get()) return@onFailure
                    session.active.set(false)
                    Log.e(TAG, "Live token failed", it)
                    val rejection = it as? LiveTokenRequestException
                    val announcement = when (rejection?.errorCode) {
                        "live_concurrent_session" -> GeminiLiveAnnouncement.PRIVATE_SESSION_BUSY
                        "live_rate_limited" -> GeminiLiveAnnouncement.PRIVATE_RATE_LIMITED
                        else -> GeminiLiveAnnouncement.GENERIC_FAILURE
                    }
                    listener.onAnnouncement(announcement)
                    setState(GeminiLiveState.ERROR, it.message ?: "Unable to start Gemini Live")
                }
        }
    }

    fun stop() {
        reportVisionDiagnostics()
        session.active.set(false)
        session.setupComplete.set(false)
        session.modelSpeaking.set(false)
        clearDelayedAudio()
        reconnectJob?.cancel()
        reconnectJob = null
        sessionResumptionJob?.cancel()
        sessionResumptionJob = null
        meteredSessionLimitJob?.cancel()
        meteredSessionLimitJob = null
        socket?.close(1000, "user_stopped")
        socket = null
        stopCapture()
        audio.stopPlayback()
        audio.endSession()
        session.sessionResumptionHandle = null
        session.tokenConfig?.reservationId?.takeUnless { it == "free-proxy" || it == "direct" }?.let { reservationId ->
            scope.launch {
                relay.releaseReservation(
                    reservationId = reservationId,
                    inputAudioMs = session.inputAudioMs,
                    outputAudioMs = session.outputAudioMs,
                    imageCount = session.visualInputCount,
                )
            }
        }
        session.tokenConfig = null
        setState(GeminiLiveState.STOPPED, "Stopped")
    }

    fun close() {
        stop()
        connectivity.unregisterNetworkCallback(networkCallback)
        audio.unregisterDeviceCallback()
    }

    fun pauseForBackground() = pauseCapture()

    fun resumeAfterForeground() {
        if (session.active.get() && session.setupComplete.get() && session.state == GeminiLiveState.LISTENING) resumeCapture()
    }

    /** Accepts raw glasses PCM if a device SDK exposes it. */
    fun offerGlassesPcm(pcm: ShortArray, sampleRateHz: Int) {
        if (!session.active.get() || !session.setupComplete.get() || !audio.captureEnabled.get()) return
        val normalized = PcmResampler.resampleMono16(pcm, sampleRateHz, GeminiLiveProtocol.INPUT_SAMPLE_RATE_HZ)
        val bytes = ByteArray(normalized.size * 2)
        normalized.forEachIndexed { index, sample ->
            bytes[index * 2] = (sample.toInt() and 0xff).toByte()
            bytes[index * 2 + 1] = ((sample.toInt() ushr 8) and 0xff).toByte()
        }
        sendPcm(bytes)
    }

    /** Direct 16 kHz mono signed little-endian PCM; no second AudioRecord. */
    fun offerMentraPcm(pcm: ByteArray) {
        if (audio.mentraMicActive && session.active.get() && session.setupComplete.get() &&
            audio.captureEnabled.get() && pcm.isNotEmpty() && pcm.size % 2 == 0
        ) sendPcm(pcm)
    }

    /** Backward-compatible name for explicit still images. */
    fun sendImage(jpegBytes: ByteArray) = sendVideoFrame(jpegBytes)

    fun sendTextTurn(text: String) {
        if (!session.active.get() || !session.setupComplete.get() || text.isBlank()) return
        socket?.send(GeminiLiveProtocol.buildClientTextTurn(text))
    }

    /** Sends one JPEG frame through the current realtimeInput.video field. */
    fun sendVideoFrame(jpegBytes: ByteArray): Boolean {
        val limit = if (session.tokenConfig?.let { it.freeTier || it.economy } == true) 10 else MAX_VISUAL_INPUTS_PER_SESSION
        if (session.visualInputCount >= limit) {
            setState(GeminiLiveState.LISTENING, "Live visual-input safety limit reached")
        }
        val accepted = session.visionDiagnostics.send(
            ready = session.active.get() && session.setupComplete.get(),
            bytes = jpegBytes,
            limit = limit,
        ) {
            sendRealtimeBlob(GeminiLiveProtocol.REALTIME_VIDEO_FIELD, GeminiLiveProtocol.IMAGE_MIME_TYPE, jpegBytes)
        }
        Log.i(TAG, "Live image queued=$accepted diagnostics=${session.visionDiagnostics.snapshot()}")
        scheduleVisionDiagnosticsReport()
        return accepted
    }

    fun recordVisionCaptureStarted() {
        session.visionDiagnostics.captureStarted()
        scheduleVisionDiagnosticsReport()
    }

    fun recordVisionCaptureFinished(success: Boolean, error: Throwable? = null) {
        session.visionDiagnostics.captureFinished(success, error)
        Log.i(TAG, "Live capture success=$success diagnostics=${session.visionDiagnostics.snapshot()}")
        scheduleVisionDiagnosticsReport()
    }

    private fun scheduleVisionDiagnosticsReport() {
        synchronized(session.visionDiagnostics) {
            visionReportJob?.cancel()
            visionReportJob = scope.launch {
                delay(1_000)
                reportVisionDiagnostics()
            }
        }
    }

    private fun reportVisionDiagnostics() {
        val config = session.tokenConfig ?: session.visionReportConfig ?: return
        if (config.reservationId == "direct") return
        relay.reportVisionDiagnostics(config, session.visionReportId, session.visionDiagnostics.snapshot())
    }

    /** Prevents trailing silence from finalizing a turn before its fresh image is sent. */
    fun deferAudioForVisualContext() {
        visualAudioHold.defer(isLive = { session.isLive }) {
            Log.i(TAG, "Deferring Live turn completion until fresh visual context is ready")
        }
    }

    /** Sends the image first, then releases the held turn tail in socket order. */
    fun releaseAudioAfterVisualContext() {
        visualAudioHold.release(isLive = { session.isLive }, send = ::sendAudioPacket) { chunks ->
            Log.i(TAG, "Released deferred Live audio chunks=$chunks after visual context")
        }
    }

    fun cancelDeferredVisualContext() = clearDelayedAudio()

    private fun requestToken(language: String, imagePrompt: String): LiveTokenConfig =
        kotlinx.coroutines.runBlocking { effectiveTokenProvider.requestToken(language, imagePrompt) }

    private fun connectOrReconnect() {
        if (!session.active.get()) return
        val config = session.tokenConfig ?: return
        if (GeminiLiveSessionPolicy.requiresExplicitRestart(config.freeTier, config.economy) && session.meteredConnectionAttempted.get()) {
            Log.i(TAG, "Prevented repeat Free/Economy socket connection without new user request")
            return
        }
        if (System.currentTimeMillis() >= config.expiresAtMs) {
            session.active.set(false)
            listener.onAnnouncement(GeminiLiveAnnouncement.SESSION_ENDED)
            setState(GeminiLiveState.ERROR, "Live session expired. Start a new session.")
            return
        }
        if (!hasInternet()) {
            setState(GeminiLiveState.RECONNECTING, "Waiting for network")
            return
        }

        session.setupComplete.set(false)
        clearDelayedAudio()
        socket?.cancel()
        setState(
            if (session.reconnectAttempt == 0) GeminiLiveState.CONNECTING else GeminiLiveState.RECONNECTING,
            "Connecting to Google",
        )
        val request = GeminiLiveProtocol.buildConnectRequest(config, session.visionReportId) {
            com.fersaiyan.cyanbridge.ui.localization.AppLanguagePreferences.selected(appContext).languageTag
                .ifBlank { java.util.Locale.getDefault().toLanguageTag() }
        }
        if (GeminiLiveSessionPolicy.requiresExplicitRestart(config.freeTier, config.economy) &&
            !session.meteredConnectionAttempted.compareAndSet(false, true)
        ) {
            Log.i(TAG, "Blocked racing duplicate Free/Economy WebSocket attempt")
            return
        }
        socket = http.newWebSocket(request, SocketListener(config))
    }

    private inner class SocketListener(private val config: LiveTokenConfig) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (!session.active.get()) {
                webSocket.close(1000, "stopped")
                return
            }
            socket = webSocket
            session.reconnectAttempt = 0
            sendSetup(webSocket, config)
            if (!GeminiLiveSessionPolicy.requiresExplicitRestart(config.freeTier, config.economy)) scheduleSessionResumption(webSocket)
            setState(GeminiLiveState.CONNECTING, "Connected; waiting for Gemini setup")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            handleServerMessage(text)
        }

        override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
            // Google's direct endpoint can send UTF-8 JSON in binary frames.
            // The free proxy converts these to text, which hid this missing callback.
            handleServerMessage(bytes.utf8())
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            Log.w(TAG, "Gemini Live socket closing code=$code reason=$reason")
            webSocket.close(code, reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            val wasSetupComplete = session.setupComplete.get()
            val listeningDurationMs = session.listeningDurationMs(System.currentTimeMillis())
            Log.w(TAG, "Gemini Live socket closed code=$code reason=$reason active=${session.active.get()} setupComplete=$wasSetupComplete")
            if (socket === webSocket) socket = null
            session.setupComplete.set(false)
            if (failedBeforeSetup(config, wasSetupComplete, "Gemini Live connection failed ($code). Please try again.")) return
            if (session.active.get() && GeminiLiveSessionPolicy.requiresExplicitRestart(config.freeTier, config.economy)) {
                Log.i(TAG, "Metered Live socket closed; a new connection requires a user action")
                stop()
                return
            }
            reconnectAfterDrop(config, wasSetupComplete, listeningDurationMs, reason)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            val wasSetupComplete = session.setupComplete.get()
            val listeningDurationMs = session.listeningDurationMs(System.currentTimeMillis())
            if (socket === webSocket) socket = null
            session.setupComplete.set(false)
            Log.w(TAG, "Gemini Live socket failed code=${response?.code} msg=${response?.message} err=${t.message}", t)
            val responseBody = if (GeminiLiveSocketFailurePolicy.shouldReadBody(response?.code)) {
                try { response?.body?.string().orEmpty() } catch (_: Exception) { "" }
            } else ""
            when (val outcome = GeminiLiveSocketFailurePolicy.classify(config, response?.code, responseBody, t.message)) {
                is GeminiLiveSocketFailurePolicy.Outcome.FreeDailyLimit -> {
                    listener.onAnnouncement(GeminiLiveAnnouncement.FREE_DAILY_LIMIT_REACHED)
                    session.active.set(false)
                    setState(GeminiLiveState.ERROR, outcome.detail)
                    return
                }
                is GeminiLiveSocketFailurePolicy.Outcome.EconomyRejected -> {
                    stop()
                    setState(GeminiLiveState.ERROR, outcome.detail)
                    return
                }
                is GeminiLiveSocketFailurePolicy.Outcome.FreeQueued -> {
                    val queuedDetail = outcome.detail ?: localizedFreeQueueMessage()
                    session.active.set(false)
                    listener.onAnnouncement(GeminiLiveAnnouncement.FREE_BUSY)
                    setState(GeminiLiveState.ERROR, queuedDetail)
                    return
                }
                GeminiLiveSocketFailurePolicy.Outcome.Retryable -> Unit
            }
            if (failedBeforeSetup(config, wasSetupComplete, "Gemini Live connection failed. Please try again.")) return
            if (session.active.get() && GeminiLiveSessionPolicy.requiresExplicitRestart(config.freeTier, config.economy)) {
                Log.i(TAG, "Metered Live socket failed; not automatically starting another session")
                stop()
                setState(GeminiLiveState.ERROR, "Live disconnected. Start a new session manually to continue.")
                return
            }
            reconnectAfterDrop(config, wasSetupComplete, listeningDurationMs, t.message.orEmpty())
        }
    }

    /** A private reservation that never reached setupComplete is released and not retried. */
    private fun failedBeforeSetup(config: LiveTokenConfig, wasSetupComplete: Boolean, detail: String): Boolean {
        if (wasSetupComplete || !session.active.get() || config.reservationId == "free-proxy" || config.reservationId == "direct") {
            return false
        }
        session.active.set(false)
        scope.launch {
            relay.releaseReservation(
                reservationId = config.reservationId,
                inputAudioMs = session.inputAudioMs,
                outputAudioMs = session.outputAudioMs,
                imageCount = session.visualInputCount,
            )
        }
        session.tokenConfig = null
        setState(GeminiLiveState.ERROR, detail)
        return true
    }

    private fun reconnectAfterDrop(config: LiveTokenConfig, wasSetupComplete: Boolean, listeningDurationMs: Long, reason: String) {
        if (!session.active.get()) return
        val announcement = GeminiLiveReconnectAnnouncementPolicy.resolve(
            proxySession = config.reservationId == "free-proxy",
            freeTier = config.freeTier,
            setupCompleted = wasSetupComplete,
            listeningDurationMs = listeningDurationMs,
            reason = reason,
        )
        announcement?.let(listener::onAnnouncement)
        scheduleReconnect(GeminiLiveReconnectAnnouncementPolicy.detail(announcement))
    }

    private fun localizedFreeQueueMessage(): String {
        val tag = runCatching {
            com.fersaiyan.cyanbridge.ui.localization.AppLanguagePreferences.selected(appContext).languageTag
                .ifBlank { java.util.Locale.getDefault().toLanguageTag() }
        }.getOrDefault("en")
        return GeminiLiveSocketFailurePolicy.freeQueueMessage(tag)
    }

    private fun sendSetup(webSocket: WebSocket, config: LiveTokenConfig) {
        val setup = GeminiLiveProtocol.buildSetup(
            config = config,
            sessionResumptionHandle = session.sessionResumptionHandle,
            language = session.language,
            imagePrompt = session.imagePrompt,
            systemPrompt = session.systemPrompt,
            storedSystemPrompt = { ProSubscriptionAiPrefs.getSystemPrompt(appContext) },
        )
        // Do not log the user-provided system instruction or session-resumption handle.
        Log.i(TAG, "Sending Live setup model=${config.model} serverProvided=${setup.serverProvided} resuming=${!session.sessionResumptionHandle.isNullOrBlank()}")
        check(webSocket.send(setup.json)) {
            "Live setup could not be sent"
        }
    }

    private fun handleServerMessage(raw: String) {
        // Verbose log for diagnosing why Live never reaches setupComplete (user reported no listening cue / notification)
        Log.d(TAG, "Live server message raw=${raw.take(2000)}")
        val events = GeminiLiveProtocol.parseServerMessage(raw) ?: run {
            Log.w(TAG, "Ignoring malformed Gemini Live message raw=${raw.take(500)}")
            return
        }
        for (event in events) {
            when (event) {
                is GeminiLiveServerEvent.Error -> {
                    if (!event.quotaExhausted) listener.onAnnouncement(GeminiLiveAnnouncement.GENERIC_FAILURE)
                    stop()
                    val detail = if (event.quotaExhausted)
                        "Live quota exhausted. Please wait for quota reset or upgrade."
                    else "Gemini Live session failed. Please try again."
                    setState(GeminiLiveState.ERROR, detail)
                }
                GeminiLiveServerEvent.SetupComplete -> if (session.setupComplete.compareAndSet(false, true)) onSetupComplete()
                is GeminiLiveServerEvent.SessionResumptionUpdate -> session.sessionResumptionHandle = event.newHandle
                is GeminiLiveServerEvent.GoAway -> Log.i(TAG, "Gemini Live goAway received; timeLeft=${event.timeLeft}")
                // Google-recommended metering: usageMetadata is the billed tokens for this turn
                // (includes re-billed sliding window). Forward it for server-side quota debit.
                is GeminiLiveServerEvent.UsageMetadata -> handleUsageMetadata(event.usage)
                is GeminiLiveServerEvent.Transcription -> listener.onTranscription(event.input, event.text)
                GeminiLiveServerEvent.Interrupted -> onServerInterrupted()
                is GeminiLiveServerEvent.ModelAudio -> playPcm(event.pcm)
                GeminiLiveServerEvent.TurnComplete -> if (session.active.get()) {
                    finishModelPlayback()
                    listener.onModelTurnComplete()
                    setState(GeminiLiveState.LISTENING, "Gemini Live is listening")
                    reportTurnUsageIfNeeded()
                }
            }
        }
        // If message had only usageMetadata and no serverContent, billing already happened via handleUsageMetadata
    }

    private fun onSetupComplete() {
        session.socketListeningStartedAtMs = System.currentTimeMillis()
        session.tokenConfig?.takeIf { it.freeTier || it.economy }?.let { config ->
            // The proxy/economy route can debit a daily allowance. Do not silently renew.
            if (meteredSessionLimitJob == null) {
                meteredSessionLimitJob = scope.launch {
                    delay(GeminiLiveSessionPolicy.METERED_SESSION_LIMIT_MS)
                    if (session.active.get()) {
                        Log.i(TAG, "Free/Economy Live reached five-minute limit; stopping without renewal")
                        listener.onAnnouncement(GeminiLiveAnnouncement.SESSION_ENDED)
                        stop()
                    }
                }
                Log.i(TAG, "Metered Live five-minute cap armed")
            }
        }
        startCapture()
        setState(GeminiLiveState.LISTENING, "Gemini Live is listening")
        listener.onSetupComplete()
    }

    private fun onServerInterrupted() {
        // A server interruption alone is not enough to refresh vision: it can be
        // caused by noise/echo. Require simultaneous local energy evidence so barge-in
        // speech still gets a fresh image without reintroducing spontaneous captures.
        val locallyConfirmedSpeech = session.speechActive.get()
        audio.interruptPlayback()
        finishModelPlayback()
        if (locallyConfirmedSpeech) listener.onUserSpeechActivity(true)
        listener.onInterrupted()
        // Interrupted still ends a turn — bill the partial turn via usageMetadata if present,
        // otherwise via audio ms delta.
        reportTurnUsageIfNeeded()
    }

    private fun handleUsageMetadata(usage: JSONObject) {
        if (!session.acceptUsageMetadata(usage)) return
        reportLiveUsage(usageMetadata = usage, deltaInputMs = 0, deltaOutputMs = 0, deltaImages = 0)
    }

    private fun reportTurnUsageIfNeeded() {
        val usage = session.pendingTurnUsage() ?: return
        // If we just billed via usageMetadata for this turn, lastUsageTotalTokens will have been updated
        // and delta will be small; we still report delta as fallback but server will deduplicate via max logic.
        reportLiveUsage(usageMetadata = null, deltaInputMs = usage.deltaInputMs, deltaOutputMs = usage.deltaOutputMs, deltaImages = usage.deltaImages)
        session.markTurnBilled()
    }

    private fun reportLiveUsage(usageMetadata: JSONObject?, deltaInputMs: Long, deltaOutputMs: Long, deltaImages: Int) {
        val cfg = session.tokenConfig ?: return
        if (cfg.reservationId == "free-proxy" || cfg.reservationId == "direct") return
        relay.reportUsage(cfg, usageMetadata, deltaInputMs, deltaOutputMs, deltaImages) {
            session.active.set(false)
            setState(GeminiLiveState.ERROR, "Live quota exhausted. Please wait for quota reset or upgrade.")
            stop()
        }
    }

    private fun startCapture() {
        if (!session.setupComplete.get()) return
        if (!audio.hasRecordPermission()) {
            setState(GeminiLiveState.ERROR, "Microphone permission is required")
            return
        }
        if (!audio.captureEnabled.compareAndSet(false, true)) return
        audio.requestAudioFocus()
        if (!audio.microphoneAvailable) {
            // A spoken prompt owns the microphone; capture starts when it hands it back.
            audio.captureEnabled.set(false)
            Log.i(TAG, "Live capture waiting for the microphone")
            return
        }
        if (audio.usesVendorPcmInput()) {
            audio.startMentraInput(::offerMentraPcm)
            return
        }
        audio.startPhoneInput(keepRunning = { session.active.get() && session.setupComplete.get() }, onPcm = ::sendPcm)
    }

    private fun pauseCapture() {
        val wasCapturing = audio.captureEnabled.getAndSet(false)
        audio.stopMentraInput()
        speechDetector.reset()
        session.speechActive.set(false)
        audio.stopPhoneInput()

        flushDelayedAudio()

        // With automatic VAD enabled, Gemini asks clients to explicitly flush cached audio when
        // a stream is actually paused (background/audio-focus). This is not used as per-turn VAD.
        if (wasCapturing && session.active.get() && session.setupComplete.get() && socket != null) {
            sendAudioStreamEnd()
        }
    }

    private fun resumeCapture() {
        if (session.active.get() && session.setupComplete.get() && socket != null && session.state == GeminiLiveState.LISTENING) {
            startCapture()
        }
    }

    private fun stopCapture() = pauseCapture()

    private fun sendAudioStreamEnd() {
        socket?.send(GeminiLiveProtocol.buildAudioStreamEnd())
    }

    private fun sendPcm(bytes: ByteArray) {
        if (!session.setupComplete.get() || bytes.isEmpty()) return
        // The local detector schedules visual refreshes only. Gemini's server-side VAD must
        // receive the continuous stream so quiet speech and natural pauses are not discarded.
        speechDetector.offerPcm16Le(bytes)
        visualAudioHold.offer(bytes, isSpeaking = { session.speechActive.get() }, send = ::sendAudioPacket)
    }

    private fun flushDelayedAudio() {
        visualAudioHold.flush(isLive = { session.isLive }, send = ::sendAudioPacket)
    }

    private fun clearDelayedAudio() = visualAudioHold.clear()

    private fun sendAudioPacket(bytes: ByteArray) {
        if (sendRealtimeBlob(GeminiLiveProtocol.REALTIME_AUDIO_FIELD, GeminiLiveProtocol.INPUT_AUDIO_MIME_TYPE, bytes)) {
            session.addInputAudioBytes(bytes.size)
        }
    }

    private fun sendRealtimeBlob(field: String, mimeType: String, bytes: ByteArray): Boolean =
        socket?.send(GeminiLiveProtocol.buildRealtimeBlob(field, mimeType, bytes)) == true

    private fun playPcm(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        if (session.modelSpeaking.compareAndSet(false, true)) {
            listener.onModelOutputStarted()
            val wasUserSpeaking = session.speechActive.getAndSet(false)
            speechDetector.reset()
            if (wasUserSpeaking) listener.onUserSpeechActivity(false)
        }
        audio.play(bytes, onWritten = session::addOutputAudioBytes)
    }

    private fun finishModelPlayback() {
        if (!session.modelSpeaking.getAndSet(false)) return
        audio.setMentraOwnAudioPlaying(false)
        speechDetector.reset()
        session.speechActive.set(false)
    }

    private fun scheduleReconnect(detail: String? = null) {
        if (reconnectJob?.isActive == true) return
        pauseCapture()
        reconnectJob = scope.launch {
            session.reconnectAttempt++
            val delayMs = (1_000L shl (session.reconnectAttempt - 1).coerceAtMost(4)).coerceAtMost(15_000L)
            setState(GeminiLiveState.RECONNECTING, detail ?: "Reconnecting in ${delayMs / 1000}s")
            delay(delayMs)
            connectOrReconnect()
        }
    }

    /**
     * Ephemeral tokens are single-use for new sessions. Google permits reconnecting with the
     * same token only when the server-issued resumption handle is used before 10 minutes.
     */
    private fun scheduleSessionResumption(webSocket: WebSocket) {
        sessionResumptionJob?.cancel()
        sessionResumptionJob = scope.launch {
            delay(SESSION_RESUMPTION_RECONNECT_MS)
            if (!session.active.get() || socket !== webSocket) return@launch
            if (session.sessionResumptionHandle.isNullOrBlank()) {
                stop()
                setState(GeminiLiveState.ERROR, "Live session renewal was unavailable. Start a new session.")
                return@launch
            }
            webSocket.close(1000, "session_resumption")
        }
    }

    private fun hasInternet(): Boolean = connectivity.activeNetwork?.let { network ->
        connectivity.getNetworkCapabilities(network)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    } == true

    private fun notifyRouteChange() {
        if (session.active.get() && session.state == GeminiLiveState.LISTENING) {
            setState(GeminiLiveState.LISTENING, "Audio route changed")
        }
    }

    private fun setState(next: GeminiLiveState, detail: String) {
        session.state = next
        Log.i(TAG, "state=$next detail=$detail")
        listener.onStateChanged(next, detail)
    }

    private companion object {
        const val TAG = "GeminiLiveClient"
        const val SESSION_RESUMPTION_RECONNECT_MS = 9 * 60 * 1000L
        const val MAX_VISUAL_INPUTS_PER_SESSION = 540 // At most 1 FPS over the 9-minute resumption window.
    }
}
