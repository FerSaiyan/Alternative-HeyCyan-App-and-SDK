package com.fersaiyan.cyanbridge.devices.eyevue

import android.content.Context
import android.util.Log
import com.fersaiyan.cyanbridge.glasses.GlassesPhotoCapture
import com.fersaiyan.cyanbridge.glasses.NativeGlassesPhotoCapture
import com.fersaiyan.cyanbridge.shared.devices.DeviceClass
import com.fersaiyan.cyanbridge.shared.devices.eyevue.EyevueFrame
import com.fersaiyan.cyanbridge.shared.devices.eyevue.EyevueProtocol
import com.fersaiyan.cyanbridge.shared.devices.eyevue.RcspOtaSender
import com.fersaiyan.cyanbridge.shared.devices.eyevue.RcspTargetInfo
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.fersaiyan.cyanbridge.ai.live.GeminiLiveImageButtonRouter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException

data class EyevueState(
    val connectionLabel: String = "Disconnected",
    val protocolState: String = EyevueGattState.DISCONNECTED.name,
    val deviceAddress: String? = null,
    val deviceName: String? = null,
    val batteryPercent: Int? = null,
    val isCharging: Boolean = false,
    val storageCount: Int? = null,
    val wifiSsid: String? = null,
    val customer: String? = null,
    val project: String? = null,
    val isVideoRecording: Boolean = false,
    val isAudioRecording: Boolean = false,
    val aiWakeWordEnabled: Boolean? = null,
    val localOfflineSpeechEnabled: Boolean? = null,
    val lastError: String? = null,
)

/** Progress of an EyeVue RCSP firmware install. */
sealed interface RcspInstallProgress {
    /** [cancelable]: stopping now keeps the installed firmware (a dual-bank transfer). */
    data class Sending(val percent: Int, val cancelable: Boolean) : RcspInstallProgress

    /** The glasses dropped the link to restart as part of the update; reconnecting. */
    data object Restarting : RcspInstallProgress
}

/** Owns Eyevue's native BLE connection and exposes vendor commands to the app. */
class EyevueManager private constructor(context: Context) {
    companion object {
        private const val TAG = "EyevueManager"
        private const val RCSP_IDLE_TIMEOUT_MS = 20_000L
        private const val RCSP_INFO_TIMEOUT_MS = 6_000L
        private const val RCSP_RESTART_WAIT_MS = 4_000L
        private const val RCSP_RECONNECT_ATTEMPTS = 20
        private const val RCSP_RECONNECT_DELAY_MS = 3_000L
        private const val RCSP_CANCEL_POLL_MS = 500L

        @Volatile
        private var instance: EyevueManager? = null

        fun getInstance(context: Context): EyevueManager =
            instance ?: synchronized(this) {
                instance ?: EyevueManager(context.applicationContext).also { instance = it }
            }

        /** The manager if EyeVue was used in this process (so switching devices does not create one). */
        fun instanceOrNull(): EyevueManager? = instance
    }

    private val client = EyevueGattClient(context.applicationContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(EyevueState())
    private val _wakeWordEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private var connectJob: Job? = null
    private val photoCapture = NativeGlassesPhotoCapture(
        device = DeviceClass.EYEVUE,
        connected = client::isConnected,
        trigger = {
            // AA15 AI stream; await the actual write rather than launching a detached command.
            if (!sendNow(EyevueProtocol.buildAiPhotoPacket(), "take AI photo")) {
                throw IOException("Could not request the Eyevue photo")
            }
        },
        resetStream = client::resetPhotoStream,
        manualScope = scope,
    )

    val state: StateFlow<EyevueState> = _state.asStateFlow()
    val wakeWordEvents: SharedFlow<Unit> = _wakeWordEvents.asSharedFlow()
    private val rcspMutex = Mutex()

    /** The RCSP target info read last on this connection (firmware version). */
    @Volatile var rcspInfo: RcspTargetInfo? = null
        private set

    init {
        client.photoPacketListener = { packet ->
            photoCapture.onPacket()
            if (packet.size > 4 && (packet[4].toInt() and 255) == EyevueProtocol.CMD_RECEIVE_PHOTO_DATA_START) {
                GeminiLiveImageButtonRouter.nativePhotoReceiver(DeviceClass.EYEVUE)?.let { receiver ->
                    photoCapture.onManualStreamStart(12_000, receiver)
                }
            }
        }
        client.photoListener = { image -> photoCapture.onPhoto(image) }
        scope.launch {
            client.state.collect { gattState ->
                if (gattState == EyevueGattState.DISCONNECTED || gattState == EyevueGattState.ERROR) {
                    GlassesPhotoCapture.invalidate(DeviceClass.EYEVUE)
                }
                val current = _state.value
                val label = when (gattState) {
                    EyevueGattState.DISCONNECTED -> "Eyevue disconnected"
                    EyevueGattState.CONNECTING -> "Connecting to Eyevue"
                    EyevueGattState.CONNECTED -> "Eyevue connected"
                    EyevueGattState.ERROR -> current.lastError ?: "Eyevue connection failed"
                }
                _state.value = current.copy(
                    connectionLabel = label,
                    protocolState = gattState.name,
                )
            }
        }
        scope.launch {
            client.frames.collect(::handleFrame)
        }
    }

    fun connect(address: String, deviceName: String? = null) {
        val normalized = address.trim()
        if (normalized.isBlank()) {
            updateError("No Eyevue Bluetooth address was selected")
            return
        }
        connectJob?.cancel()
        GlassesPhotoCapture.invalidate(DeviceClass.EYEVUE)
        // The GATT client keeps an open link as is: drop it first when other glasses were chosen.
        val previous = _state.value.deviceAddress
        if (previous != null && !previous.equals(normalized, ignoreCase = true)) client.disconnect()
        _state.value = EyevueState(
            connectionLabel = "Connecting to Eyevue",
            protocolState = EyevueGattState.CONNECTING.name,
            deviceAddress = normalized,
            deviceName = deviceName,
        )
        connectJob = scope.launch {
            val result = client.connect(normalized)
            if (result.isFailure) {
                updateError(result.exceptionOrNull()?.message ?: "Eyevue connection failed")
                return@launch
            }

            _state.value = _state.value.copy(
                connectionLabel = "Eyevue connected",
                protocolState = EyevueGattState.CONNECTED.name,
                lastError = null,
            )
            // Match the vendor startup burst without sending any Oudmon command.
            sendNow(EyevueProtocol.buildGetCustomerPacket(), "get customer")
            sendNow(EyevueProtocol.buildGetBatteryPacket(), "get battery")
            sendNow(EyevueProtocol.buildGetCapacityPacket(), "get capacity")
            sendNow(EyevueProtocol.buildGetVoiceAssistantStatusPacket(), "get AI wake-word status")
        }
    }

    fun disconnect() {
        GlassesPhotoCapture.invalidate(DeviceClass.EYEVUE)
        connectJob?.cancel()
        connectJob = null
        client.disconnect()
        _state.value = EyevueState(
            connectionLabel = "Eyevue disconnected",
            protocolState = EyevueGattState.DISCONNECTED.name,
        )
    }

    fun isConnected(): Boolean = client.isConnected()

    /**
     * Homescreen/manual shutter. Sends `AB 55 00 03 22 30 52`.
     * Saves to glasses storage and flashes the LED.
     */
    fun takeManualPhoto() = send(
        EyevueProtocol.buildManualPhotoPacket(),
        "take photo",
    )

    fun takePhoto(highQuality: Boolean = false) = send(
        EyevueProtocol.buildPhotoPacket(highQuality),
        "take photo",
    )

    suspend fun capturePhotoForAi(timeoutMs: Long = 12_000L): ByteArray = photoCapture.capture(timeoutMs)

    fun stopVoiceRecognition() = send(
        EyevueProtocol.buildStopVoiceRecognitionPacket(),
        "stop voice recognition",
    )

    fun toggleVideo() {
        launchCameraCommand("toggle video") {
            val start = !_state.value.isVideoRecording
            if (sendNow(if (start) EyevueProtocol.buildStartVideoPacket() else EyevueProtocol.buildStopVideoPacket(), "toggle video")) {
                _state.value = _state.value.copy(isVideoRecording = start)
            }
        }
    }

    fun toggleAudio() {
        launchCameraCommand("toggle audio") {
            val start = !_state.value.isAudioRecording
            if (sendNow(EyevueProtocol.buildAudioPacket(start), "toggle audio")) {
                _state.value = _state.value.copy(isAudioRecording = start)
            }
        }
    }

    fun requestBattery() = send(EyevueProtocol.buildGetBatteryPacket(), "get battery")

    fun requestMediaCount() = send(
        EyevueProtocol.valuePacket(EyevueProtocol.CMD_GET_MEDIA_COUNT, 0),
        "get media count",
    )

    fun requestDeviceInfo() = send(EyevueProtocol.buildGetDeviceInfoPacket(), "get device info")

    fun requestSupportFunction() = send(
        EyevueProtocol.valuePacket(EyevueProtocol.CMD_GET_SUPPORT_FUNCTION, 0),
        "get supported functions",
    )

    fun requestWifiInfo(p2p: Boolean) = send(
        EyevueProtocol.buildGetWifiInfoPacket(p2p),
        "get Wi-Fi information",
    )

    /** Requests the thumbnail count and waits for the 64/66 reply. Null on timeout. */
    suspend fun awaitMediaCount(timeoutMs: Long = 8_000L): Int? {
        _state.value = _state.value.copy(storageCount = null)
        if (!sendNow(EyevueProtocol.valuePacket(EyevueProtocol.CMD_GET_MEDIA_COUNT, 0), "get media count")) {
            return null
        }
        return withTimeoutOrNull(timeoutMs) {
            state
                .filter { it.storageCount != null }
                .first()
                .storageCount
        }
    }

    /** Requests the battery level and waits for the reply. Null on timeout. */
    suspend fun awaitBatteryPercent(timeoutMs: Long = 8_000L): Int? {
        _state.value = _state.value.copy(batteryPercent = null)
        if (!sendNow(EyevueProtocol.buildGetBatteryPacket(), "get battery")) {
            return null
        }
        return withTimeoutOrNull(timeoutMs) {
            state
                .filter { it.batteryPercent != null }
                .first()
                .batteryPercent
        }
    }

    suspend fun awaitWifiSsid(p2p: Boolean, timeoutMs: Long = 15_000L): String? {
        _state.value = _state.value.copy(wifiSsid = null)
        if (!sendNow(EyevueProtocol.buildGetWifiInfoPacket(p2p), "start media Wi-Fi")) {
            throw IOException("Could not request the Eyevue media Wi-Fi network")
        }
        return withTimeoutOrNull(timeoutMs) {
            state
                .filter { !it.wifiSsid.isNullOrBlank() }
                .first()
                .wifiSsid
        }
    }

    suspend fun awaitProject(timeoutMs: Long = 15_000L): String? = coroutineScope {
        _state.value = _state.value.copy(customer = null, project = null)
        val project = async(start = CoroutineStart.UNDISPATCHED) {
            state
                .filter { !it.project.isNullOrBlank() }
                .first()
                .project
        }
        try {
            if (!sendNow(EyevueProtocol.buildGetCustomerPacket(), "get customer")) {
                throw IOException("Could not request the Eyevue project/model")
            }
            withTimeoutOrNull(timeoutMs) { project.await() }
        } finally {
            if (project.isActive) project.cancel()
        }
    }

    suspend fun startLiveAndAwaitSsid(ap: Boolean, timeoutMs: Long = 15_000L): String? = coroutineScope {
        _state.value = _state.value.copy(wifiSsid = null)
        val ssid = async(start = CoroutineStart.UNDISPATCHED) {
            state
                .filter { !it.wifiSsid.isNullOrBlank() }
                .first()
                .wifiSsid
        }
        val packet = if (ap) {
            EyevueProtocol.buildStartLiveApPacket()
        } else {
            EyevueProtocol.buildStartLiveP2pPacket()
        }
        try {
            if (!sendNow(packet, "start live")) {
                throw IOException("Could not send the Eyevue live command")
            }
            withTimeoutOrNull(timeoutMs) { ssid.await() }
        } finally {
            if (ssid.isActive) ssid.cancel()
        }
    }

    suspend fun stopLiveBlocking() {
        sendNow(EyevueProtocol.buildFinishTransferPacket(), "stop live")
    }

    fun syncTime() = send(EyevueProtocol.buildSetTimePacket(), "sync time")

    fun requestVolume() = send(
        EyevueProtocol.valuePacket(EyevueProtocol.CMD_GET_VOLUME, 0),
        "get volume",
    )

    fun setWearingDetection(enabled: Boolean) = send(
        EyevueProtocol.buildWearDetectionPacket(enabled),
        "set wearing detection",
    )

    fun setRecordingDuration(seconds: Int) = send(
        EyevueProtocol.buildRecordDurationPacket(seconds),
        "set recording duration",
    )

    fun finishTransfer() = send(EyevueProtocol.buildFinishTransferPacket(), "finish transfer")

    /**
     * Vendor stopP2pWifiConnect mirror: clear the glasses storage only when every
     * file arrived, report partial progress otherwise, keep files when nothing did.
     * Suspending so cleanup ordering stays explicit.
     */
    suspend fun finishMediaSync(completed: Int, total: Int) {
        val packet = when {
            completed > 0 && completed == total -> EyevueProtocol.buildFinishTransferAndClearPacket()
            completed > 0 -> EyevueProtocol.buildFinishTransferPartialPacket(completed)
            else -> EyevueProtocol.buildFinishTransferPacket()
        }
        sendNow(packet, "finish transfer")
    }

    // ── Glasses microphone (customization wake samples) ──

    /** Opens the glasses-mic Opus stream; [listener] gets every 0x46 payload (N x 40-byte packets). */
    suspend fun startMicStream(listener: (ByteArray) -> Unit): Boolean {
        client.voiceListener = listener
        val sent = sendNow(EyevueProtocol.buildStartVoiceStreamPacket(), "start the glasses microphone")
        if (!sent) client.voiceListener = null
        return sent
    }

    suspend fun stopMicStream() {
        try {
            sendNow(EyevueProtocol.buildStopVoiceRecognitionPacket(), "stop the glasses microphone")
        } finally {
            client.voiceListener = null
        }
    }

    // ── JieLi RCSP (AE00): firmware version and firmware update ──

    /** Authenticates on AE00 and reads the target info (op 0x03); null when the glasses do not answer. */
    suspend fun readRcspTargetInfo(): RcspTargetInfo? = rcspMutex.withLock {
        if (!client.isConnected() || !client.hasRcsp()) return@withLock null
        val sender = RcspOtaSender(ByteArray(0), readInfoOnly = true)
        val result = runRcsp(sender, reconnect = { false }, onProgress = {}, cancelRequested = { false }, idleTimeoutMs = RCSP_INFO_TIMEOUT_MS)
        if (result == "ok") sender.info.also { rcspInfo = it } else {
            Log.w(TAG, "RCSP target info failed: $result")
            null
        }
    }

    /**
     * Installs a `.ufw` through JieLi RCSP. The glasses restart during the update; this reconnects
     * by address (the loader exposes only AE00). Returns "ok" or "error: ...".
     */
    suspend fun installRcspFirmware(ufw: ByteArray, onProgress: (String) -> Unit): String =
        installRcspFirmware(ufw, cancelRequested = { false }) { event ->
            onProgress(
                when (event) {
                    is RcspInstallProgress.Sending -> "Sending firmware: ${event.percent}%"
                    RcspInstallProgress.Restarting -> "The glasses are restarting; reconnecting…"
                },
            )
        }

    /**
     * [installRcspFirmware] with structured progress and a cancel request: [cancelRequested] is
     * honoured only while stopping keeps the installed firmware intact ([RcspOtaSender.canCancel]);
     * a cancelled install returns "error: cancelled".
     */
    suspend fun installRcspFirmware(
        ufw: ByteArray,
        cancelRequested: () -> Boolean,
        onEvent: (RcspInstallProgress) -> Unit,
    ): String = rcspMutex.withLock {
        val address = _state.value.deviceAddress ?: return@withLock "error: no EyeVue address"
        if (!client.isConnected()) return@withLock "error: EyeVue is not connected"
        val sender = RcspOtaSender(ufw)
        val result = runRcsp(
            sender,
            reconnect = {
                onEvent(RcspInstallProgress.Restarting)
                reconnectForUpdate(address)
            },
            onProgress = onEvent,
            cancelRequested = cancelRequested,
        )
        rcspInfo = sender.info
        if (result == "ok") {
            "ok: firmware ${sender.versionBefore ?: "?"} -> ${sender.info.version ?: "?"}"
        } else {
            result
        }
    }

    private suspend fun reconnectForUpdate(address: String): Boolean {
        repeat(RCSP_RECONNECT_ATTEMPTS) {
            delay(RCSP_RECONNECT_DELAY_MS)
            client.disconnect()
            if (client.connect(address, requireVendorService = false).isSuccess) return true
        }
        return false
    }

    private suspend fun runRcsp(
        sender: RcspOtaSender,
        reconnect: suspend () -> Boolean,
        onProgress: (RcspInstallProgress) -> Unit,
        cancelRequested: () -> Boolean,
        idleTimeoutMs: Long = RCSP_IDLE_TIMEOUT_MS,
    ): String {
        val incoming = Channel<ByteArray>(Channel.UNLIMITED)
        client.rcspListener = { incoming.trySend(it) }
        try {
            if (client.enableRcsp().isFailure) return "error: the glasses have no JieLi update service"
            suspend fun flush(): Boolean {
                // Remember a passed auth before writing: the next session on this link reuses it.
                if (sender.linkAuthenticated) client.markRcspAuthenticated()
                return sender.drainOutbox().all { client.writeRcsp(it).isSuccess }
            }
            sender.onConnected(linkAuthenticated = client.rcspAuthenticated)
            if (!flush()) return "error: write failed"
            var lastPercent = -1
            while (sender.state != RcspOtaSender.State.DONE) {
                if (cancelRequested() && sender.cancel()) {
                    flush()
                    return "error: cancelled"
                }
                val restarting = sender.state == RcspOtaSender.State.RECONNECT || sender.state == RcspOtaSender.State.REBOOTING
                // Short waits so a cancel request is seen promptly; the idle limit stays the same.
                val wait = if (restarting) RCSP_RESTART_WAIT_MS else idleTimeoutMs
                var data: ByteArray? = null
                var waited = 0L
                while (data == null && waited < wait && !(cancelRequested() && sender.canCancel)) {
                    val slice = minOf(RCSP_CANCEL_POLL_MS, wait - waited)
                    data = withTimeoutOrNull(slice) { incoming.receive() }
                    waited += slice
                }
                if (data == null && cancelRequested() && sender.canCancel) continue
                if (data != null) {
                    sender.onBytes(data)
                    if (!flush() && !restarting) return "error: write failed"
                    sender.progressPercent?.takeIf { it != lastPercent }?.let {
                        lastPercent = it
                        onProgress(RcspInstallProgress.Sending(it, cancelable = sender.canCancel))
                    }
                    continue
                }
                if (!restarting && client.isConnected()) return "error: the glasses stopped answering (${sender.state})"
                val wasRebooting = sender.state == RcspOtaSender.State.REBOOTING
                if (!reconnect()) {
                    return if (wasRebooting) "ok" else "error: could not reconnect to the glasses"
                }
                if (client.enableRcsp().isFailure) return "error: the glasses have no JieLi update service after restart"
                while (incoming.tryReceive().isSuccess) Unit
                sender.onReconnected()
                if (!flush()) return "error: write failed"
            }
            return sender.result ?: "error: no result"
        } finally {
            client.rcspListener = null
        }
    }

    private fun send(packet: ByteArray, operation: String) {
        if (packet.size > 4 && (packet[4].toInt() and 255) == EyevueProtocol.CMD_TAKE_PHOTO) {
            launchCameraCommand(operation) { sendNow(packet, operation) }
            return
        }
        scope.launch { sendNow(packet, operation) }
    }

    private fun launchCameraCommand(operation: String, block: suspend () -> Unit) {
        scope.launch {
            runCatching { GlassesPhotoCapture.withCapture(DeviceClass.EYEVUE) { block() } }
                .onFailure { updateError("Could not $operation: ${it.message}") }
        }
    }

    private suspend fun sendNow(packet: ByteArray, operation: String): Boolean {
        val result = client.write(packet)
        if (result.isFailure) {
            val error = result.exceptionOrNull() ?: IOException("Unknown Eyevue write failure")
            Log.w(TAG, "Eyevue $operation failed: ${error.message}")
            updateError("Could not $operation: ${error.message}")
            return false
        } else {
            Log.d(TAG, "Eyevue command sent: $operation")
            return true
        }
    }

    private fun handleFrame(frame: EyevueFrame) {
        Log.d(TAG, "Eyevue frame cmd=${frame.commandId} payloadSize=${frame.payload.size}")
        EyevueProtocol.parseBattery(frame)?.let { battery ->
            _state.value = _state.value.copy(
                batteryPercent = battery.percent.coerceIn(0, 100),
                isCharging = battery.isCharging,
                lastError = null,
            )
        }
        EyevueProtocol.parseWifiSsid(frame)?.let { ssid ->
            _state.value = _state.value.copy(wifiSsid = ssid, lastError = null)
        }
        EyevueProtocol.parseCustomer(frame)?.let { customer ->
            _state.value = _state.value.copy(
                customer = customer.customer,
                project = customer.project,
                lastError = null,
            )
        }
        EyevueProtocol.parseVoiceAssistantStatus(frame)?.let { status ->
            _state.value = _state.value.copy(
                aiWakeWordEnabled = status.aiWakeWordEnabled,
                localOfflineSpeechEnabled = status.localOfflineSpeechEnabled,
            )
            if (!status.aiWakeWordEnabled) {
                send(
                    EyevueProtocol.buildSetVoiceAssistantStatusPacket(
                        localOfflineSpeechEnabled = status.localOfflineSpeechEnabled,
                        aiWakeWordEnabled = true,
                    ),
                    "enable AI wake word",
                )
            }
        }

        when (frame.commandId) {
            EyevueProtocol.CMD_RECEIVE_VOICE_DATA_START -> if (client.voiceListener == null) {
                Log.i(TAG, "Eyevue AI wake word started a voice stream")
                _wakeWordEvents.tryEmit(Unit)
            }

            EyevueProtocol.CMD_GET_MEDIA_COUNT,
            EyevueProtocol.CMD_RECEIVE_THUMBNAIL_COUNT,
            -> parseU16(frame.payload)?.let { count ->
                _state.value = _state.value.copy(storageCount = count)
            }

            69 -> {
                if (frame.payload.size >= 3) {
                    _state.value = _state.value.copy(
                        isVideoRecording = frame.payload[2].toInt() == 1,
                        isAudioRecording = frame.payload[1].toInt() == 1,
                    )
                }
            }
        }
    }

    private fun parseU16(payload: ByteArray): Int? {
        if (payload.size < 2) return null
        return ((payload[0].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF)
    }

    private fun updateError(message: String) {
        Log.w(TAG, message)
        _state.value = _state.value.copy(
            connectionLabel = "Eyevue error",
            lastError = message,
        )
    }
}
