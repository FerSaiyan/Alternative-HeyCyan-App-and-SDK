package com.fersaiyan.cyanbridge.devices.eyevue

import android.content.Context
import android.util.Log
import com.fersaiyan.cyanbridge.glasses.GlassesPhotoCapture
import com.fersaiyan.cyanbridge.glasses.NativeGlassesPhotoCapture
import com.fersaiyan.cyanbridge.shared.devices.DeviceClass
import com.fersaiyan.cyanbridge.shared.devices.eyevue.EyevueFrame
import com.fersaiyan.cyanbridge.shared.devices.eyevue.EyevueProtocol
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

/** Owns Eyevue's native BLE connection and exposes vendor commands to the app. */
class EyevueManager private constructor(context: Context) {
    companion object {
        private const val TAG = "EyevueManager"

        @Volatile
        private var instance: EyevueManager? = null

        fun getInstance(context: Context): EyevueManager =
            instance ?: synchronized(this) {
                instance ?: EyevueManager(context.applicationContext).also { instance = it }
            }
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

    fun requestStorage() = send(EyevueProtocol.buildGetCapacityPacket(), "get capacity")

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
            EyevueProtocol.CMD_RECEIVE_VOICE_DATA_START -> {
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
