package com.fersaiyan.cyanbridge.devices.mentra

import android.content.Context
import android.util.Log
import com.fersaiyan.cyanbridge.bridge.core.GlassesBridge
import com.fersaiyan.cyanbridge.bridge.core.GlassesBridgeState
import com.fersaiyan.cyanbridge.bridge.core.GestureType
import com.fersaiyan.cyanbridge.bridge.core.InputEvent
import com.mentra.bluetoothsdk.BluetoothError
import com.mentra.bluetoothsdk.ButtonPressEvent
import com.mentra.bluetoothsdk.ConnectOptions
import com.mentra.bluetoothsdk.Device
import com.mentra.bluetoothsdk.DeviceModel
import com.mentra.bluetoothsdk.GlassesRuntimeState
import com.mentra.bluetoothsdk.MentraBluetoothSdk
import com.mentra.bluetoothsdk.MentraBluetoothSdkCallback
import com.mentra.bluetoothsdk.MentraBluetoothSdkConfig
import com.mentra.bluetoothsdk.MentraBluetoothSdkListener
import com.mentra.bluetoothsdk.OtaStartAckEvent
import com.mentra.bluetoothsdk.BluetoothSdkAnalyticsConfig
import com.mentra.bluetoothsdk.MicPcmEvent
import com.mentra.bluetoothsdk.PhotoCompression
import com.mentra.bluetoothsdk.PhotoRequest
import com.mentra.bluetoothsdk.PhotoResponse
import com.mentra.bluetoothsdk.PhotoResponseEvent
import com.mentra.bluetoothsdk.PhotoSize
import com.mentra.bluetoothsdk.ScanSession
import com.mentra.bluetoothsdk.VersionInfoResult
import com.mentra.bluetoothsdk.VideoRecordingRequest
import com.mentra.bluetoothsdk.WifiScanResult
import com.mentra.bluetoothsdk.WifiStatusEvent
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The single owner of the Mentra Bluetooth SDK and its Mentra Live connection. It follows the
 * SDK's intended flow: [startScan] for the pairing list, [connect] with a scanned [Device] (the
 * SDK bonds and saves it as the default device), and [reconnectSaved] on launch, which restores
 * the app-persisted default device with `setDefaultDevice` and calls `connectDefault`. Link-loss
 * and Bluetooth-on reconnects are run by the SDK itself.
 *
 * Creating the SDK starts its foreground service, so only Mentra code paths call [getInstance];
 * other code uses [instanceOrNull].
 */
class MentraLiveManager private constructor(context: Context) : MentraBluetoothSdkCallback() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sdk = MentraBluetoothSdk.create(
        context.applicationContext,
        MentraBluetoothSdkConfig(
            deliverCallbacksOnMainThread = false,
            analytics = BluetoothSdkAnalyticsConfig.disabled(),
        ),
        this,
    )
    private val _glasses = MutableStateFlow<GlassesRuntimeState>(sdk.getGlasses())
    val glasses: StateFlow<GlassesRuntimeState> = _glasses
    private val _bridgeState = MutableStateFlow<GlassesBridgeState>(GlassesBridgeState.Disconnected)
    val bridgeState: StateFlow<GlassesBridgeState> = _bridgeState
    private val _inputEvents = MutableSharedFlow<InputEvent>(extraBufferCapacity = 32)

    /** Button presses of the connected glasses. */
    val inputEvents: Flow<InputEvent> = _inputEvents
    private val discovered = ConcurrentHashMap<String, Device>()
    private val pcmListeners = ConcurrentHashMap<String, (ByteArray) -> Unit>()

    @Volatile private var scanSession: ScanSession? = null
    @Volatile private var configuredForReady = false
    @Volatile private var activeVideoRequestId: String? = null

    init { GlassesBridge.registerAdapter(MentraLiveAdapter(this)) }

    val connected: Boolean get() = _glasses.value.connected
    val ready: Boolean get() = _glasses.value.ready
    val isRecordingVideo: Boolean get() = activeVideoRequestId != null

    private val connectedState: GlassesRuntimeState.Connected?
        get() = _glasses.value as? GlassesRuntimeState.Connected

    override fun onGlassesChanged(glasses: GlassesRuntimeState) {
        val wasReady = _glasses.value.ready
        _glasses.value = glasses
        _bridgeState.value = when {
            glasses.ready -> GlassesBridgeState.Connected
            glasses.connected || glasses.connection.isBusy -> GlassesBridgeState.Connecting
            else -> GlassesBridgeState.Disconnected
        }
        if (glasses.ready != wasReady) Log.i(TAG, "Glasses ready=${glasses.ready} connection=${glasses.connection}")
        if (glasses.ready && !configuredForReady) {
            configuredForReady = true
            scope.launch { reserveButtonForAssistant() }
        }
        if (!glasses.connected) {
            configuredForReady = false
            activeVideoRequestId = null
        }
    }

    /**
     * Gallery mode makes the action button take photos and videos on the glasses. CyanBridge
     * uses the button for assistant questions, so it asks the glasses to report presses only.
     */
    private suspend fun reserveButtonForAssistant() {
        runCatching { sdk.setGalleryModeEnabled(false) }
            .onSuccess { Log.i(TAG, "Gallery mode off: the action button reports presses to the app") }
            .onFailure { Log.w(TAG, "Could not turn gallery mode off", it) }
    }

    override fun onButtonPress(event: ButtonPressEvent) {
        if (firmwareUpdateActive) {
            Log.i(TAG, "Button ${event.pressType} ignored during the firmware update")
            return
        }
        val gesture = when (event.pressType.lowercase()) {
            "long", "long_press", "hold" -> GestureType.LONG_PRESS
            "double", "double_tap", "double_press" -> GestureType.DOUBLE_TAP
            else -> GestureType.SINGLE_TAP
        }
        Log.i(TAG, "Button ${event.buttonId} ${event.pressType}")
        val input = InputEvent.Button(event.buttonId, gesture)
        _inputEvents.tryEmit(input)
        GlassesBridge.onInputEvent(ADAPTER_ID, input)
    }

    override fun onMicPcm(event: MicPcmEvent) {
        // Do not send corrupt or unexpected PCM to the assistant or the realtime agent.
        if (event.sampleRate != PCM_SAMPLE_RATE_HZ || event.bitsPerSample != 16 ||
            event.channels != 1 || event.encoding != "pcm_s16le" ||
            event.pcm.isEmpty() || event.pcm.size % 2 != 0
        ) return
        val listeners = pcmListeners.values
        if (listeners.isEmpty()) return
        val pcm = event.pcm
        listeners.forEach { it(pcm.copyOf()) }
    }

    override fun onError(error: BluetoothError) {
        Log.w(TAG, "SDK error ${error.code}: ${error.message}")
    }

    override fun onDefaultDeviceChanged(device: Device?) {
        Log.i(TAG, "SDK default device: ${device?.name}")
    }

    // --- Discovery and connection -------------------------------------------------------------

    /**
     * Scans for Mentra Live with the SDK scanner; [onResults] receives the full list on every
     * change. Throws when Bluetooth is off or its permissions are missing.
     */
    fun startScan(timeoutMs: Long = SCAN_TIMEOUT_MS, onResults: (List<Device>) -> Unit) {
        stopScan()
        scanSession = sdk.scan(DeviceModel.MENTRA_LIVE, timeoutMs) { devices ->
            devices.forEach { remember(it) }
            onResults(devices)
        }
        Log.i(TAG, "SDK scan started")
    }

    fun stopScan() {
        scanSession?.let { session ->
            scanSession = null
            runCatching { session.stop() }
        }
    }

    private fun remember(device: Device) {
        discovered[device.id] = device
        device.address?.takeIf { it.isNotBlank() }?.let { discovered[it.uppercase()] = device }
    }

    fun discoveredDevices(): List<Device> = discovered.values.distinctBy { it.id }

    /** A device the SDK scanner reported, by SDK id or Bluetooth address. */
    fun findDiscovered(idOrAddress: String): Device? =
        discovered[idOrAddress] ?: discovered[idOrAddress.uppercase()]

    /**
     * Connects to the glasses chosen in the pairing list. The SDK's scan result is preferred
     * (it carries the secure-pairing flags); without one the SDK connects by name and address.
     * The SDK saves the device as its default for [reconnectSaved].
     */
    fun connect(address: String, name: String?) {
        val device = findDiscovered(address)
            ?: Device(model = DeviceModel.MENTRA_LIVE, name = name.orEmpty(), address = address)
        connect(device)
    }

    fun connect(device: Device) {
        require(device.model == DeviceModel.MENTRA_LIVE) { "Not Mentra Live" }
        require(device.name.isNotBlank()) { "Mentra Live needs its Bluetooth name to connect" }
        stopScan()
        GlassesBridge.setActiveAdapter(ADAPTER_ID)
        _bridgeState.value = GlassesBridgeState.Connecting
        Log.i(TAG, "Connecting to ${device.name} (${device.address})")
        sdk.connect(device)
    }

    /**
     * Reconnects to the saved glasses the way the SDK recommends: restore the app-persisted
     * device with `setDefaultDevice`, then `connectDefault`. Never cancels an attempt or a
     * reconnect the SDK is already running. Returns false when nothing was started.
     */
    fun reconnectSaved(name: String?, address: String?, reason: String): Boolean {
        val glasses = _glasses.value
        if (glasses.connected || glasses.connection.isBusy) {
            Log.d(TAG, "Reconnect ($reason) skipped: connection=${glasses.connection}")
            return false
        }
        val savedName = name?.trim().orEmpty()
        if (savedName.isEmpty()) {
            Log.w(TAG, "Reconnect ($reason) skipped: no saved Mentra Live name")
            return false
        }
        return runCatching {
            if (sdk.getDefaultDevice()?.name != savedName) {
                sdk.setDefaultDevice(
                    Device(model = DeviceModel.MENTRA_LIVE, name = savedName, address = address?.takeIf { it.isNotBlank() }),
                )
            }
            GlassesBridge.setActiveAdapter(ADAPTER_ID)
            sdk.connectDefault(ConnectOptions(saveAsDefault = true, cancelExistingConnectionAttempt = false))
            Log.i(TAG, "Reconnect ($reason) to $savedName requested")
            true
        }.getOrElse { error ->
            Log.w(TAG, "Reconnect ($reason) failed: ${error.message}")
            false
        }
    }

    fun disconnect() {
        if (firmwareUpdateActive) {
            Log.w(TAG, "Disconnect refused during the firmware update")
            return
        }
        pcmListeners.clear()
        runCatching { sdk.setMicState(enabled = false) }
        runCatching { sdk.setMicSourcePin(null) }
        runCatching { sdk.disconnect() }
        _bridgeState.value = GlassesBridgeState.Disconnected
        Log.i(TAG, "Disconnected by the app")
    }

    /** Another glasses family was selected: drop the link and stop the SDK's saved reconnect. */
    private fun release(reason: String) {
        stopScan()
        disconnect()
        runCatching { sdk.clearDefaultDevice() }
        Log.i(TAG, "Released ($reason)")
    }

    // --- Status -----------------------------------------------------------------------------

    fun batteryLevel(): Int? = connectedState?.battery?.level
    fun isCharging(): Boolean = connectedState?.battery?.charging == true

    /** "Firmware x (build y)" from the connection status, or null before the glasses report it. */
    fun firmwareSummary(): String? {
        val state = connectedState ?: return null
        val version = state.firmware?.version ?: state.device?.firmwareVersion ?: return null
        val build = state.firmware?.buildNumber ?: state.device?.buildNumber
        return if (build.isNullOrBlank()) version else "$version (build $build)"
    }

    suspend fun requestVersionInfo(): VersionInfoResult = sdk.requestVersionInfo()

    fun syncTime() = sdk.setSystemTime(System.currentTimeMillis())

    /** Glasses media volume (0-15), read over Bluetooth; blocks, so call it off the main thread. */
    suspend fun mediaVolume(): Int? = withContext(Dispatchers.IO) { sdk.getGlassesMediaVolume().level }

    /** Photo and video counts in the glasses gallery. */
    suspend fun galleryCounts(): Pair<Int, Int> {
        val values = sdk.queryGalleryStatus().values
        return (values["photos"] as? Number)?.toInt().orZero() to (values["videos"] as? Number)?.toInt().orZero()
    }

    private fun Int?.orZero() = this ?: 0

    // --- Camera -----------------------------------------------------------------------------

    suspend fun requestPhoto(request: PhotoRequest): PhotoResponseEvent {
        checkNoFirmwareUpdate()
        return sdk.requestPhoto(request)
    }

    /** Takes a full-size photo that stays in the glasses gallery. */
    suspend fun capturePhotoToGlasses() {
        check(ready) { "Mentra Live is not ready" }
        checkNoFirmwareUpdate()
        val response = sdk.requestPhoto(
            PhotoRequest(
                requestId = "cyanbridge-photo-" + UUID.randomUUID(),
                size = PhotoSize.HIGH,
                webhookUrl = "",
                compress = PhotoCompression.NONE,
                save = true,
            ),
        ).response
        if (response is PhotoResponse.Error) error(response.errorMessage ?: response.errorCode ?: "Photo failed")
    }

    /** Starts a recording saved in the glasses gallery, or stops the running one; true = now recording. */
    suspend fun toggleVideoRecording(): Boolean {
        check(ready) { "Mentra Live is not ready" }
        checkNoFirmwareUpdate()
        val running = activeVideoRequestId
        if (running != null) {
            activeVideoRequestId = null
            sdk.stopVideoRecording(running)
            Log.i(TAG, "Video recording stopped")
            return false
        }
        val requestId = "cyanbridge-video-" + UUID.randomUUID()
        val status = sdk.startVideoRecording(
            VideoRecordingRequest(requestId, true, true, 0, 0, 0, 0),
        )
        check(status.success) { status.details ?: "Video recording did not start" }
        activeVideoRequestId = requestId
        Log.i(TAG, "Video recording started")
        return true
    }

    // --- Audio ------------------------------------------------------------------------------

    /**
     * Streams the glasses microphone (16 kHz mono PCM16) to [listener] until [closeMicrophone].
     * Several owners may listen; the microphone runs while any of them does.
     */
    fun openMicrophone(owner: String, listener: (ByteArray) -> Unit) {
        check(ready) { "Mentra Live is not ready" }
        checkNoFirmwareUpdate()
        val first = pcmListeners.isEmpty()
        pcmListeners[owner] = listener
        if (first) {
            sdk.setMicSourcePin("glasses")
            sdk.setMicState(enabled = true, useGlassesMic = true)
        }
        Log.i(TAG, "Microphone opened by $owner (listeners=${pcmListeners.size})")
    }

    fun closeMicrophone(owner: String) {
        if (pcmListeners.remove(owner) == null) return
        if (pcmListeners.isEmpty()) {
            runCatching { sdk.setMicState(enabled = false) }
            runCatching { sdk.setMicSourcePin(null) }
        }
        Log.i(TAG, "Microphone closed by $owner (listeners=${pcmListeners.size})")
    }

    /** Tells the glasses the app is playing audio to them, so their microphone gate can react. */
    fun setOwnAudioPlaying(playing: Boolean) = sdk.setOwnAppAudioPlaying(playing)

    // --- Firmware update (see MentraOtaAdapter) -------------------------------------------------

    /**
     * True while the firmware update screen runs an update. The SDK guidance is to send no
     * unrelated commands until it ends, so photos, video, the microphone, the action button and
     * the user's disconnect are refused meanwhile.
     */
    @Volatile var firmwareUpdateActive: Boolean = false

    val microphoneInUse: Boolean get() = pcmListeners.isNotEmpty()

    val otaVersionUrl: String get() = sdk.getOtaVersionUrl()

    private fun checkNoFirmwareUpdate() = check(!firmwareUpdateActive) { "Mentra Live is updating its firmware" }

    fun addSdkListener(listener: MentraBluetoothSdkListener) = sdk.addListener(listener)

    fun removeSdkListener(listener: MentraBluetoothSdkListener) = sdk.removeListener(listener)

    suspend fun checkForOtaUpdate(): Boolean = sdk.checkForOtaUpdate()

    suspend fun startOtaUpdate(): OtaStartAckEvent = sdk.startOtaUpdate()

    suspend fun requestWifiScan(): List<WifiScanResult> = sdk.requestWifiScan()

    suspend fun sendWifiCredentials(ssid: String, password: String): WifiStatusEvent = sdk.sendWifiCredentials(ssid, password)

    companion object {
        const val TAG = "MentraLive"
        const val ADAPTER_ID = "mentra_live"
        const val PCM_SAMPLE_RATE_HZ = 16_000
        private const val SCAN_TIMEOUT_MS = 15_000L

        @Volatile private var instance: MentraLiveManager? = null

        fun getInstance(context: Context): MentraLiveManager =
            instance ?: synchronized(this) {
                instance ?: MentraLiveManager(context.applicationContext).also { instance = it }
            }

        /** The manager if a Mentra path already created it; never starts the SDK. */
        fun instanceOrNull(): MentraLiveManager? = instance

        /** Releases the Mentra link when another glasses family is chosen (no-op if never started). */
        fun releaseIfStarted(reason: String) {
            instance?.release(reason)
        }
    }
}
