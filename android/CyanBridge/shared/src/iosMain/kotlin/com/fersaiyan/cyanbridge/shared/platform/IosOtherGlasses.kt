package com.fersaiyan.cyanbridge.shared.platform

import com.fersaiyan.cyanbridge.bridge.core.AiPhotoCapture
import com.fersaiyan.cyanbridge.bridge.core.BatteryReporting
import com.fersaiyan.cyanbridge.bridge.core.CameraCapture
import com.fersaiyan.cyanbridge.bridge.core.DeviceInfoReporting
import com.fersaiyan.cyanbridge.bridge.core.GlassesConnectionControl
import com.fersaiyan.cyanbridge.bridge.core.MediaSyncTransport
import com.fersaiyan.cyanbridge.bridge.core.OnboardRecording
import com.fersaiyan.cyanbridge.shared.ble.IosBleManager
import com.fersaiyan.cyanbridge.shared.ble.IosTuneBudsSession
import com.fersaiyan.cyanbridge.shared.ble.SdkConnectionState
import com.fersaiyan.cyanbridge.shared.ble.SdkGlassesActions
import com.fersaiyan.cyanbridge.shared.ble.SdkGlassesBridge
import com.fersaiyan.cyanbridge.shared.ble.SdkGlassesListener
import com.fersaiyan.cyanbridge.shared.ble.SdkGlassesRegistry
import com.fersaiyan.cyanbridge.shared.ble.SdkGlassesStatus
import com.fersaiyan.cyanbridge.shared.ble.awaitAiPhoto
import com.fersaiyan.cyanbridge.shared.ble.awaitConnect
import com.fersaiyan.cyanbridge.shared.ble.awaitPerform
import com.fersaiyan.cyanbridge.shared.devices.DeviceClass
import com.fersaiyan.cyanbridge.shared.devices.GlassesControlPanel
import com.fersaiyan.cyanbridge.shared.devices.GlassesFamilyProfile
import com.fersaiyan.cyanbridge.shared.devices.GlassesFamilyProfiles
import com.fersaiyan.cyanbridge.shared.devices.GlassesManagerGating
import com.fersaiyan.cyanbridge.shared.devices.tunebuds.TuneBudsSession
import com.fersaiyan.cyanbridge.shared.devices.mentra.MentraDisplayAdapter
import com.fersaiyan.cyanbridge.shared.devices.mentra.MentraDisplayInput
import com.fersaiyan.cyanbridge.shared.devices.mentra.MentraDisplayPort
import com.fersaiyan.cyanbridge.shared.devices.mentra.MentraSdkModel
import com.fersaiyan.cyanbridge.bridge.core.BridgeError
import com.fersaiyan.cyanbridge.bridge.core.DeviceInfo
import com.fersaiyan.cyanbridge.bridge.core.GlassesBridgeState
import com.fersaiyan.cyanbridge.bridge.core.GlassesCapability
import com.fersaiyan.cyanbridge.bridge.core.GlassesDeviceAdapter
import com.fersaiyan.cyanbridge.bridge.core.InputEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import com.fersaiyan.cyanbridge.shared.glasses.GlassesDashboardAction
import com.fersaiyan.cyanbridge.shared.glasses.GlassesDashboardUiState
import com.fersaiyan.cyanbridge.shared.glasses.GlassesMediaCounts
import com.fersaiyan.cyanbridge.shared.glasses.GlassesTransferUiState
import com.fersaiyan.cyanbridge.shared.glasses.MeizuMyvuUiState
import com.fersaiyan.cyanbridge.shared.glasses.MetaRegistrationEvent
import com.fersaiyan.cyanbridge.shared.glasses.MetaRegistrationPhase
import com.fersaiyan.cyanbridge.shared.glasses.MetaRegistrationTracker
import com.fersaiyan.cyanbridge.shared.glasses.PrefsMetaRegistrationStore
import com.fersaiyan.cyanbridge.shared.generated.resources.Res
import com.fersaiyan.cyanbridge.shared.generated.resources.meta_setup_dev_mode_lost
import org.jetbrains.compose.resources.getString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import platform.Foundation.NSData

/** What the glasses sessions need from the app controller. */
internal interface IosGlassesHost {
    val dashboard: GlassesDashboardUiState

    /** The shared BLE link is up. */
    val isBleConnected: Boolean

    fun updateDashboard(transform: (GlassesDashboardUiState) -> GlassesDashboardUiState)

    /** A photo from the glasses: answer it as an image question. */
    fun onGlassesPhoto(bytes: ByteArray)

    /** The glasses' AI button or capture gesture. */
    fun onGlassesButton()

    /** An SDK-driven connection (MoYoung, Meta, Mentra) came up or went down. */
    fun onSdkConnectionChanged(connected: Boolean)
}

/**
 * The glasses beyond HeyCyan (QCSDK) and Eyevue on iOS, one [IosGlassesDevice] per family:
 * - TuneBuds: AB Mate over BLE ([IosTuneBudsSession]), [tuneBudsDevice];
 * - MoYoung, Meta Ray-Ban, Mentra Live: their Swift SDKs ([SdkGlassesBridge]), [sdkDevice];
 * - Meizu MYVU: not drivable on iOS (its app relay is RFCOMM-only), so the card explains that and
 *   the assistant runs over the glasses' Bluetooth audio, [meizuDevice].
 * This class keeps the link of the selected class (the SDK listener, the TuneBuds session).
 */
internal class IosOtherGlasses(
    private val bleManager: IosBleManager,
    private val scope: CoroutineScope,
    private val host: IosGlassesHost,
    /** Pairing scan, reconnect and BLE disconnect of the app controller. */
    private val bleConnection: GlassesConnectionControl,
) {
    val tuneBuds = IosTuneBudsSession(bleManager, scope, onPhoto = host::onGlassesPhoto)

    var deviceClass: DeviceClass = DeviceClass.UNKNOWN
        private set

    /** The SDK bridge of the selected class, when it is an SDK-driven class built into this app. */
    val sdkBridge: SdkGlassesBridge? get() = SdkGlassesRegistry.bridgeFor(deviceClass)

    var isSdkConnected: Boolean = false
        private set

    /** The SDK connection as a bridge state, for the Mentra display adapters. */
    private val sdkBridgeState = MutableStateFlow<GlassesBridgeState>(GlassesBridgeState.Disconnected)

    /**
     * Hybrid Meta registration (see MetaRegistration.kt). iOS DAT 1.0 does not expose isDevMode,
     * so the path is only known for an app id of "0", where it can only be Developer Mode.
     */
    private var metaAppIdConfigured = false
    private var metaTracker = MetaRegistrationTracker(PrefsMetaRegistrationStore(), metaAppIdConfigured)

    private fun metaPhase(label: String): MetaRegistrationPhase = when (label) {
        "Registered" -> MetaRegistrationPhase.REGISTERED
        "Registering" -> MetaRegistrationPhase.REGISTERING
        else -> MetaRegistrationPhase.NOT_REGISTERED
    }

    private fun onMetaRegistration(label: String) {
        val event = metaTracker.onPhase(metaPhase(label)) { null }
        PlatformLogger.i(TAG, "Meta registration=$label path=${metaTracker.recordedPath} event=$event")
        if (event == MetaRegistrationEvent.DeveloperModeLost) {
            scope.launch { IosAssistantVoice.say(getString(Res.string.meta_setup_dev_mode_lost)) }
        }
    }

    private val sdkListener = object : SdkGlassesListener {
        override fun onConnectionChanged(state: Int, label: String) {
            val connected = state == SdkConnectionState.CONNECTED
            host.updateDashboard {
                it.copy(
                    connectionLabel = when (state) {
                        SdkConnectionState.CONNECTED -> "Connected"
                        SdkConnectionState.CONNECTING -> "Connecting"
                        else -> "Disconnected"
                    },
                    agentStatus = label.ifBlank { it.agentStatus },
                )
            }
            sdkBridgeState.value = when (state) {
                SdkConnectionState.CONNECTED -> GlassesBridgeState.Connected
                SdkConnectionState.CONNECTING -> GlassesBridgeState.Connecting
                else -> GlassesBridgeState.Disconnected
            }
            if (connected != isSdkConnected) {
                isSdkConnected = connected
                host.onSdkConnectionChanged(connected)
            }
        }

        override fun onBattery(level: Int, charging: Boolean) {
            host.updateDashboard { it.copy(batteryPercent = level) }
        }

        override fun onMediaCounts(photos: Int, videos: Int, audio: Int) {
            host.updateDashboard {
                it.copy(
                    storageLabel = "$photos photos / $videos videos / $audio audio",
                    mediaCounts = GlassesMediaCounts(photos, videos, audio),
                )
            }
        }

        override fun onAiImage(data: NSData) {
            host.onGlassesPhoto(data.toKotlinBytes())
        }

        override fun onButton(gesture: String) {
            PlatformLogger.i(TAG, "Glasses button: $gesture")
            val display = (sdkDevices[deviceClass] as? SdkGlasses)?.displayAdapter
            if (display == null) return host.onGlassesButton()
            // Mentra display glasses: long press asks, tap pages, double tap clears (as on Android).
            val input = MentraDisplayInput.touch(gesture) ?: return
            when (MentraDisplayInput.actionFor(input)) {
                MentraDisplayInput.Action.VOICE_QUESTION -> host.onGlassesButton()
                MentraDisplayInput.Action.NEXT_PAGE -> scope.launch { display.showNextPage() }
                MentraDisplayInput.Action.CLEAR_DISPLAY -> scope.launch { display.clearDisplay() }
                MentraDisplayInput.Action.NONE -> Unit
            }
        }

        override fun onStatus(key: String, value: String) {
            host.updateDashboard { state ->
                val meta = state.metaRayban
                when (key) {
                    SdkGlassesStatus.META_REGISTRATION -> {
                        onMetaRegistration(value)
                        state.copy(
                            metaRayban = meta.copy(
                                registrationLabel = value,
                                canRegister = value != "Registered",
                                canUnregister = value == "Registered",
                                registrationPath = metaTracker.recordedPath,
                                showSetupChoice = true,
                                registrationPhase = metaPhase(value),
                                appIdConfigured = metaAppIdConfigured,
                            ),
                        )
                    }
                    SdkGlassesStatus.META_APP_ID_CONFIGURED -> {
                        val configured = value == "true"
                        // Sent once by the bridge before the registration state.
                        if (configured != metaAppIdConfigured) {
                            metaAppIdConfigured = configured
                            metaTracker = MetaRegistrationTracker(PrefsMetaRegistrationStore(), configured)
                        }
                        state.copy(metaRayban = meta.copy(appIdConfigured = configured))
                    }
                    SdkGlassesStatus.META_DEVICE_NAME -> state.copy(metaRayban = meta.copy(selectedDeviceName = value.ifBlank { null }))
                    SdkGlassesStatus.META_DEVICE_COUNT -> state.copy(
                        metaRayban = meta.copy(availableDeviceCount = value.toIntOrNull() ?: 0),
                    )
                    SdkGlassesStatus.META_AI_INSTALLED -> state.copy(metaRayban = meta.copy(metaAiInstalled = value == "true"))
                    SdkGlassesStatus.META_DISPLAY_CAPABLE -> state.copy(metaRayban = meta.copy(displayCapable = value == "true"))
                    SdkGlassesStatus.ERROR -> state.copy(
                        agentLastError = value,
                        metaRayban = if (deviceClass == DeviceClass.META_RAYBAN) meta.copy(lastError = value) else meta,
                    )
                    else -> state
                }
            }
        }
    }

    init {
        scope.launch {
            tuneBuds.state.collect { tb ->
                if (deviceClass != DeviceClass.TUNEBUDS) return@collect
                host.updateDashboard { state ->
                    state.copy(
                        batteryPercent = tb.batteryPercent ?: state.batteryPercent,
                        storageLabel = tb.storage?.let { "${it.usedMiB} MiB used / ${it.freeMiB} MiB free" } ?: state.storageLabel,
                        mediaCounts = tb.mediaCounts?.let {
                            GlassesMediaCounts(it.images.toInt(), it.videos.toInt(), it.audio.toInt())
                        } ?: state.mediaCounts,
                        isVideoRecording = tb.isVideoRecording,
                        isAudioRecording = tb.isAudioRecording,
                        deviceInfoLabel = listOfNotNull(
                            tb.model?.let { "Model: $it" },
                            tb.firmwareVersion?.let { "Firmware: $it" },
                            tb.coprocessorVersion?.let { "Camera: $it" },
                            tb.serialNumber?.let { "Serial: $it" },
                        ).joinToString("\n").ifBlank { state.deviceInfoLabel },
                        agentLastError = tb.lastError ?: state.agentLastError,
                    )
                }
            }
        }
    }

    fun select(deviceClass: DeviceClass) {
        if (this.deviceClass == deviceClass) return
        sdkBridge?.let { previous ->
            if (isSdkConnected) previous.disconnect()
            previous.setListener(null)
        }
        if (isSdkConnected) {
            isSdkConnected = false
            host.onSdkConnectionChanged(false)
        }
        this.deviceClass = deviceClass
        sdkBridge?.setListener(sdkListener)
        if (deviceClass == DeviceClass.MEIZU_MYVU) {
            host.updateDashboard {
                it.copy(
                    meizuMyvu = MeizuMyvuUiState(
                        connectionLabel = "Not supported on iOS",
                        protocolState = "UNAVAILABLE",
                        lastError = MEIZU_UNSUPPORTED,
                        canConnect = false,
                        canDisconnect = false,
                        canSend = false,
                    ),
                )
            }
        }
    }

    /** Selected class whose glasses are driven by a Swift SDK rather than the shared BLE manager. */
    val usesSdk: Boolean get() = deviceClass in SDK_CLASSES

    /** Message for the dashboard when the SDK for the selected class was not built into the app. */
    fun missingSdkMessage(): String? = when {
        !usesSdk || sdkBridge != null -> null
        deviceClass == DeviceClass.MOYOUNG_W620 ->
            "MoYoung glasses need a device build with the MoYoung SDK (run ios/scripts/fetch_moyoung_sdk.sh)"
        MentraSdkModel.forClass(deviceClass)?.onIos == false ->
            "${deviceClass.displayName()} needs the Android app: the Mentra SDK's iOS package leaves out its driver"
        else -> "${deviceClass.displayName()} needs a device build of the iOS app"
    }

    suspend fun connectSdk(identifier: String, name: String): Result<String> {
        val bridge = sdkBridge ?: return Result.failure(IllegalStateException(missingSdkMessage() ?: "SDK unavailable"))
        sdkListener.onConnectionChanged(SdkConnectionState.CONNECTING, "")
        return bridge.awaitConnect(identifier, name).onFailure { error ->
            sdkListener.onConnectionChanged(SdkConnectionState.DISCONNECTED, "")
            host.updateDashboard { it.copy(agentLastError = error.message ?: "Connection failed") }
        }
    }

    fun disconnectSdk() {
        sdkBridge?.disconnect()
        sdkListener.onConnectionChanged(SdkConnectionState.DISCONNECTED, "")
    }

    /** BLE link up for a TuneBuds selection. */
    suspend fun onBleConnected() {
        if (deviceClass != DeviceClass.TUNEBUDS) return
        if (!tuneBuds.onConnected()) {
            host.updateDashboard { it.copy(agentLastError = "TuneBuds did not answer over Bluetooth LE") }
        }
    }

    /** TuneBuds over BLE (Android: TuneBudsGlasses over the RFCOMM TuneBudsManager). */
    val tuneBudsDevice: IosGlassesDevice = TuneBudsGlasses()

    private val sdkDevices: Map<DeviceClass, IosGlassesDevice> =
        SDK_CLASSES.associateWith { SdkGlasses(GlassesFamilyProfiles.forClass(it)) }

    /** MoYoung, Meta Ray-Ban or Mentra Live over the SDK bridge of the selected class. */
    fun sdkDevice(deviceClass: DeviceClass): IosGlassesDevice = sdkDevices.getValue(deviceClass)

    /** Meizu MYVU: only the panel, which explains why iOS cannot drive the display. */
    val meizuDevice: IosGlassesDevice = MeizuGlasses()

    private inner class TuneBudsGlasses : IosGlassesDevice {
        override val profile: GlassesFamilyProfile = GlassesFamilyProfiles.tuneBuds
        override val connection: GlassesConnectionControl = bleConnection
        override val isLinked: Boolean get() = host.isBleConnected

        private fun command(label: String, job: suspend (TuneBudsSession) -> Unit) {
            scope.launch { tuneBuds.run(label) { job(this) }?.let(::showError) }
        }

        override val camera = CameraCapture { command("CapturePhoto") { it.takePhoto() } }

        override val recording = object : OnboardRecording {
            override fun toggleVideoRecording() = command("ToggleVideo") { it.toggleVideo() }

            override fun toggleAudioRecording() = command("StartAudioRecording") { it.toggleAudio() }
        }

        override val mediaSync = object : MediaSyncTransport {
            override fun requestMediaCount() = command("RequestMediaCount") { it.requestStorageAndMediaCounts() }

            // The glasses join the phone's hotspot for media sync, which iOS apps cannot set up.
            override fun startSync() =
                showError("TuneBuds media sync needs the Android app: the glasses join the phone's hotspot")

            override fun stopSync() = host.updateDashboard { it.copy(transfer = GlassesTransferUiState(detail = "Sync stopped")) }
        }

        override val battery = object : BatteryReporting {
            override val pollsPeriodically: Boolean = false

            override fun requestBattery() = command("RequestBattery") { it.requestBattery() }

            override fun refreshAfterSelection() = Unit
        }

        override val deviceInfo = object : DeviceInfoReporting {
            override fun requestVersion() = command("RequestVersion") { it.requestVersion() }

            override fun syncTime() = command("SyncTime") { it.syncTime() }
        }

        /** The photo arrives through [IosGlassesHost.onGlassesPhoto]. */
        override val aiPhoto = object : AiPhotoCapture {
            override suspend fun requestAiPhoto(): Boolean {
                scope.launch {
                    tuneBuds.run("AI photo") {
                        if (capturePhotoForAi() == null) error("the photo did not arrive")
                    }?.let(::showError)
                }
                return true
            }
        }

        override val canTakeAiPhoto: Boolean get() = bleManager.isConnected()
    }

    private inner class SdkGlasses(override val profile: GlassesFamilyProfile) : IosGlassesDevice {
        /** Mentra display glasses only: text through the bridge's display actions (Android: MentraLiveManager). */
        val displayAdapter: MentraDisplayAdapter? =
            MentraSdkModel.forClass(profile.deviceClass)?.takeIf { it.hasDisplay }?.let { model ->
                MentraDisplayAdapter(BridgeDisplayPort(model), scope)
            }

        override val answerDisplay: GlassesDeviceAdapter? get() = displayAdapter

        override val connection = object : GlassesConnectionControl {
            override fun scan() = bleConnection.scan()

            override fun reconnect() = bleConnection.reconnect()

            override fun disconnect() = disconnectSdk()
        }
        override val isLinked: Boolean get() = isSdkConnected
        override val aiPhotoUnavailableMessage: String
            get() = missingSdkMessage() ?: IMAGE_QUESTION_NEEDS_GLASSES

        private fun withBridge(block: (SdkGlassesBridge) -> Unit) {
            val bridge = sdkBridge ?: return showError(missingSdkMessage() ?: "SDK unavailable")
            block(bridge)
        }

        /** Runs an SDK action and shows its answer. */
        private fun perform(action: GlassesDashboardAction, name: String) = withBridge { bridge ->
            scope.launch {
                bridge.awaitPerform(name, "")
                    .onSuccess { value ->
                        if (value.isBlank()) return@onSuccess
                        if (action == GlassesDashboardAction.RequestVersion) {
                            host.updateDashboard { it.copy(deviceInfoLabel = value, agentLastError = value) }
                        } else if (action == GlassesDashboardAction.MetaSendDiagnostics) {
                            IosMediaPlatform.showCopyAlert("Meta glasses details", value, "Copy", value)
                        } else {
                            host.updateDashboard { it.copy(agentLastError = value) }
                        }
                    }
                    .onFailure { showError(it.message ?: "Glasses command failed") }
            }
        }

        override val camera = CameraCapture {
            perform(GlassesDashboardAction.CapturePhoto, SdkGlassesActions.CAPTURE_PHOTO)
        }

        override val battery = object : BatteryReporting {
            override val pollsPeriodically: Boolean = false

            override fun requestBattery() = withBridge { it.requestBattery() }

            override fun refreshAfterSelection() = Unit
        }

        override val deviceInfo = object : DeviceInfoReporting {
            override fun requestVersion() = perform(GlassesDashboardAction.RequestVersion, SdkGlassesActions.VERSION)

            override fun syncTime() = perform(GlassesDashboardAction.SyncTime, SdkGlassesActions.SYNC_TIME)
        }

        override val mediaSync = object : MediaSyncTransport {
            override fun requestMediaCount() = withBridge { it.requestMediaCounts() }

            override fun startSync() = showError(IOS_CONTROL_NOT_IMPLEMENTED)

            override fun stopSync() = host.updateDashboard { it.copy(transfer = GlassesTransferUiState(detail = "Sync stopped")) }
        }

        /** The photo arrives through [IosGlassesHost.onGlassesPhoto]. */
        override val aiPhoto = object : AiPhotoCapture {
            override suspend fun requestAiPhoto(): Boolean {
                val bridge = sdkBridge ?: return false
                scope.launch {
                    bridge.awaitAiPhoto()
                        .onSuccess { host.onGlassesPhoto(it.toKotlinBytes()) }
                        .onFailure { showError("Glasses photo failed: ${it.message}") }
                }
                return true
            }
        }

        override val canTakeAiPhoto: Boolean get() = sdkBridge != null

        /** The Meta panel: registration through the Meta AI app, and the diagnostics sheet. */
        override fun handlePanelAction(action: GlassesDashboardAction): Boolean {
            if (profile.controlPanel != GlassesControlPanel.META_RAYBAN) return false
            if (action == GlassesDashboardAction.MetaRegister || action == GlassesDashboardAction.MetaOpenPairing) {
                // Never register while registered or between states (it resets Meta's dev-mode value).
                val phase = host.dashboard.metaRayban.registrationPhase
                if (!metaTracker.beginRegistration(phase)) {
                    PlatformLogger.i(TAG, "Not starting Meta registration while $phase")
                    return true
                }
                // iOS DAT 1.0 has no dev-mode value to wait for.
                metaTracker.registrationUiClosed()
            }
            if (action == GlassesDashboardAction.MetaUnregister) {
                metaTracker.beginUnregistration(host.dashboard.metaRayban.registrationPhase)
            }
            val name = when (action) {
                GlassesDashboardAction.MetaRegister, GlassesDashboardAction.MetaOpenPairing -> SdkGlassesActions.META_REGISTER
                GlassesDashboardAction.MetaUnregister -> SdkGlassesActions.META_UNREGISTER
                GlassesDashboardAction.MetaOpenMetaAi -> SdkGlassesActions.META_OPEN_META_AI
                GlassesDashboardAction.MetaSendDiagnostics -> SdkGlassesActions.META_DIAGNOSTICS
                else -> return false
            }
            perform(action, name)
            return true
        }
    }

    /** A Mentra display model over the Swift bridge of the selected class. */
    private inner class BridgeDisplayPort(private val model: MentraSdkModel) : MentraDisplayPort {
        override val displayModel: MentraSdkModel? get() = model.takeIf { deviceClass == it.deviceClass }
        override val bridgeState: StateFlow<GlassesBridgeState> get() = sdkBridgeState
        override val inputEvents: Flow<InputEvent> = emptyFlow()
        override fun batteryLevel(): Int? = host.dashboard.batteryPercent

        override suspend fun displayText(text: String) {
            perform(SdkGlassesActions.DISPLAY_TEXT, text)
        }

        override suspend fun clearDisplay() {
            perform(SdkGlassesActions.CLEAR_DISPLAY, "")
        }

        private suspend fun perform(action: String, argument: String) {
            val bridge = sdkBridge ?: throw BridgeError.NotConnected(missingSdkMessage() ?: "SDK unavailable")
            bridge.awaitPerform(action, argument).getOrThrow()
        }

        override suspend fun connect(device: DeviceInfo) {
            connectSdk(device.address, device.name).getOrThrow()
        }

        override suspend fun disconnect() = disconnectSdk()

        // The iOS bridge does not stream the Mentra microphone; voice questions use the phone's route.
        override fun openMicrophone(owner: String) = throw BridgeError.UnsupportedCapability(GlassesCapability.MICROPHONE_AUDIO)

        override fun closeMicrophone(owner: String) = Unit
    }

    private inner class MeizuGlasses : IosGlassesDevice {
        override val profile: GlassesFamilyProfile = GlassesFamilyProfiles.meizuMyvu
        override val connection: GlassesConnectionControl = bleConnection
        override val isLinked: Boolean get() = host.isBleConnected

        override fun handlePanelAction(action: GlassesDashboardAction): Boolean {
            if (GlassesManagerGating.requiredPanel(action) != GlassesControlPanel.MEIZU_MYVU) return false
            showError(MEIZU_UNSUPPORTED)
            return true
        }
    }

    private fun showError(message: String) {
        host.updateDashboard { it.copy(agentLastError = message) }
    }

    companion object {
        private const val TAG = "IosOtherGlasses"
        val SDK_CLASSES = setOf(DeviceClass.MOYOUNG_W620, DeviceClass.META_RAYBAN) +
            MentraSdkModel.entries.map { it.deviceClass }
        const val MEIZU_UNSUPPORTED =
            "Meizu MYVU display features need the Android app: after the Bluetooth LE handshake the glasses " +
                "only talk over classic Bluetooth (RFCOMM), which iOS apps cannot open. Pair them in iOS " +
                "Settings ▸ Bluetooth to use voice questions through their microphone and speakers."
    }
}
