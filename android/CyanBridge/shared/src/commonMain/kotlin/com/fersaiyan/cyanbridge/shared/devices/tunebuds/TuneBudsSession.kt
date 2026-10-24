package com.fersaiyan.cyanbridge.shared.devices.tunebuds

import com.fersaiyan.cyanbridge.shared.platform.PlatformLogger
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/** The link a [TuneBudsSession] talks over: RFCOMM on Android, BLE (FDB3/FF17/FF18) on iOS. */
interface TuneBudsTransport {
    val isConnected: Boolean

    /** Largest packet the link accepts in one write (BLE: ATT payload; SPP: unlimited). */
    val maxWriteLength: Int

    /** Writes one encoded AB Mate frame. */
    suspend fun write(packet: ByteArray)
}

data class TuneBudsSessionState(
    val batteryPercent: Int? = null,
    val isCharging: Boolean = false,
    val firmwareVersion: String? = null,
    val coprocessorVersion: String? = null,
    val model: String? = null,
    val serialNumber: String? = null,
    val storage: TuneBudsStorageInfo? = null,
    val mediaCounts: TuneBudsMediaCounts? = null,
    val workState: Int? = null,
    val isVideoRecording: Boolean = false,
    val isAudioRecording: Boolean = false,
    val lastError: String? = null,
)

/**
 * The AB Mate command session of TuneBuds glasses, independent of the link.
 * Same requests and frame handling as Android's TuneBudsManager.
 */
class TuneBudsSession(private val transport: TuneBudsTransport) {
    private val decoder = TuneBudsFrameDecoder()
    private val writeMutex = Mutex()
    private val requestMutex = Mutex()
    private var nextHostSequence = 0
    private var maxPacketSize = TuneBudsProtocol.DEFAULT_MAX_PACKET_SIZE
    private var aiPicture = byteArrayOf()
    private val _frames = MutableSharedFlow<TuneBudsFrame>(extraBufferCapacity = 64)
    private val _aiPhotos = MutableSharedFlow<ByteArray>(extraBufferCapacity = 1)
    private val _state = MutableStateFlow(TuneBudsSessionState())

    val state: StateFlow<TuneBudsSessionState> = _state.asStateFlow()

    /** AI photos pushed by the glasses (their AI button or [capturePhotoForAi]). */
    val aiPhotos: SharedFlow<ByteArray> = _aiPhotos.asSharedFlow()

    /** Feed every chunk received from the link. */
    fun onBytesReceived(chunk: ByteArray) {
        decoder.append(chunk).forEach { frame ->
            handleFrame(frame)
            _frames.tryEmit(frame)
        }
    }

    /** Call when the link (re)connects, before [initialize]. */
    fun reset() {
        decoder.reset()
        nextHostSequence = 0
        maxPacketSize = TuneBudsProtocol.DEFAULT_MAX_PACKET_SIZE
        aiPicture = byteArrayOf()
        _state.value = TuneBudsSessionState()
    }

    /** Android: TuneBudsManager.initializeProtocol. */
    suspend fun initialize() {
        val capabilities = requestDeviceInfo(
            TuneBudsProtocol.INFO_MAX_PACKET_SIZE,
            TuneBudsProtocol.INFO_AI_KIT_SUPPORT,
        )
        capabilities[TuneBudsProtocol.INFO_MAX_PACKET_SIZE]
            ?.firstOrNull()
            ?.toInt()
            ?.and(0xFF)
            ?.takeIf { it > TuneBudsProtocol.HEADER_SIZE }
            ?.let { size -> maxPacketSize = size.coerceAtMost(transport.maxWriteLength).coerceAtMost(260) }
        requestDeviceInfo(
            TuneBudsProtocol.INFO_BATTERY,
            TuneBudsProtocol.INFO_FIRMWARE_VERSION,
            TuneBudsProtocol.INFO_MODEL,
            TuneBudsProtocol.INFO_SERIAL_NUMBER,
        )
        runCatching { requireSuccess(request(TuneBudsProtocol.CMD_COPROCESSOR_VERSION)) }
        requireSuccess(request(TuneBudsProtocol.CMD_STORAGE))
        requireSuccess(request(TuneBudsProtocol.CMD_MEDIA_COUNTS))
        TuneBudsProtocol.parseStatus(request(TuneBudsProtocol.CMD_WORK_STATE).payload)?.let { value ->
            _state.value = _state.value.copy(workState = value)
        }
    }

    suspend fun takePhoto() {
        requireSuccess(request(TuneBudsProtocol.CMD_CAMERA_ON, byteArrayOf(0)))
    }

    /** Takes an AI photo and waits for its bytes; null on timeout. */
    suspend fun capturePhotoForAi(timeoutMs: Long = AI_PHOTO_TIMEOUT_MS): ByteArray? = coroutineScope {
        aiPicture = byteArrayOf()
        val photo = async(start = CoroutineStart.UNDISPATCHED) { aiPhotos.first() }
        try {
            requireSuccess(request(TuneBudsProtocol.CMD_CAMERA_ON, byteArrayOf(1)))
            withTimeoutOrNull(timeoutMs) { photo.await() }
        } finally {
            photo.cancel()
        }
    }

    suspend fun toggleVideo() {
        if (_state.value.isVideoRecording) {
            stopCameraSubsystem()
        } else {
            requireSuccess(request(TuneBudsProtocol.CMD_START_VIDEO))
            _state.value = _state.value.copy(isVideoRecording = true, isAudioRecording = false)
        }
    }

    suspend fun toggleAudio() {
        if (_state.value.isAudioRecording) {
            stopCameraSubsystem()
        } else {
            requireSuccess(request(TuneBudsProtocol.CMD_START_AUDIO))
            _state.value = _state.value.copy(isAudioRecording = true, isVideoRecording = false)
        }
    }

    suspend fun requestBattery() {
        requestDeviceInfo(TuneBudsProtocol.INFO_BATTERY)
    }

    suspend fun requestVersion() {
        requestDeviceInfo(
            TuneBudsProtocol.INFO_FIRMWARE_VERSION,
            TuneBudsProtocol.INFO_MODEL,
            TuneBudsProtocol.INFO_SERIAL_NUMBER,
        )
        runCatching { requireSuccess(request(TuneBudsProtocol.CMD_COPROCESSOR_VERSION)) }
    }

    suspend fun requestStorageAndMediaCounts() {
        requireSuccess(request(TuneBudsProtocol.CMD_STORAGE))
        requireSuccess(request(TuneBudsProtocol.CMD_MEDIA_COUNTS))
    }

    suspend fun syncTime() {
        requireSuccess(request(TuneBudsProtocol.CMD_SET_TIME, TuneBudsProtocol.buildSetTimePayload()))
    }

    private suspend fun requestDeviceInfo(vararg types: Int): Map<Int, ByteArray> {
        val response = request(TuneBudsProtocol.CMD_DEVICE_INFO, TuneBudsProtocol.deviceInfoQuery(*types))
        return TuneBudsProtocol.parseDeviceInfo(response.payload).also(::applyDeviceInfo)
    }

    private suspend fun request(command: Int, payload: ByteArray = byteArrayOf()): TuneBudsFrame =
        requestMutex.withLock {
            coroutineScope {
                val response = async(start = CoroutineStart.UNDISPATCHED) {
                    withTimeout(REQUEST_TIMEOUT_MS) {
                        _frames
                            .filter { it.type == TuneBudsFrameType.RESPONSE && it.command == command }
                            .first()
                    }
                }
                try {
                    send(command, payload)
                    response.await()
                } catch (error: Throwable) {
                    response.cancel()
                    throw error
                }
            }
        }

    private suspend fun send(command: Int, payload: ByteArray) = writeMutex.withLock {
        check(transport.isConnected) { "TuneBuds are not connected" }
        val encoded = TuneBudsProtocol.encode(
            command = command,
            payload = payload,
            maxPacketSize = maxPacketSize,
            initialSequence = nextHostSequence,
        )
        encoded.frames.forEach { transport.write(it) }
        nextHostSequence = encoded.nextSequence
    }

    private suspend fun stopCameraSubsystem() {
        repeat(CAMERA_CLOSE_MAX_ATTEMPTS) { attempt ->
            val response = request(TuneBudsProtocol.CMD_CAMERA_CLOSE)
            when (val status = TuneBudsProtocol.parseStatus(response.payload)) {
                0 -> {
                    _state.value = _state.value.copy(isVideoRecording = false, isAudioRecording = false)
                    return
                }
                // The recording stopped, but the camera subsystem is still saving it.
                1 -> if (attempt < CAMERA_CLOSE_MAX_ATTEMPTS - 1) delay(CAMERA_CLOSE_RETRY_MS)
                2 -> error("TuneBuds camera is busy transferring media")
                3 -> error("TuneBuds camera is busy updating")
                null -> error("TuneBuds camera close returned no status")
                else -> error("TuneBuds camera close failed with status $status")
            }
        }
        error("TuneBuds camera was still saving after ${CAMERA_CLOSE_MAX_ATTEMPTS}s")
    }

    private fun requireSuccess(response: TuneBudsFrame) {
        val status = TuneBudsProtocol.parseStatus(response.payload)
            ?: error("TuneBuds command 0x${response.command.toString(16)} returned no status")
        check(status == 0) { "TuneBuds command 0x${response.command.toString(16)} failed with status $status" }
    }

    private fun handleFrame(frame: TuneBudsFrame) {
        val notification = frame.type == TuneBudsFrameType.NOTIFICATION
        when (frame.command) {
            TuneBudsProtocol.CMD_DEVICE_INFO -> if (frame.type == TuneBudsFrameType.RESPONSE) {
                runCatching { TuneBudsProtocol.parseDeviceInfo(frame.payload) }.onSuccess(::applyDeviceInfo)
            }
            TuneBudsProtocol.INFO_BATTERY -> TuneBudsProtocol.parseBattery(frame.payload)?.let(::applyBattery)
            TuneBudsProtocol.CMD_AI_PICTURE -> if (notification) handleAiPicture(frame.payload)
            TuneBudsProtocol.CMD_COPROCESSOR_VERSION -> if (notification) {
                TuneBudsProtocol.parseString(frame.payload)?.let { version ->
                    _state.value = _state.value.copy(coprocessorVersion = version)
                }
            }
            TuneBudsProtocol.CMD_STORAGE -> if (notification) {
                TuneBudsProtocol.parseStorage(frame.payload)?.let { storage ->
                    _state.value = _state.value.copy(storage = storage)
                }
            }
            TuneBudsProtocol.CMD_MEDIA_COUNTS -> if (notification) {
                TuneBudsProtocol.parseMediaCounts(frame.payload)?.let { counts ->
                    _state.value = _state.value.copy(mediaCounts = counts)
                }
            }
            TuneBudsProtocol.CMD_WORK_STATE -> TuneBudsProtocol.parseStatus(frame.payload)?.let { value ->
                _state.value = _state.value.copy(workState = value)
            }
        }
    }

    private fun applyDeviceInfo(info: Map<Int, ByteArray>) {
        var current = _state.value
        info[TuneBudsProtocol.INFO_BATTERY]?.let(TuneBudsProtocol::parseBattery)?.let { battery ->
            current = current.copy(batteryPercent = battery.percent.coerceIn(0, 100), isCharging = battery.isCharging)
        }
        info[TuneBudsProtocol.INFO_FIRMWARE_VERSION]?.let(TuneBudsProtocol::parseFirmwareVersion)?.let {
            current = current.copy(firmwareVersion = it)
        }
        info[TuneBudsProtocol.INFO_MODEL]?.let(TuneBudsProtocol::parseString)?.let { current = current.copy(model = it) }
        info[TuneBudsProtocol.INFO_SERIAL_NUMBER]?.let(TuneBudsProtocol::parseString)?.let {
            current = current.copy(serialNumber = it)
        }
        _state.value = current.copy(lastError = null)
    }

    private fun applyBattery(battery: TuneBudsBattery) {
        _state.value = _state.value.copy(batteryPercent = battery.percent.coerceIn(0, 100), isCharging = battery.isCharging)
    }

    private fun handleAiPicture(payload: ByteArray) {
        if (payload.isEmpty()) return
        when (val status = payload[0].toInt() and 0xFF) {
            1 -> aiPicture += payload.copyOfRange(1, payload.size)
            0 -> {
                val image = aiPicture + payload.copyOfRange(1, payload.size)
                aiPicture = byteArrayOf()
                PlatformLogger.i(TAG, "TuneBuds AI photo completed bytes=${image.size}")
                if (image.isNotEmpty()) _aiPhotos.tryEmit(image)
            }
            else -> {
                aiPicture = byteArrayOf()
                _state.value = _state.value.copy(lastError = "TuneBuds AI photo failed with status $status")
            }
        }
    }

    private companion object {
        const val TAG = "TuneBudsSession"
        const val REQUEST_TIMEOUT_MS = 10_000L
        // BLE notifications are 20-byte frames, so a photo takes far longer than over RFCOMM.
        const val AI_PHOTO_TIMEOUT_MS = 60_000L
        const val CAMERA_CLOSE_RETRY_MS = 1_000L
        const val CAMERA_CLOSE_MAX_ATTEMPTS = 15
    }
}
