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

    /** Largest packet the link accepts in one write (BLE: ATT payload; SPP: 260). */
    val maxWriteLength: Int

    /** Writes one encoded AB Mate frame. */
    suspend fun write(packet: ByteArray)
}

/** Platform hooks on the AI photo stream (Android: capture leases and the Live photo button). */
interface TuneBudsAiPhotoListener {
    /** Every AI photo chunk; [startsPhoto] when it opens a new JPEG. */
    fun onChunk(startsPhoto: Boolean)
    fun onPhoto(image: ByteArray)
    fun onFailure(message: String)
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
    // Capabilities: the packet size the glasses asked for and the device-info values.
    val protocolPacketSize: Int = TuneBudsProtocol.DEFAULT_MAX_PACKET_SIZE,
    val aiKitSupport: Int? = null,
    val cameraCoprocessorType: Int? = null,
    val deviceAbility: ByteArray? = null,
    val deviceCapabilityMask: Int? = null,
    val supportDetection: Int? = null,
    val supportAudio: Int? = null,
    val videoLimitSeconds: Int? = null,
    val audioLimitSeconds: Int? = null,
    val supportVolumeControl: Int? = null,
    val currentVolume: List<Int>? = null,
    val screenConfig: TuneBudsScreenConfig? = null,
    val resolution: Int? = null,
    val supportOpus: Int? = null,
    val supportAiChat: Int? = null,
    val supportAppList: Int? = null,
    val wifiSupportMask: Int? = null,
    /** Last 0xEC notification: the glasses' Wi-Fi state during hotspot media or livestream. */
    val wifiState: Int? = null,
)

/** The AB Mate command session of TuneBuds glasses, independent of the link. */
class TuneBudsSession(private val transport: TuneBudsTransport) {
    private val decoder = TuneBudsFrameDecoder()
    private val writeMutex = Mutex()
    private val requestMutex = Mutex()
    private var nextHostSequence = 0
    private var maxPacketSize = TuneBudsProtocol.DEFAULT_MAX_PACKET_SIZE
    private val aiPicture = TuneBudsPhotoAssembler()
    private val _frames = MutableSharedFlow<TuneBudsFrame>(extraBufferCapacity = 64)
    private val _aiPhotos = MutableSharedFlow<ByteArray>(extraBufferCapacity = 1)
    private val _state = MutableStateFlow(TuneBudsSessionState())

    val state: StateFlow<TuneBudsSessionState> = _state.asStateFlow()

    /** Every frame received, after the session has applied it to [state]. */
    val frames: SharedFlow<TuneBudsFrame> = _frames.asSharedFlow()

    /** AI photos pushed by the glasses (their AI button or [capturePhotoForAi]). */
    val aiPhotos: SharedFlow<ByteArray> = _aiPhotos.asSharedFlow()

    var aiPhotoListener: TuneBudsAiPhotoListener? = null

    /** Frames the decoder had to skip or accept with a sequence gap since the last [reset]. */
    val malformedFrameCount: Int get() = decoder.malformedFrameCount

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
        aiPicture.reset()
        _state.value = TuneBudsSessionState()
    }

    /** Shows a failure from outside the session (connection, a dashboard command) in [state]. */
    fun reportError(message: String) {
        _state.value = _state.value.copy(lastError = message)
    }

    /**
     * Negotiates the packet size and reads status. [readCapabilities] adds the limits, Wi-Fi
     * support and capability values that Android's hotspot media and livestream use.
     */
    suspend fun initialize(readCapabilities: Boolean = false) {
        val capabilities = requestDeviceInfo(TuneBudsProtocol.INFO_MAX_PACKET_SIZE, TuneBudsProtocol.INFO_AI_KIT_SUPPORT)
        capabilities[TuneBudsProtocol.INFO_MAX_PACKET_SIZE]
            ?.firstOrNull()
            ?.toInt()
            ?.and(0xFF)
            ?.takeIf { it > TuneBudsProtocol.HEADER_SIZE }
            ?.let { size ->
                maxPacketSize = size.coerceAtMost(transport.maxWriteLength).coerceAtMost(260)
                _state.value = _state.value.copy(protocolPacketSize = size)
            }
        val aiKitSupport = capabilities[TuneBudsProtocol.INFO_AI_KIT_SUPPORT]?.firstOrNull()?.toInt()?.and(0xFF) ?: 1
        _state.value = _state.value.copy(aiKitSupport = aiKitSupport)
        val limits = if (readCapabilities) {
            intArrayOf(
                TuneBudsProtocol.INFO_VIDEO_LIMIT,
                TuneBudsProtocol.INFO_AUDIO_LIMIT,
                TuneBudsProtocol.INFO_SUPPORT_DETECTION,
                TuneBudsProtocol.INFO_WIFI_SUPPORT,
            )
        } else {
            intArrayOf()
        }
        requestDeviceInfo(
            TuneBudsProtocol.INFO_BATTERY,
            TuneBudsProtocol.INFO_FIRMWARE_VERSION,
            TuneBudsProtocol.INFO_MODEL,
            TuneBudsProtocol.INFO_SERIAL_NUMBER,
            *limits,
        )
        if (readCapabilities) {
            runCatching { requestCapabilities() }
                .onFailure { PlatformLogger.w(TAG, "Optional TuneBuds capability query failed", it) }
        }
        runCatching { requireSuccess(request(TuneBudsProtocol.CMD_COPROCESSOR_VERSION)) }
        requestStorageAndMediaCounts()
        requestWorkState()
    }

    suspend fun takePhoto() {
        requireSuccess(request(TuneBudsProtocol.CMD_CAMERA_ON, byteArrayOf(0)))
    }

    /** Takes an AI photo and waits for its bytes; null on timeout. */
    suspend fun capturePhotoForAi(timeoutMs: Long = AI_PHOTO_TIMEOUT_MS): ByteArray? = coroutineScope {
        resetAiPhoto()
        val photo = async(start = CoroutineStart.UNDISPATCHED) { aiPhotos.first() }
        try {
            requestAiPhoto()
            withTimeoutOrNull(timeoutMs) { photo.await() }
        } finally {
            photo.cancel()
        }
    }

    /** Asks for an AI photo; its chunks arrive as notifications (see [aiPhotoListener], [aiPhotos]). */
    suspend fun requestAiPhoto() {
        requireSuccess(request(TuneBudsProtocol.CMD_CAMERA_ON, byteArrayOf(1)))
    }

    /** Drops a partly received AI photo. */
    fun resetAiPhoto() = aiPicture.reset()

    suspend fun toggleVideo() {
        if (_state.value.isVideoRecording) {
            closeCamera()
        } else {
            requireSuccess(request(TuneBudsProtocol.CMD_START_VIDEO))
            _state.value = _state.value.copy(isVideoRecording = true, isAudioRecording = false)
        }
    }

    suspend fun toggleAudio() {
        if (_state.value.isAudioRecording) {
            closeCamera()
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

    /** Device ability, limits, screen, volume, codec and app-list support, then the camera coprocessor. */
    suspend fun requestCapabilities() {
        requestDeviceInfo(
            TuneBudsProtocol.INFO_DEVICE_ABILITY,
            TuneBudsProtocol.INFO_SUPPORT_DETECTION,
            TuneBudsProtocol.INFO_SUPPORT_AUDIO,
            TuneBudsProtocol.INFO_RESOLUTION,
            TuneBudsProtocol.INFO_SCREEN_CONFIG,
            TuneBudsProtocol.INFO_SUPPORT_VOLUME_CONTROL,
            TuneBudsProtocol.INFO_CURRENT_VOLUME,
            TuneBudsProtocol.INFO_VIDEO_LIMIT,
            TuneBudsProtocol.INFO_AUDIO_LIMIT,
            TuneBudsProtocol.INFO_SUPPORT_OPUS,
            TuneBudsProtocol.INFO_AI_CHAT_SUPPORT,
            TuneBudsProtocol.INFO_SUPPORT_APP_LIST,
        )
        TuneBudsProtocol.parseStatus(request(TuneBudsProtocol.CMD_CAMERA_COPROCESSOR_INFO).payload)?.let { type ->
            applyStatus(_state.value.copy(cameraCoprocessorType = type))
        }
    }

    suspend fun requestMediaCounts() {
        requireSuccess(request(TuneBudsProtocol.CMD_MEDIA_COUNTS))
    }

    suspend fun requestStorageAndMediaCounts() {
        requireSuccess(request(TuneBudsProtocol.CMD_STORAGE))
        requestMediaCounts()
    }

    /** Battery, versions, Wi-Fi support, capabilities, storage and counts; every step must succeed. */
    suspend fun refreshStatus() {
        requestDeviceInfo(
            TuneBudsProtocol.INFO_BATTERY,
            TuneBudsProtocol.INFO_FIRMWARE_VERSION,
            TuneBudsProtocol.INFO_MODEL,
            TuneBudsProtocol.INFO_SERIAL_NUMBER,
            TuneBudsProtocol.INFO_WIFI_SUPPORT,
        )
        requestCapabilities()
        requestStorageAndMediaCounts()
        requireSuccess(request(TuneBudsProtocol.CMD_COPROCESSOR_VERSION))
    }

    suspend fun syncTime() {
        requireSuccess(request(TuneBudsProtocol.CMD_SET_TIME, TuneBudsProtocol.buildSetTimePayload()))
    }

    /** Forgets the last Wi-Fi state before a hotspot handshake, so only new failures count. */
    fun clearWifiState() {
        _state.value = _state.value.copy(wifiState = null)
    }

    /**
     * Closes the camera subsystem: stops a recording, or ends media transfer. Retries while the
     * glasses are still saving.
     */
    suspend fun closeCamera() {
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

    /** Sends [command] and waits for its response; notifications that follow arrive on [frames]. */
    suspend fun request(command: Int, payload: ByteArray = byteArrayOf()): TuneBudsFrame =
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

    private suspend fun requestWorkState() {
        TuneBudsProtocol.parseStatus(request(TuneBudsProtocol.CMD_WORK_STATE).payload)?.let { value ->
            _state.value = _state.value.copy(workState = value)
        }
    }

    private suspend fun requestDeviceInfo(vararg types: Int): Map<Int, ByteArray> {
        val response = request(TuneBudsProtocol.CMD_DEVICE_INFO, TuneBudsProtocol.deviceInfoQuery(*types))
        return TuneBudsProtocol.parseDeviceInfo(response.payload).also(::applyDeviceInfo)
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

    private fun requireSuccess(response: TuneBudsFrame) {
        val status = TuneBudsProtocol.parseStatus(response.payload)
            ?: error("TuneBuds command 0x${response.command.toString(16)} returned no status")
        check(status == 0) { "TuneBuds command 0x${response.command.toString(16)} failed with status $status" }
    }

    private fun handleFrame(frame: TuneBudsFrame) {
        val payload = frame.payload
        val current = _state.value
        val notification = frame.type == TuneBudsFrameType.NOTIFICATION
        when (frame.command) {
            TuneBudsProtocol.CMD_DEVICE_INFO -> if (frame.type == TuneBudsFrameType.RESPONSE) {
                runCatching { TuneBudsProtocol.parseDeviceInfo(payload) }
                    .onSuccess(::applyDeviceInfo)
                    .onFailure { PlatformLogger.w(TAG, "Invalid device-info response", it) }
            }
            TuneBudsProtocol.INFO_BATTERY -> TuneBudsProtocol.parseBattery(payload)?.let { applyStatus(current.withBattery(it)) }
            TuneBudsProtocol.CMD_WORK_STATE -> TuneBudsProtocol.parseStatus(payload)?.let { applyStatus(current.copy(workState = it)) }
            TuneBudsProtocol.CMD_AI_PICTURE -> if (notification) handleAiPicture(payload)
            TuneBudsProtocol.CMD_CAMERA_CLOSE -> if (notification && TuneBudsProtocol.parseStatus(payload) == 0) {
                // The firmware can stop recording on its own (limit or the physical button).
                _state.value = current.copy(isVideoRecording = false, isAudioRecording = false)
            }
            TuneBudsProtocol.CMD_WIFI_STATE -> if (notification) {
                TuneBudsProtocol.parseStatus(payload)?.let { _state.value = current.copy(wifiState = it) }
            }
            TuneBudsProtocol.CMD_COPROCESSOR_VERSION -> if (notification) {
                TuneBudsProtocol.parseString(payload)?.let { applyStatus(current.copy(coprocessorVersion = it)) }
            }
            TuneBudsProtocol.CMD_CAMERA_COPROCESSOR_INFO -> if (notification) {
                TuneBudsProtocol.parseStatus(payload)?.let { applyStatus(current.copy(cameraCoprocessorType = it)) }
            }
            TuneBudsProtocol.CMD_STORAGE -> if (notification) {
                TuneBudsProtocol.parseStorage(payload)?.let { applyStatus(current.copy(storage = it)) }
            }
            TuneBudsProtocol.CMD_MEDIA_COUNTS -> if (notification) {
                TuneBudsProtocol.parseMediaCounts(payload)?.let { applyStatus(current.copy(mediaCounts = it)) }
            }
        }
    }

    /** A status the glasses report also clears an earlier failure. */
    private fun applyStatus(state: TuneBudsSessionState) {
        _state.value = state.copy(lastError = null)
    }

    private fun applyDeviceInfo(info: Map<Int, ByteArray>) {
        fun value(type: Int) = info[type]?.let(TuneBudsProtocol::parseUnsignedValue)
        var current = _state.value
        info[TuneBudsProtocol.INFO_BATTERY]?.let(TuneBudsProtocol::parseBattery)?.let { current = current.withBattery(it) }
        info[TuneBudsProtocol.INFO_DEVICE_ABILITY]?.let { ability ->
            current = current.copy(
                deviceAbility = ability.copyOf(),
                deviceCapabilityMask = TuneBudsProtocol.parseDeviceCapabilities(ability) ?: current.deviceCapabilityMask,
            )
        }
        _state.value = current.copy(
            firmwareVersion = info[TuneBudsProtocol.INFO_FIRMWARE_VERSION]?.let(TuneBudsProtocol::parseFirmwareVersion)
                ?: current.firmwareVersion,
            model = info[TuneBudsProtocol.INFO_MODEL]?.let(TuneBudsProtocol::parseString) ?: current.model,
            serialNumber = info[TuneBudsProtocol.INFO_SERIAL_NUMBER]?.let(TuneBudsProtocol::parseString)
                ?: current.serialNumber,
            supportDetection = value(TuneBudsProtocol.INFO_SUPPORT_DETECTION) ?: current.supportDetection,
            supportAudio = value(TuneBudsProtocol.INFO_SUPPORT_AUDIO) ?: current.supportAudio,
            videoLimitSeconds = value(TuneBudsProtocol.INFO_VIDEO_LIMIT) ?: current.videoLimitSeconds,
            audioLimitSeconds = value(TuneBudsProtocol.INFO_AUDIO_LIMIT) ?: current.audioLimitSeconds,
            supportVolumeControl = value(TuneBudsProtocol.INFO_SUPPORT_VOLUME_CONTROL) ?: current.supportVolumeControl,
            currentVolume = info[TuneBudsProtocol.INFO_CURRENT_VOLUME]?.map { it.toInt() and 0xFF } ?: current.currentVolume,
            screenConfig = info[TuneBudsProtocol.INFO_SCREEN_CONFIG]?.let(TuneBudsProtocol::parseScreenConfig)
                ?: current.screenConfig,
            resolution = value(TuneBudsProtocol.INFO_RESOLUTION) ?: current.resolution,
            supportOpus = value(TuneBudsProtocol.INFO_SUPPORT_OPUS) ?: current.supportOpus,
            supportAiChat = value(TuneBudsProtocol.INFO_AI_CHAT_SUPPORT) ?: current.supportAiChat,
            supportAppList = value(TuneBudsProtocol.INFO_SUPPORT_APP_LIST) ?: current.supportAppList,
            wifiSupportMask = info[TuneBudsProtocol.INFO_WIFI_SUPPORT]?.firstOrNull()?.toInt()?.and(0xFF)
                ?: current.wifiSupportMask,
            lastError = null,
        )
    }

    private fun handleAiPicture(payload: ByteArray) {
        if (payload.isEmpty()) return
        val listener = aiPhotoListener
        listener?.onChunk(startsPhoto = aiPicture.isAtStart() && payload.size > 1 && payload[1] == 0xFF.toByte())
        val status = payload[0].toInt() and 0xFF
        val image = aiPicture.append(payload)
        if (status !in 0..1) {
            val message = "TuneBuds AI photo failed with status $status"
            listener?.onFailure(message)
            _state.value = _state.value.copy(lastError = message)
        }
        if (image != null) {
            PlatformLogger.i(TAG, "TuneBuds AI photo completed bytes=${image.size}")
            listener?.onPhoto(image)
            _aiPhotos.tryEmit(image)
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

private fun TuneBudsSessionState.withBattery(battery: TuneBudsBattery) =
    copy(batteryPercent = battery.percent.coerceIn(0, 100), isCharging = battery.isCharging)
