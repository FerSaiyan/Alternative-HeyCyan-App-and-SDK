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
        setupComplete.set(false)
        speechActive.set(false)
        modelSpeaking.set(false)
        clearDelayedAudio()
        speechDetector.reset()
        setState(GeminiLiveState.REQUESTING_TOKEN, "Requesting secure Live session")
        scope.launch {
            runCatching { requestToken(language, imagePrompt) }
                .onSuccess {
                    tokenConfig = it
                    connectOrReconnect()
                }
                .onFailure {
                    active.set(false)
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
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (socket === webSocket) socket = null
                setupComplete.set(false)
                if (active.get()) scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (socket === webSocket) socket = null
                setupComplete.set(false)
                Log.w(TAG, "Gemini Live socket failed", t)
                if (active.get()) scheduleReconnect()
            }
        })
    }

    private fun sendSetup(webSocket: WebSocket, config: LiveTokenConfig) {
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
        val setup = JSONObject()
            .put("model", config.model)
            .put("generationConfig", JSONObject().put("responseModalities", JSONArray().put("AUDIO")))
            .put("realtimeInputConfig", realtimeInputConfig)
            .put("inputAudioTranscription", JSONObject())
            .put("outputAudioTranscription", JSONObject())
            .put("sessionResumption", resumption)
        check(webSocket.send(JSONObject().put("setup", setup).toString())) {
            "Live setup could not be sent"
        }
    }

    private fun handleServerMessage(raw: String) {
        val message = runCatching { JSONObject(raw) }.getOrElse {
            Log.w(TAG, "Ignoring malformed Gemini Live message")
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

        val serverContent = message.optJSONObject("serverContent") ?: return
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
