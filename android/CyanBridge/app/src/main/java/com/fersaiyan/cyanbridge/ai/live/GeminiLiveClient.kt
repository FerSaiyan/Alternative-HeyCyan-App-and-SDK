package com.fersaiyan.cyanbridge.ai.live

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Base64
import android.util.Log
import androidx.core.content.ContextCompat
import com.fersaiyan.cyanbridge.agent.ProSubscriptionServerPrefs
import com.fersaiyan.cyanbridge.agent.ProSubscriptionAiPrefs
import com.fersaiyan.cyanbridge.ai.router.AiProviderPrefs
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject

/**
 * Direct Gemini Live WebSocket client. The relay only issues the short-lived token.
 *
 * This client is instantiated only by the explicit Gemini Live flow. Being a Pro user does not
 * activate Live Mode or change routing for any other model/provider.
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
    }

    @Deprecated("Use LiveTokenConfig")
    private data class TokenConfig(
        val token: String,
        val model: String,
        val websocketUrl: String,
        val expiresAtMs: Long,
        val reservationId: String,
    )

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val connectivity = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val active = AtomicBoolean(false)
    private val captureEnabled = AtomicBoolean(false)
    private val setupComplete = AtomicBoolean(false)
    private val speechActive = AtomicBoolean(false)
    private val modelSpeaking = AtomicBoolean(false)
    private val audioDelayLock = Any()
    private val delayedAudio = ArrayDeque<ByteArray>()
    private var audioDeferredForVisualContext = false
    private var visualAudioHoldStarted = false
    private var heldVisualSilenceChunks = 0
    private val speechDetector = GeminiLiveSpeechActivityDetector { speaking ->
        if (modelSpeaking.get()) {
            speechActive.set(false)
            Log.d(TAG, "Ignoring local speech energy during Gemini playback active=$speaking")
        } else {
            speechActive.set(speaking)
            Log.d(TAG, "Local speech energy active=$speaking")
            listener.onUserSpeechActivity(speaking)
        }
    }

    private var state = GeminiLiveState.IDLE
    private var tokenConfig: LiveTokenConfig? = null
    private val effectiveTokenProvider: GeminiLiveTokenProvider by lazy {
        tokenProvider ?: DefaultGeminiLiveTokenProvider(appContext, http)
    }
    // Stored for building the Live setup systemInstruction to match the token's bidiGenerateContentSetup.
    // The token's setup is built server-side via buildLiveSystemInstruction(language, imagePrompt, systemPrompt)
    // and the Live setup we send must include the same systemInstruction + contextWindowCompression
    // to avoid 1008 or hanging in CONNECTING (seen when setup omitted these).
    private var lastLanguage: String = "en"
    private var lastImagePrompt: String = ""
    private var lastSystemPrompt: String = ""
    private var socket: WebSocket? = null
    private var recorder: AudioRecord? = null
    private var recorderJob: Job? = null
    private var playback: AudioTrack? = null
    private var reconnectJob: Job? = null
    private var sessionResumptionJob: Job? = null
    private var sessionResumptionHandle: String? = null
    private var reconnectAttempt = 0
    private var audioFocusRequest: AudioFocusRequest? = null
    private var previousAudioMode: Int? = null
    private var previousCommunicationDevice: AudioDeviceInfo? = null
    private var liveAudioRouteConfigured = false
    private var inputAudioMs = 0L
    private var outputAudioMs = 0L
    private var visualInputCount = 0
    private var lastBilledInputMs = 0L
    private var lastBilledOutputMs = 0L
    private var lastBilledImages = 0
    private var lastUsageTotalTokens = 0

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            listener.onNetworkChanged(true)
            if (active.get() && socket == null) connectOrReconnect()
        }

        override fun onLost(network: Network) {
            if (!hasInternet()) {
                listener.onNetworkChanged(false)
                socket?.cancel()
            }
        }
    }

    private val audioFocusListener = AudioManager.OnAudioFocusChangeListener { focus ->
        when (focus) {
            AudioManager.AUDIOFOCUS_LOSS -> stop()
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> pauseCapture()
            AudioManager.AUDIOFOCUS_GAIN -> resumeCapture()
        }
    }

    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = notifyRouteChange()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = notifyRouteChange()
    }

    init {
        connectivity.registerDefaultNetworkCallback(networkCallback)
        audioManager.registerAudioDeviceCallback(audioDeviceCallback, null)
    }

    fun start(language: String, imagePrompt: String) {
        if (!active.compareAndSet(false, true)) return
        inputAudioMs = 0L
        outputAudioMs = 0L
        visualInputCount = 0
        lastBilledInputMs = 0L
        lastBilledOutputMs = 0L
        lastBilledImages = 0
        lastUsageTotalTokens = 0
        setupComplete.set(false)
        speechActive.set(false)
        modelSpeaking.set(false)
        clearDelayedAudio()
        speechDetector.reset()
        // Store for setup to match token's bidiGenerateContentSetup
        lastLanguage = language.ifBlank { "en" }
        lastImagePrompt = imagePrompt
        lastSystemPrompt = ProSubscriptionAiPrefs.getSystemPrompt(appContext)
        setState(GeminiLiveState.REQUESTING_TOKEN, "Requesting secure Live session")
        Log.i(TAG, "Live start requested language=$language prompt=${imagePrompt.take(80)}")
        scope.launch {
            runCatching { requestToken(language, imagePrompt) }
                .onSuccess {
                    tokenConfig = it
                    Log.i(TAG, "Live token ok model=${it.model} reservation=${it.reservationId} url=${it.websocketUrl.take(80)} expires=${it.expiresAtMs} auth=${it.authorizationHeader?.take(20)} apiKey=${if (it.apiKey.isNullOrBlank()) "none" else "present"}")
                    connectOrReconnect()
                }
                .onFailure {
                    active.set(false)
                    Log.e(TAG, "Live token failed", it)
                    setState(GeminiLiveState.ERROR, it.message ?: "Unable to start Gemini Live")
                }
        }
    }

    fun stop() {
        active.set(false)
        setupComplete.set(false)
        modelSpeaking.set(false)
        clearDelayedAudio()
        reconnectJob?.cancel()
        reconnectJob = null
        sessionResumptionJob?.cancel()
        sessionResumptionJob = null
        socket?.close(1000, "user_stopped")
        socket = null
        stopCapture()
        stopPlayback()
        restoreAudioRoute()
        abandonAudioFocus()
        sessionResumptionHandle = null
        tokenConfig?.reservationId?.takeUnless { it == "free-proxy" || it == "direct" }?.let { reservationId ->
            scope.launch {
                releaseRelayReservation(
                    reservationId = reservationId,
                    inputAudioMs = inputAudioMs,
                    outputAudioMs = outputAudioMs,
                    imageCount = visualInputCount,
                )
            }
        }
        tokenConfig = null
        setState(GeminiLiveState.STOPPED, "Stopped")
    }

    fun close() {
        stop()
        connectivity.unregisterNetworkCallback(networkCallback)
        audioManager.unregisterAudioDeviceCallback(audioDeviceCallback)
    }

    fun pauseForBackground() = pauseCapture()

    fun resumeAfterForeground() {
        if (active.get() && setupComplete.get() && state == GeminiLiveState.LISTENING) resumeCapture()
    }

    /** Accepts raw glasses PCM if a device SDK exposes it. */
    fun offerGlassesPcm(pcm: ShortArray, sampleRateHz: Int) {
        if (!active.get() || !setupComplete.get() || !captureEnabled.get()) return
        val normalized = PcmResampler.resampleMono16(pcm, sampleRateHz, INPUT_SAMPLE_RATE_HZ)
        val bytes = ByteArray(normalized.size * 2)
        normalized.forEachIndexed { index, sample ->
            bytes[index * 2] = (sample.toInt() and 0xff).toByte()
            bytes[index * 2 + 1] = ((sample.toInt() ushr 8) and 0xff).toByte()
        }
        sendPcm(bytes)
    }

    /** Backward-compatible name for explicit still images. */
    fun sendImage(jpegBytes: ByteArray) = sendVideoFrame(jpegBytes)

    fun sendTextTurn(text: String) {
        if (!active.get() || !setupComplete.get() || text.isBlank()) return
        val content = JSONObject()
            .put(
                "turns",
                JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put("parts", JSONArray().put(JSONObject().put("text", text.take(2_000)))),
                ),
            )
            .put("turnComplete", true)
        socket?.send(JSONObject().put("clientContent", content).toString())
    }

    /** Sends one JPEG frame through the current realtimeInput.video field. */
    fun sendVideoFrame(jpegBytes: ByteArray) {
        if (!active.get() || !setupComplete.get() || jpegBytes.isEmpty()) return
        if (visualInputCount >= MAX_VISUAL_INPUTS_PER_SESSION) {
            setState(GeminiLiveState.LISTENING, "Live visual-input safety limit reached")
            return
        }
        if (sendRealtimeBlob("video", "image/jpeg", jpegBytes)) visualInputCount++
    }

    /** Prevents trailing silence from finalizing a turn before its fresh image is sent. */
    fun deferAudioForVisualContext() {
        synchronized(audioDelayLock) {
            if (!active.get() || !setupComplete.get()) return
            if (!audioDeferredForVisualContext) {
                audioDeferredForVisualContext = true
                visualAudioHoldStarted = false
                heldVisualSilenceChunks = 0
                Log.i(TAG, "Deferring Live turn completion until fresh visual context is ready")
            }
        }
    }

    /** Sends the image first, then releases the held turn tail in socket order. */
    fun releaseAudioAfterVisualContext() {
        synchronized(audioDelayLock) {
            if (!audioDeferredForVisualContext) return
            audioDeferredForVisualContext = false
            visualAudioHoldStarted = false
            heldVisualSilenceChunks = 0
            if (!active.get() || !setupComplete.get()) {
                delayedAudio.clear()
                return
            }
            val chunks = delayedAudio.size
            while (delayedAudio.isNotEmpty()) sendAudioPacket(delayedAudio.removeFirst())
            Log.i(TAG, "Released deferred Live audio chunks=$chunks after visual context")
        }
    }

    fun cancelDeferredVisualContext() = clearDelayedAudio()

    private fun requestToken(language: String, imagePrompt: String): LiveTokenConfig =
        kotlinx.coroutines.runBlocking { effectiveTokenProvider.requestToken(language, imagePrompt) }

    private fun releaseRelayReservation(
        reservationId: String,
        inputAudioMs: Long,
        outputAudioMs: Long,
        imageCount: Int,
    ) {
        val authToken = ProSubscriptionServerPrefs.getApiToken(appContext).trim()
        val base = AiProviderPrefs.getRelayBaseUrl(appContext).trim().trimEnd('/')
        if (authToken.isBlank() || !base.startsWith("https://")) return
        val request = Request.Builder()
            .url("$base/api/pro/live/end")
            .header("Authorization", "Bearer $authToken")
            .post(
                JSONObject()
                    .put("reservation_id", reservationId)
                    .put("input_audio_ms", inputAudioMs)
                    .put("output_audio_ms", outputAudioMs)
                    .put("image_count", imageCount)
                    .toString()
                    .toRequestBody("application/json".toMediaType()),
            )
            .build()
        runCatching { http.newCall(request).execute().use { } }
            .onFailure { Log.w(TAG, "Could not release Gemini Live reservation", it) }
    }

    private fun connectOrReconnect() {
        if (!active.get()) return
        val config = tokenConfig ?: return
        if (System.currentTimeMillis() >= config.expiresAtMs) {
            active.set(false)
            setState(GeminiLiveState.ERROR, "Live session expired. Start a new session.")
            return
        }
        if (!hasInternet()) {
            setState(GeminiLiveState.RECONNECTING, "Waiting for network")
            return
        }

        setupComplete.set(false)
        clearDelayedAudio()
        socket?.cancel()
        setState(
            if (reconnectAttempt == 0) GeminiLiveState.CONNECTING else GeminiLiveState.RECONNECTING,
            "Connecting to Google",
        )
        val builder = Request.Builder().url(config.websocketUrl)
        config.authorizationHeader?.takeIf { it.isNotBlank() }?.let {
            builder.header("Authorization", it)
        } ?: config.token.takeIf { it.isNotBlank() }?.let {
            builder.header("Authorization", "Token $it")
        }
        // Free-tier Live requires x-goog-api-key alongside the ephemeral token (or alone).
        // DefaultGeminiLiveTokenProvider populates apiKey from debug prefs; Direct provider always does.
        config.apiKey?.takeIf { it.isNotBlank() }?.let { builder.header("x-goog-api-key", it) }
        // Send app language so free queue 429 can be localized (too many free users)
        runCatching {
            val langTag = com.fersaiyan.cyanbridge.ui.localization.AppLanguagePreferences.selected(appContext).languageTag
                .ifBlank { java.util.Locale.getDefault().toLanguageTag() }
            if (langTag.isNotBlank()) builder.header("Accept-Language", langTag)
        }
        val request = builder.build()
        socket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (!active.get()) {
                    webSocket.close(1000, "stopped")
                    return
                }
                socket = webSocket
                reconnectAttempt = 0
                sendSetup(webSocket, config)
                scheduleSessionResumption(webSocket)
                setState(GeminiLiveState.CONNECTING, "Connected; waiting for Gemini setup")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleServerMessage(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.w(TAG, "Gemini Live socket closing code=$code reason=$reason")
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                val wasSetupComplete = setupComplete.get()
                Log.w(TAG, "Gemini Live socket closed code=$code reason=$reason active=${active.get()} setupComplete=$wasSetupComplete")
                if (socket === webSocket) socket = null
                setupComplete.set(false)
                if (!wasSetupComplete && active.get() && config.reservationId != "free-proxy" && config.reservationId != "direct") {
                    active.set(false)
                    scope.launch {
                        releaseRelayReservation(
                            reservationId = config.reservationId,
                            inputAudioMs = inputAudioMs,
                            outputAudioMs = outputAudioMs,
                            imageCount = visualInputCount,
                        )
                    }
                    tokenConfig = null
                    setState(GeminiLiveState.ERROR, "Gemini Live connection failed ($code). Please try again.")
                    return
                }
                if (active.get()) scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (socket === webSocket) socket = null
                setupComplete.set(false)
                Log.w(TAG, "Gemini Live socket failed code=${response?.code} msg=${response?.message} err=${t.message}", t)
                // Free queue: server returns 429 live_free_queued with localized message
                if (response?.code == 429) {
                    val raw = try { response.body?.string().orEmpty() } catch (_: Exception) { "" }
                    val isQueued = raw.contains("live_free_queued") || raw.contains("live_rate_limited") || t.message?.contains("429") == true
                    if (isQueued || raw.contains("live_free_queued")) {
                        val queuedDetail = runCatching {
                            val json = JSONObject(raw.ifBlank { "{}" })
                            json.optString("message", "").takeIf { it.isNotBlank() }
                        }.getOrNull() ?: localizedFreeQueueMessage()
                        active.set(false)
                        setState(GeminiLiveState.ERROR, queuedDetail)
                        return
                    }
                    // Also treat any 429 during free as queue for UX
                    if (config.reservationId == "free-proxy") {
                        active.set(false)
                        setState(GeminiLiveState.ERROR, localizedFreeQueueMessage())
                        return
                    }
                }
                if (!setupComplete.get() && active.get() && config.reservationId != "free-proxy" && config.reservationId != "direct") {
                    active.set(false)
                    scope.launch {
                        releaseRelayReservation(
                            reservationId = config.reservationId,
                            inputAudioMs = inputAudioMs,
                            outputAudioMs = outputAudioMs,
                            imageCount = visualInputCount,
                        )
                    }
                    tokenConfig = null
                    setState(GeminiLiveState.ERROR, "Gemini Live connection failed. Please try again.")
                    return
                }
                if (active.get()) scheduleReconnect()
            }

    private fun localizedFreeQueueMessage(): String {
        val tag = runCatching {
            com.fersaiyan.cyanbridge.ui.localization.AppLanguagePreferences.selected(appContext).languageTag
                .ifBlank { java.util.Locale.getDefault().toLanguageTag() }
        }.getOrDefault("en").lowercase()
        return when {
            tag.startsWith("pt") -> "Muitos usuários gratuitos no modo Live agora. Você está na fila — tente novamente em instantes."
            tag.startsWith("es") -> "Demasiados usuarios gratuitos en el modo Live ahora mismo. Estás en cola — inténtalo de nuevo en un momento."
            tag.startsWith("de") -> "Zu viele kostenlose Nutzer im Live-Modus. Du bist in der Warteschlange — bitte versuche es gleich erneut."
            tag.startsWith("fr") -> "Trop d'utilisateurs gratuits en mode Live. Vous êtes en file d'attente — réessayez dans un instant."
            tag.startsWith("it") -> "Troppi utenti gratuiti in modalità Live. Sei in coda — riprova tra un momento."
            tag.startsWith("zh") -> "当前 Live 模式的免费用户过多，您已进入排队，请稍后重试。"
            tag.startsWith("ko") -> "현재 Live 모드에 무료 사용자가 많아 대기열에 있습니다. 잠시 후 다시 시도해 주세요."
            tag.startsWith("ru") -> "Слишком много бесплатных пользователей в режиме Live. Вы в очереди — попробуйте ещё раз через мгновение."
            else -> "Too many free users on Live mode right now, you are in queue. Please try again in a moment."
        }
    }
        })
    }

    private fun sendSetup(webSocket: WebSocket, config: LiveTokenConfig) {
        // If the token was minted with an exact bidiGenerateContentSetup (Pro Live), echo it exactly
        // to avoid hanging in CONNECTING (server waits for matching setup). Free proxy does
        // upstream.send({setup: provisioningRequest.bidiGenerateContentSetup}) — we do the same for Pro.
        val serverSetup = config.setupJson?.let { raw ->
            runCatching { JSONObject(raw) }.getOrNull()
        }
        val setup: JSONObject = if (serverSetup != null) {
            // Inject sessionResumption handle if present, keep everything else from server
            val resumption = JSONObject().apply {
                sessionResumptionHandle?.takeIf { it.isNotBlank() }?.let { put("handle", it) }
            }
            // Ensure sessionResumption is updated, keep other fields from server
            serverSetup.put("sessionResumption", resumption)
            // Ensure model matches token (in case server setup had different)
            serverSetup.put("model", config.model)
            serverSetup
        } else {
            val resumption = JSONObject().apply {
                sessionResumptionHandle?.takeIf { it.isNotBlank() }?.let { put("handle", it) }
            }
            val automaticActivityDetection = JSONObject()
                .put("disabled", false)
                .put("startOfSpeechSensitivity", "START_SENSITIVITY_HIGH")
                .put("endOfSpeechSensitivity", "END_SENSITIVITY_LOW")
                .put("prefixPaddingMs", 40)
                .put("silenceDurationMs", 500)
            val realtimeInputConfig = JSONObject()
                .put("automaticActivityDetection", automaticActivityDetection)
                .put("activityHandling", "START_OF_ACTIVITY_INTERRUPTS")
                .put("turnCoverage", "TURN_INCLUDES_AUDIO_ACTIVITY_AND_ALL_VIDEO")
            // Build systemInstruction to match token's bidiGenerateContentSetup (see lib/gemini-live.ts buildLiveSystemInstruction)
            // The token's setup is built server-side with the same language/imagePrompt/systemPrompt, and the Live setup we send
            // must include the same systemInstruction + contextWindowCompression to avoid hanging in CONNECTING
            // (seen when setup omitted these and server waited for matching setup).
            val cleanPrompt = lastImagePrompt.trim().replace(Regex("\\s+"), " ").take(400)
                .ifBlank { "Describe what you can see and answer the user's question." }
            val systemPrompt = lastSystemPrompt.ifBlank { ProSubscriptionAiPrefs.getSystemPrompt(appContext) }
            // Replicate resolveAssistantSystemPrompt + buildLiveSystemInstruction
            val assistantPrompt = systemPrompt.trim().take(4000).ifBlank {
                "You are CyanBridge's assistant for smart glasses. Answer the user's request directly. Give the most useful answer first in one clear sentence, then stop when the request is fully answered. For simple spoken requests, usually use 1-3 short sentences; for complex requests, include the important explanation, steps, caveats, and safety information needed. Use the shortest complete answer. Avoid filler, long preambles, repetition, and unnecessary formatting unless the user asks for them. Use the latest glasses image as visual context when the user refers to what they see."
            }
            val systemInstructionText = listOf(
                assistantPrompt,
                "You are Gemini Live in CyanBridge smart glasses.",
                "You support 97 languages. Respond in the language the user is currently speaking, defaulting to ${lastLanguage} only when unclear. Switch immediately when the user asks to speak another language.",
                "Keep spoken answers concise, helpful, and safe for a hands-free conversation.",
                "Default image question when the user gives no specific question and you have a fresh glasses image: $cleanPrompt"
            ).joinToString(" ")
            val systemInstruction = JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemInstructionText)))
            val contextWindowCompression = JSONObject()
                .put("triggerTokens", 25000)
                .put("slidingWindow", JSONObject().put("targetTokens", 8000))
            JSONObject()
                .put("model", config.model)
                .put("generationConfig", JSONObject().put("responseModalities", JSONArray().put("AUDIO")))
                .put("realtimeInputConfig", realtimeInputConfig)
                .put("inputAudioTranscription", JSONObject())
                .put("outputAudioTranscription", JSONObject())
                .put("sessionResumption", resumption)
                .put("contextWindowCompression", contextWindowCompression)
                .put("systemInstruction", systemInstruction)
        }
        val setupJson = JSONObject().put("setup", setup).toString()
        Log.i(TAG, "Sending Live setup model=${config.model} handle=${sessionResumptionHandle?.take(12)} json=${setupJson.take(2000)}")
        check(webSocket.send(setupJson)) {
            "Live setup could not be sent"
        }
    }

    private fun handleServerMessage(raw: String) {
        // Verbose log for diagnosing why Live never reaches setupComplete (user reported no listening cue / notification)
        Log.d(TAG, "Live server message raw=${raw.take(2000)}")
        val message = runCatching { JSONObject(raw) }.getOrElse {
            Log.w(TAG, "Ignoring malformed Gemini Live message raw=${raw.take(500)}")
            return
        }

        if (message.has("setupComplete") && setupComplete.compareAndSet(false, true)) {
            startCapture()
            setState(GeminiLiveState.LISTENING, "Gemini Live is listening")
            listener.onSetupComplete()
        }

        message.optJSONObject("sessionResumptionUpdate")
            ?.optString("newHandle")
            ?.takeIf { it.isNotBlank() }
            ?.let { sessionResumptionHandle = it }

        message.optJSONObject("goAway")?.let { goAway ->
            val timeLeft = goAway.optString("timeLeft")
            Log.i(TAG, "Gemini Live goAway received; timeLeft=$timeLeft")
        }

        // Google-recommended metering: usageMetadata is the billed tokens for this turn
        // (includes re-billed sliding window). Forward it for server-side quota debit.
        message.optJSONObject("usageMetadata")?.let { usage ->
            handleUsageMetadata(usage)
        }

        val serverContent = message.optJSONObject("serverContent")
        if (serverContent != null) {
            serverContent.optJSONObject("inputTranscription")
                ?.optString("text")
                ?.takeIf { it.isNotBlank() }
                ?.let { listener.onTranscription(true, it) }
            serverContent.optJSONObject("outputTranscription")
                ?.optString("text")
                ?.takeIf { it.isNotBlank() }
                ?.let { listener.onTranscription(false, it) }

            if (serverContent.optBoolean("interrupted", false)) {
                playback?.let { track ->
                    runCatching { track.pause() }
                    runCatching { track.flush() }
                }
                finishModelPlayback()
                listener.onInterrupted()
                // Interrupted still ends a turn — bill the partial turn via usageMetadata if present,
                // otherwise via audio ms delta.
                reportTurnUsageIfNeeded()
            }

            val parts = serverContent.optJSONObject("modelTurn")?.optJSONArray("parts")
            if (parts != null) {
                for (index in 0 until parts.length()) {
                    val encoded = parts.optJSONObject(index)
                        ?.optJSONObject("inlineData")
                        ?.optString("data")
                        .orEmpty()
                    if (encoded.isNotBlank()) {
                        runCatching { Base64.decode(encoded, Base64.DEFAULT) }
                            .getOrNull()
                            ?.let(::playPcm)
                    }
                }
            }

            if (serverContent.optBoolean("turnComplete", false) && active.get()) {
                finishModelPlayback()
                setState(GeminiLiveState.LISTENING, "Gemini Live is listening")
                reportTurnUsageIfNeeded()
            }
        }
        // If message had only usageMetadata and no serverContent, billing already happened via handleUsageMetadata
    }

    private fun handleUsageMetadata(usage: JSONObject) {
        val total = usage.optInt("totalTokenCount", 0)
        if (total <= 0) {
            // Fallback to prompt+response if total missing
            val prompt = usage.optInt("promptTokenCount", 0)
            val response = usage.optInt("responseTokenCount", 0)
            if (prompt <= 0 && response <= 0) return
        }
        // Avoid double-billing same total within a turn
        val totalForDedup = usage.optInt("totalTokenCount", usage.optInt("promptTokenCount", 0) + usage.optInt("responseTokenCount", 0))
        if (totalForDedup == lastUsageTotalTokens && totalForDedup != 0) return
        lastUsageTotalTokens = totalForDedup
        reportLiveUsage(usageMetadata = usage, deltaInputMs = 0, deltaOutputMs = 0, deltaImages = 0)
    }

    private fun reportTurnUsageIfNeeded() {
        val deltaInput = inputAudioMs - lastBilledInputMs
        val deltaOutput = outputAudioMs - lastBilledOutputMs
        val deltaImages = visualInputCount - lastBilledImages
        if (deltaInput <= 0 && deltaOutput <= 0 && deltaImages <= 0) return
        // If we just billed via usageMetadata for this turn, lastUsageTotalTokens will have been updated
        // and delta will be small; we still report delta as fallback but server will deduplicate via max logic.
        reportLiveUsage(usageMetadata = null, deltaInputMs = deltaInput, deltaOutputMs = deltaOutput, deltaImages = deltaImages)
        lastBilledInputMs = inputAudioMs
        lastBilledOutputMs = outputAudioMs
        lastBilledImages = visualInputCount
    }

    private fun reportLiveUsage(usageMetadata: JSONObject?, deltaInputMs: Long, deltaOutputMs: Long, deltaImages: Int) {
        val cfg = tokenConfig ?: return
        if (cfg.reservationId == "free-proxy" || cfg.reservationId == "direct") return
        val authToken = ProSubscriptionServerPrefs.getApiToken(appContext).trim()
        val base = AiProviderPrefs.getRelayBaseUrl(appContext).trim().trimEnd('/')
        if (authToken.isBlank() || !base.startsWith("https://")) return
        scope.launch {
            try {
                val body = JSONObject().apply {
                    put("reservation_id", cfg.reservationId)
                    if (usageMetadata != null) put("usage_metadata", usageMetadata) else {
                        put("delta_input_audio_ms", deltaInputMs)
                        put("delta_output_audio_ms", deltaOutputMs)
                        put("delta_image_count", deltaImages)
                        put("input_audio_ms", deltaInputMs)
                        put("output_audio_ms", deltaOutputMs)
                        put("image_count", deltaImages)
                    }
                }.toString().toRequestBody("application/json".toMediaType())
                val req = Request.Builder()
                    .url("$base/api/pro/live/usage")
                    .header("Authorization", "Bearer $authToken")
                    .post(body)
                    .build()
                http.newCall(req).execute().use { resp ->
                    val raw = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        val json = runCatching { JSONObject(raw) }.getOrNull()
                        val err = json?.optString("error") ?: raw
                        if (resp.code == 402 && err.contains("live_quota_exhausted")) {
                            Log.w(TAG, "Live quota exhausted mid-session, stopping")
                            active.set(false)
                            setState(GeminiLiveState.ERROR, "Live quota exhausted. Please wait for quota reset or upgrade.")
                            stop()
                        } else {
                            Log.w(TAG, "Live usage report failed code=${resp.code} err=$err")
                        }
                    } else {
                        Log.d(TAG, "Live usage reported ok deltaInput=$deltaInputMs deltaOutput=$deltaOutputMs images=$deltaImages usage=$usageMetadata")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Live usage report exception", e)
            }
        }
    }

    private fun startCapture() {
        if (!setupComplete.get()) return
        if (
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            setState(GeminiLiveState.ERROR, "Microphone permission is required")
            return
        }
        if (!captureEnabled.compareAndSet(false, true)) return
        requestAudioFocus()
        configureAudioRoute()
        startPlayback()
        val minBuffer = AudioRecord.getMinBufferSize(
            INPUT_SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) {
            captureEnabled.set(false)
            setState(GeminiLiveState.ERROR, "Microphone is unavailable")
            return
        }
        val newRecorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            INPUT_SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuffer, INPUT_CHUNK_BYTES * 4),
        )
        if (newRecorder.state != AudioRecord.STATE_INITIALIZED) {
            captureEnabled.set(false)
            newRecorder.release()
            setState(GeminiLiveState.ERROR, "Microphone could not be initialized")
            return
        }
        recorder = newRecorder
        recorderJob = scope.launch {
            val buffer = ByteArray(INPUT_CHUNK_BYTES)
            newRecorder.startRecording()
            while (active.get() && setupComplete.get() && captureEnabled.get()) {
                val count = newRecorder.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                if (count > 0) {
                    val packet = if (count == buffer.size) buffer else buffer.copyOf(count)
                    sendPcm(packet)
                }
            }
        }
    }

    private fun pauseCapture() {
        val wasCapturing = captureEnabled.getAndSet(false)
        speechDetector.reset()
        speechActive.set(false)
        recorderJob?.cancel()
        recorderJob = null
        recorder?.let {
            runCatching { it.stop() }
            it.release()
        }
        recorder = null

        flushDelayedAudio()

        // With automatic VAD enabled, Gemini asks clients to explicitly flush cached audio when
        // a stream is actually paused (background/audio-focus). This is not used as per-turn VAD.
        if (wasCapturing && active.get() && setupComplete.get() && socket != null) {
            sendAudioStreamEnd()
        }
    }

    private fun resumeCapture() {
        if (active.get() && setupComplete.get() && socket != null && state == GeminiLiveState.LISTENING) {
            startCapture()
        }
    }

    private fun stopCapture() = pauseCapture()

    private fun sendAudioStreamEnd() {
        val realtimeInput = JSONObject().put("audioStreamEnd", true)
        socket?.send(JSONObject().put("realtimeInput", realtimeInput).toString())
    }

    private fun sendPcm(bytes: ByteArray) {
        if (!setupComplete.get() || bytes.isEmpty()) return
        // The local detector schedules visual refreshes only. Gemini's server-side VAD must
        // receive the continuous stream so quiet speech and natural pauses are not discarded.
        speechDetector.offerPcm16Le(bytes)
        synchronized(audioDelayLock) {
            val speaking = speechActive.get()
            if (audioDeferredForVisualContext && visualAudioHoldStarted && speaking) {
                repeat(heldVisualSilenceChunks.coerceAtMost(delayedAudio.size)) {
                    delayedAudio.removeLast()
                }
                heldVisualSilenceChunks = 0
                visualAudioHoldStarted = false
            }

            if (!audioDeferredForVisualContext || !visualAudioHoldStarted || speaking) {
                delayedAudio.addLast(bytes.copyOf())
            }
            if (audioDeferredForVisualContext && !speaking && !visualAudioHoldStarted) {
                visualAudioHoldStarted = true
                // The detector changes state on the fifteenth silent chunk. Those chunks are
                // already inside the delayed tail, so retain exactly that tail and no more.
                heldVisualSilenceChunks = VISUAL_TRAILING_SILENCE_CHUNKS.coerceAtMost(delayedAudio.size)
            }
            val canStream = !audioDeferredForVisualContext ||
                (!visualAudioHoldStarted && speaking)
            if (canStream && delayedAudio.size > AUDIO_DELAY_CHUNKS) {
                sendAudioPacket(delayedAudio.removeFirst())
            }
        }
    }

    private fun flushDelayedAudio() {
        synchronized(audioDelayLock) {
            audioDeferredForVisualContext = false
            visualAudioHoldStarted = false
            heldVisualSilenceChunks = 0
            if (active.get() && setupComplete.get()) {
                while (delayedAudio.isNotEmpty()) sendAudioPacket(delayedAudio.removeFirst())
            } else {
                delayedAudio.clear()
            }
        }
    }

    private fun clearDelayedAudio() {
        synchronized(audioDelayLock) {
            audioDeferredForVisualContext = false
            visualAudioHoldStarted = false
            heldVisualSilenceChunks = 0
            delayedAudio.clear()
        }
    }

    private fun sendAudioPacket(bytes: ByteArray) {
        if (sendRealtimeBlob("audio", "audio/pcm;rate=$INPUT_SAMPLE_RATE_HZ", bytes)) {
            inputAudioMs += bytes.size.toLong() * 1_000L / (INPUT_SAMPLE_RATE_HZ * 2L)
        }
    }

    private fun sendRealtimeBlob(field: String, mimeType: String, bytes: ByteArray): Boolean {
        val realtimeInput = JSONObject().put(
            field,
            JSONObject()
                .put("mimeType", mimeType)
                .put("data", Base64.encodeToString(bytes, Base64.NO_WRAP)),
        )
        return socket?.send(JSONObject().put("realtimeInput", realtimeInput).toString()) == true
    }

    private fun startPlayback() {
        if (playback != null) return
        val minBuffer = AudioTrack.getMinBufferSize(
            OUTPUT_SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(OUTPUT_SAMPLE_RATE_HZ / 5)
        playback = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(OUTPUT_SAMPLE_RATE_HZ)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
            )
            .setBufferSizeInBytes(minBuffer * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
            .also { it.play() }
    }

    private fun playPcm(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        if (modelSpeaking.compareAndSet(false, true)) {
            val wasUserSpeaking = speechActive.getAndSet(false)
            speechDetector.reset()
            if (wasUserSpeaking) listener.onUserSpeechActivity(false)
        }
        if (playback == null) startPlayback()
        val track = playback ?: return
        if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
            runCatching { track.play() }
        }
        var offset = 0
        while (offset < bytes.size) {
            val written = track.write(bytes, offset, bytes.size - offset, AudioTrack.WRITE_BLOCKING)
            if (written <= 0) {
                Log.w(TAG, "Live audio playback write failed result=$written remaining=${bytes.size - offset}")
                break
            }
            offset += written
            outputAudioMs += written.toLong() * 1_000L / (OUTPUT_SAMPLE_RATE_HZ * 2L)
        }
    }

    private fun finishModelPlayback() {
        if (!modelSpeaking.getAndSet(false)) return
        speechDetector.reset()
        speechActive.set(false)
    }

    private fun stopPlayback() {
        playback?.let {
            runCatching { it.pause() }
            runCatching { it.flush() }
            it.release()
        }
        playback = null
    }

    private fun configureAudioRoute() {
        if (liveAudioRouteConfigured) return
        previousAudioMode = audioManager.mode
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            previousCommunicationDevice = audioManager.communicationDevice
        }
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        val bluetooth = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.availableCommunicationDevices.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            }
        } else {
            null
        }
        val selected = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && bluetooth != null) {
            audioManager.setCommunicationDevice(bluetooth)
        } else {
            false
        }
        @Suppress("DEPRECATION")
        runCatching { audioManager.startBluetoothSco() }
        liveAudioRouteConfigured = true
        Log.i(TAG, "Live audio route selectedBluetooth=$selected device=${bluetooth?.productName}")
    }

    private fun restoreAudioRoute() {
        if (!liveAudioRouteConfigured) return
        @Suppress("DEPRECATION")
        runCatching { audioManager.stopBluetoothSco() }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val previous = previousCommunicationDevice
            if (previous != null) runCatching { audioManager.setCommunicationDevice(previous) }
            else runCatching { audioManager.clearCommunicationDevice() }
        }
        previousAudioMode?.let { audioManager.mode = it }
        previousAudioMode = null
        previousCommunicationDevice = null
        liveAudioRouteConfigured = false
    }

    private fun requestAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .build(),
                )
                .setOnAudioFocusChangeListener(audioFocusListener)
                .build()
            audioManager.requestAudioFocus(audioFocusRequest!!)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                audioFocusListener,
                AudioManager.STREAM_VOICE_CALL,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE,
            )
        }
    }

    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let(audioManager::abandonAudioFocusRequest)
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(audioFocusListener)
        }
        audioFocusRequest = null
    }

    private fun scheduleReconnect() {
        if (reconnectJob?.isActive == true) return
        pauseCapture()
        reconnectJob = scope.launch {
            reconnectAttempt++
            val delayMs = (1_000L shl (reconnectAttempt - 1).coerceAtMost(4)).coerceAtMost(15_000L)
            setState(GeminiLiveState.RECONNECTING, "Reconnecting in ${delayMs / 1000}s")
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
            if (!active.get() || socket !== webSocket) return@launch
            if (sessionResumptionHandle.isNullOrBlank()) {
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
        if (active.get() && state == GeminiLiveState.LISTENING) {
            setState(GeminiLiveState.LISTENING, "Audio route changed")
        }
    }

    private fun setState(next: GeminiLiveState, detail: String) {
        state = next
        Log.i(TAG, "state=$next detail=$detail")
        listener.onStateChanged(next, detail)
    }

    private companion object {
        const val TAG = "GeminiLiveClient"
        const val INPUT_SAMPLE_RATE_HZ = 16_000
        const val OUTPUT_SAMPLE_RATE_HZ = 24_000
        const val INPUT_CHUNK_BYTES = 1_280 // 40 ms PCM16 mono at 16 kHz; halves WS message rate vs 20 ms.
        // Keep more than the detector's 600 ms trailing-silence window. During a still transfer,
        // speech streams normally while this tail is held back from Gemini's 500 ms server VAD.
        const val AUDIO_DELAY_CHUNKS = 16
        const val VISUAL_TRAILING_SILENCE_CHUNKS = 15
        const val SESSION_RESUMPTION_RECONNECT_MS = 9 * 60 * 1000L
        const val MAX_VISUAL_INPUTS_PER_SESSION = 540 // At most 1 FPS over the 9-minute resumption window.
    }
}
