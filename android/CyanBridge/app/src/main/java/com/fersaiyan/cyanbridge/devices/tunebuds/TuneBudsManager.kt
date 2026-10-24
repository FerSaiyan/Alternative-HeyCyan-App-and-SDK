package com.fersaiyan.cyanbridge.devices.tunebuds

import android.content.Context
import android.util.Log
import com.fersaiyan.cyanbridge.ai.live.GeminiLiveImageButtonRouter
import com.fersaiyan.cyanbridge.glasses.GlassesPhotoCapture
import com.fersaiyan.cyanbridge.glasses.NativeGlassesPhotoCapture
import com.fersaiyan.cyanbridge.shared.devices.DeviceClass
import com.fersaiyan.cyanbridge.shared.devices.tunebuds.TuneBudsAiPhotoListener
import com.fersaiyan.cyanbridge.shared.devices.tunebuds.TuneBudsMediaCounts
import com.fersaiyan.cyanbridge.shared.devices.tunebuds.TuneBudsSessionState
import com.fersaiyan.cyanbridge.shared.devices.tunebuds.TuneBudsStorageInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException

data class TuneBudsState(
    val connectionLabel: String = "TuneBuds disconnected",
    val protocolState: String = TuneBudsSppState.DISCONNECTED.name,
    val deviceAddress: String? = null,
    val deviceName: String? = null,
    /** What the glasses reported over the shared AB Mate session, capabilities included. */
    val device: TuneBudsSessionState = TuneBudsSessionState(),
) {
    val batteryPercent: Int? get() = device.batteryPercent
    val firmwareVersion: String? get() = device.firmwareVersion
    val coprocessorVersion: String? get() = device.coprocessorVersion
    val model: String? get() = device.model
    val storage: TuneBudsStorageInfo? get() = device.storage
    val mediaCounts: TuneBudsMediaCounts? get() = device.mediaCounts
    val wifiSupportMask: Int? get() = device.wifiSupportMask
    val wifiState: Int? get() = device.wifiState
    val lastError: String? get() = device.lastError

    // Recording ends with the RFCOMM link.
    val isVideoRecording: Boolean get() = protocolState == TuneBudsSppState.CONNECTED.name && device.isVideoRecording
    val isAudioRecording: Boolean get() = protocolState == TuneBudsSppState.CONNECTED.name && device.isAudioRecording
}

/** Owns the TuneBuds SPP link; the shared [TuneBudsSession] runs the AB Mate commands over it. */
class TuneBudsManager private constructor(context: Context) {
    companion object {
        private const val TAG = "TuneBudsManager"

        @Volatile
        private var instance: TuneBudsManager? = null

        fun getInstance(context: Context): TuneBudsManager =
            instance ?: synchronized(this) {
                instance ?: TuneBudsManager(context.applicationContext).also { instance = it }
            }
    }

    private val client = TuneBudsSppClient(context.applicationContext)
    private val session = client.session
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val operationMutex = Mutex()
    private val _state = MutableStateFlow(TuneBudsState())
    private var connectJob: Job? = null
    private val photoCapture = NativeGlassesPhotoCapture(
        device = DeviceClass.TUNEBUDS,
        connected = { client.isConnected },
        trigger = session::requestAiPhoto,
        resetStream = session::resetAiPhoto,
        manualScope = scope,
    )

    val state: StateFlow<TuneBudsState> = _state.asStateFlow()

    init {
        session.aiPhotoListener = object : TuneBudsAiPhotoListener {
            override fun onChunk(startsPhoto: Boolean) {
                photoCapture.onPacket()
                if (startsPhoto) {
                    GeminiLiveImageButtonRouter.nativePhotoReceiver(DeviceClass.TUNEBUDS)?.let { receiver ->
                        photoCapture.onManualStreamStart(15_000, receiver)
                    }
                }
            }

            override fun onPhoto(image: ByteArray) {
                photoCapture.onPhoto(image)
            }

            override fun onFailure(message: String) {
                Log.w(TAG, message)
                photoCapture.onFailure(message)
            }
        }
        scope.launch {
            client.state.collect { transportState ->
                if (transportState == TuneBudsSppState.DISCONNECTED || transportState == TuneBudsSppState.ERROR) {
                    GlassesPhotoCapture.invalidate(DeviceClass.TUNEBUDS)
                    session.resetAiPhoto()
                }
                val label = when (transportState) {
                    TuneBudsSppState.DISCONNECTED -> "TuneBuds disconnected"
                    TuneBudsSppState.BONDING -> "Pairing TuneBuds"
                    TuneBudsSppState.CONNECTING -> "Connecting to TuneBuds"
                    TuneBudsSppState.CONNECTED -> "TuneBuds connected"
                    TuneBudsSppState.ERROR -> session.state.value.lastError ?: "TuneBuds connection failed"
                }
                _state.update { it.copy(connectionLabel = label, protocolState = transportState.name) }
            }
        }
        // Read the latest value, not the emitted one, so a late update never restores stale data.
        scope.launch { session.state.collect { _state.update { it.copy(device = session.state.value) } } }
    }

    @Synchronized
    fun connect(address: String, deviceName: String? = null) {
        val normalizedAddress = address.trim()
        if (normalizedAddress.isBlank()) {
            updateError("No TuneBuds Bluetooth address was selected")
            return
        }
        val sameAddress = _state.value.deviceAddress.equals(normalizedAddress, ignoreCase = true)
        if (sameAddress && (client.isConnected || connectJob?.isActive == true)) {
            return
        }
        connectJob?.cancel()
        GlassesPhotoCapture.invalidate(DeviceClass.TUNEBUDS)
        session.reset()
        _state.value = TuneBudsState(
            connectionLabel = "Connecting to TuneBuds",
            protocolState = TuneBudsSppState.CONNECTING.name,
            deviceAddress = normalizedAddress,
            deviceName = deviceName,
        )
        connectJob = scope.launch {
            client.connect(normalizedAddress).getOrElse { error ->
                updateError(error.message ?: "TuneBuds connection failed")
                return@launch
            }
            _state.update {
                it.copy(connectionLabel = "TuneBuds connected", protocolState = TuneBudsSppState.CONNECTED.name)
            }
            runCatching { session.initialize(readCapabilities = true) }
                .onFailure { error -> updateError("TuneBuds status initialization failed: ${error.message}") }
        }
    }

    fun disconnect() {
        GlassesPhotoCapture.invalidate(DeviceClass.TUNEBUDS)
        session.resetAiPhoto()
        connectJob?.cancel()
        connectJob = null
        client.disconnect()
    }

    fun isConnected(): Boolean = client.isConnected

    fun takePhoto() = launchCommand("take photo", usesCamera = true) {
        takeRecordingPhoto()
    }

    suspend fun takePhotoBlocking(lease: GlassesPhotoCapture.Lease) = operationMutex.withLock {
        check(lease.device == DeviceClass.TUNEBUDS)
        lease.ensureActive()
        takeRecordingPhoto()
        lease.ensureActive()
    }

    private suspend fun takeRecordingPhoto() {
        session.takePhoto()
        delay(2_500L)
        session.closeCamera()
    }

    suspend fun capturePhotoForAi(timeoutMs: Long = 15_000L): ByteArray {
        check(operationMutex.tryLock()) { "TuneBuds camera is busy with another operation" }
        try { return photoCapture.capture(timeoutMs) }
        finally { operationMutex.unlock() }
    }

    fun toggleVideo() = launchCommand("toggle video recording", usesCamera = true) { session.toggleVideo() }

    fun toggleAudio() = launchCommand("toggle audio recording", usesCamera = true) { session.toggleAudio() }

    fun requestBattery() = launchCommand("read battery") { session.requestBattery() }

    fun requestVersion() = launchCommand("read versions") {
        session.requestVersion()
        runCatching { session.requestCapabilities() }
            .onFailure { Log.w(TAG, "Optional TuneBuds capability query failed", it) }
    }

    fun requestMediaCount() = launchCommand("read media count") { session.requestMediaCounts() }

    fun syncTime() = launchCommand("sync time") { session.syncTime() }

    fun refreshStatus() = launchCommand("refresh status") { session.refreshStatus() }

    suspend fun startFileManager(
        hotspotSsid: String,
        hotspotPassword: String,
        channel: Int = 0,
        timeoutMs: Long = 60_000L,
    ): String? = operationMutex.withLock {
        if (!client.isConnected) return@withLock null
        session.clearWifiState()
        TuneBudsFileStartup(session.frames, session::request).open(0, hotspotSsid, hotspotPassword, channel, timeoutMs)
    }

    internal suspend fun startP2pFileManager(ssid: String, connect: suspend (String) -> Unit): String = operationMutex.withLock {
        if (!client.isConnected) throw IOException("TuneBuds Bluetooth is not connected")
        session.clearWifiState()
        TuneBudsFileStartup(session.frames, session::request).open(
            2, ssid, "", timeoutMs = 150_000L, connectP2p = connect,
        )
    }

    suspend fun finishTransferBlocking() = operationMutex.withLock { session.closeCamera() }

    suspend fun startLivestream(
        credentials: TuneBudsHotspotCredentials,
        quality: Int = -1,
    ): String {
        if (!isConnected()) throw IOException("TuneBuds Bluetooth is not connected")
        session.clearWifiState()
        return TuneBudsRtspStartup(session.frames, session::request).open(
            credentials.ssid, credentials.password, credentials.channel, quality,
        )
    }

    private fun launchCommand(operation: String, usesCamera: Boolean = false, block: suspend () -> Unit) {
        scope.launch {
            if (!client.isConnected) {
                updateError("Connect TuneBuds glasses before trying to $operation")
                return@launch
            }
            runCatching {
                if (usesCamera) GlassesPhotoCapture.withCapture(DeviceClass.TUNEBUDS) { operationMutex.withLock { block() } }
                else operationMutex.withLock { block() }
            }
                .onFailure { error -> updateError("Could not $operation: ${error.message}") }
        }
    }

    private fun updateError(message: String) {
        Log.w(TAG, message)
        session.reportError(message)
    }
}
