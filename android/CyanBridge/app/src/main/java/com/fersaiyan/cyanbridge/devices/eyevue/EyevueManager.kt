package com.fersaiyan.cyanbridge.devices.eyevue

import android.content.Context
import android.util.Log
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

    val state: StateFlow<EyevueState> = _state.asStateFlow()
    val wakeWordEvents: SharedFlow<Unit> = _wakeWordEvents.asSharedFlow()

    init {
        scope.launch {
            client.state.collect { gattState ->
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
        connectJob?.cancel()
        connectJob = null
        client.disconnect()
        _state.value = EyevueState(
            connectionLabel = "Eyevue disconnected",
            protocolState = EyevueGattState.DISCONNECTED.name,
        )
    }

    fun isConnected(): Boolean = client.isConnected()

    fun takePhoto(highQuality: Boolean = true) = send(
        EyevueProtocol.buildPhotoPacket(highQuality),
        "take photo",
    )

    suspend fun capturePhotoForAi(timeoutMs: Long = 12_000L): ByteArray? = coroutineScope {
        val photo = async(start = CoroutineStart.UNDISPATCHED) { client.photos.first() }
        // 0x31 is the only Eyevue command currently verified to return an AI image over AA15.
        // 0x30 timed out without a photo stream in hardware tests, so all AI captures use the
        // same working quality for now. Revisit this when other Eyevue quality modes are known.
        takePhoto(highQuality = true)
        withTimeoutOrNull(timeoutMs) { photo.await() }.also {
            if (it == null) photo.cancel()
        }
    }

    fun stopVoiceRecognition() = send(
        EyevueProtocol.buildStopVoiceRecognitionPacket(),
        "stop voice recognition",
    )

    fun toggleVideo() {
        val start = !_state.value.isVideoRecording
        _state.value = _state.value.copy(isVideoRecording = start)
        send(
            if (start) EyevueProtocol.buildStartVideoPacket() else EyevueProtocol.buildStopVideoPacket(),
            if (start) "start video" else "stop video",
        )
    }

    fun toggleAudio() {
        val start = !_state.value.isAudioRecording
        _state.value = _state.value.copy(isAudioRecording = start)
        send(
            EyevueProtocol.buildAudioPacket(start),
            if (start) "start audio" else "stop audio",
        )
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

    suspend fun awaitWifiSsid(p2p: Boolean, timeoutMs: Long = 15_000L): String? {
        _state.value = _state.value.copy(wifiSsid = null)
        requestWifiInfo(p2p)
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

    private fun send(packet: ByteArray, operation: String) {
        scope.launch { sendNow(packet, operation) }
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

            EyevueProtocol.CMD_GET_CAPACITY,
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
