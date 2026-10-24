package com.fersaiyan.cyanbridge.shared.platform

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
import com.fersaiyan.cyanbridge.shared.devices.tunebuds.TuneBudsSession
import com.fersaiyan.cyanbridge.shared.glasses.GlassesDashboardAction
import com.fersaiyan.cyanbridge.shared.glasses.GlassesDashboardUiState
import com.fersaiyan.cyanbridge.shared.glasses.GlassesMediaCounts
import com.fersaiyan.cyanbridge.shared.glasses.MeizuMyvuUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import platform.Foundation.NSData

/** What the glasses sessions need from the app controller. */
internal interface IosGlassesHost {
    fun updateDashboard(transform: (GlassesDashboardUiState) -> GlassesDashboardUiState)

    /** A photo from the glasses: answer it as an image question. */
    fun onGlassesPhoto(bytes: ByteArray)

    /** The glasses' AI button or capture gesture. */
    fun onGlassesButton()

    /** An SDK-driven connection (MoYoung, Meta, Mentra) came up or went down. */
    fun onSdkConnectionChanged(connected: Boolean)
}

/**
 * The glasses beyond HeyCyan (QCSDK) and Eyevue on iOS:
 * - TuneBuds: AB Mate over BLE ([IosTuneBudsSession]);
 * - MoYoung, Meta Ray-Ban, Mentra Live: their Swift SDKs ([SdkGlassesBridge]);
 * - Meizu MYVU: not drivable on iOS (its app relay is RFCOMM-only), so the card explains that and
 *   the assistant runs over the glasses' Bluetooth audio.
 */
internal class IosOtherGlasses(
    private val bleManager: IosBleManager,
    private val scope: CoroutineScope,
    private val host: IosGlassesHost,
) {
    val tuneBuds = IosTuneBudsSession(bleManager, scope, onPhoto = host::onGlassesPhoto)

    var deviceClass: DeviceClass = DeviceClass.UNKNOWN
        private set

    /** The SDK bridge of the selected class, when it is an SDK-driven class built into this app. */
    val sdkBridge: SdkGlassesBridge? get() = SdkGlassesRegistry.bridgeFor(deviceClass)

    var isSdkConnected: Boolean = false
        private set

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
            if (connected != isSdkConnected) {
                isSdkConnected = connected
                host.onSdkConnectionChanged(connected)
            }
        }

        override fun onBattery(level: Int, charging: Boolean) {
            host.updateDashboard { it.copy(batteryPercent = level, showBattery = true) }
        }

        override fun onMediaCounts(photos: Int, videos: Int, audio: Int) {
            host.updateDashboard {
                it.copy(
                    storageLabel = "$photos photos / $videos videos / $audio audio",
                    mediaCounts = GlassesMediaCounts(photos, videos, audio),
                    showStorage = true,
                )
            }
        }

        override fun onAiImage(data: NSData) {
            host.onGlassesPhoto(data.toKotlinBytes())
        }

        override fun onButton(gesture: String) {
            PlatformLogger.i(TAG, "Glasses button: $gesture")
            host.onGlassesButton()
        }

        override fun onStatus(key: String, value: String) {
            host.updateDashboard { state ->
                val meta = state.metaRayban
                when (key) {
                    SdkGlassesStatus.META_REGISTRATION -> state.copy(
                        metaRayban = meta.copy(
                            registrationLabel = value,
                            canRegister = value != "Registered",
                            canUnregister = value == "Registered",
                        ),
                    )
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
                        showBattery = tb.batteryPercent != null || state.showBattery,
                        storageLabel = tb.storage?.let { "${it.usedMiB} MiB used / ${it.freeMiB} MiB free" } ?: state.storageLabel,
                        mediaCounts = tb.mediaCounts?.let {
                            GlassesMediaCounts(it.images.toInt(), it.videos.toInt(), it.audio.toInt())
                        } ?: state.mediaCounts,
                        showStorage = tb.storage != null || tb.mediaCounts != null || state.showStorage,
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

    /** True when [requestAiPhoto] can ask the selected glasses for a photo. */
    val canTakeAiPhoto: Boolean
        get() = (deviceClass == DeviceClass.TUNEBUDS && bleManager.isConnected()) || (usesSdk && sdkBridge != null)

    /**
     * Asks the glasses for a photo for the assistant; it arrives through [IosGlassesHost.onGlassesPhoto].
     * False when the selected glasses cannot take one.
     */
    fun requestAiPhoto(): Boolean = when {
        deviceClass == DeviceClass.TUNEBUDS && bleManager.isConnected() -> {
            scope.launch {
                tuneBuds.run("AI photo") {
                    if (capturePhotoForAi() == null) error("the photo did not arrive")
                }?.let(::showError)
            }
            true
        }
        usesSdk && sdkBridge != null -> {
            val bridge = sdkBridge!!
            scope.launch {
                bridge.awaitAiPhoto()
                    .onSuccess { host.onGlassesPhoto(it.toKotlinBytes()) }
                    .onFailure { showError("Glasses photo failed: ${it.message}") }
            }
            true
        }
        else -> false
    }

    /** Dashboard actions for the selected class; false when the generic handler should run. */
    fun handle(action: GlassesDashboardAction): Boolean = when (deviceClass) {
        DeviceClass.TUNEBUDS -> handleTuneBuds(action)
        DeviceClass.MOYOUNG_W620, DeviceClass.META_RAYBAN, DeviceClass.MENTRA_LIVE -> handleSdk(action)
        DeviceClass.MEIZU_MYVU -> handleMeizu(action)
        else -> false
    }

    private fun handleTuneBuds(action: GlassesDashboardAction): Boolean {
        val job: (suspend TuneBudsSession.() -> Unit) = when (action) {
            GlassesDashboardAction.CapturePhoto -> ({ takePhoto() })
            GlassesDashboardAction.ToggleVideo -> ({ toggleVideo() })
            GlassesDashboardAction.StartAudioRecording -> ({ toggleAudio() })
            GlassesDashboardAction.RequestBattery -> ({ requestBattery() })
            GlassesDashboardAction.RequestVersion -> ({ requestVersion() })
            GlassesDashboardAction.RequestMediaCount -> ({ requestStorageAndMediaCounts() })
            GlassesDashboardAction.SyncTime -> ({ syncTime() })
            GlassesDashboardAction.StartSync -> {
                // The glasses join the phone's hotspot for media sync, which iOS apps cannot set up.
                showError("TuneBuds media sync needs the Android app: the glasses join the phone's hotspot")
                return true
            }
            else -> return false
        }
        scope.launch { tuneBuds.run(action::class.simpleName ?: "TuneBuds command", job)?.let(::showError) }
        return true
    }

    private fun handleSdk(action: GlassesDashboardAction): Boolean {
        val bridge = sdkBridge
        val perform: Pair<String, String>? = when (action) {
            GlassesDashboardAction.CapturePhoto -> SdkGlassesActions.CAPTURE_PHOTO to ""
            GlassesDashboardAction.SyncTime -> SdkGlassesActions.SYNC_TIME to ""
            GlassesDashboardAction.RequestVersion -> SdkGlassesActions.VERSION to ""
            GlassesDashboardAction.MetaRegister, GlassesDashboardAction.MetaOpenPairing -> SdkGlassesActions.META_REGISTER to ""
            GlassesDashboardAction.MetaUnregister -> SdkGlassesActions.META_UNREGISTER to ""
            GlassesDashboardAction.MetaOpenMetaAi -> SdkGlassesActions.META_OPEN_META_AI to ""
            GlassesDashboardAction.MetaSendDiagnostics -> SdkGlassesActions.META_DIAGNOSTICS to ""
            else -> null
        }
        when {
            action == GlassesDashboardAction.RequestBattery -> bridge?.requestBattery()
            action == GlassesDashboardAction.RequestMediaCount -> bridge?.requestMediaCounts()
            perform != null -> scope.launch {
                val target = bridge ?: return@launch showError(missingSdkMessage() ?: "SDK unavailable")
                target.awaitPerform(perform.first, perform.second)
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
            else -> return false
        }
        if (bridge == null) missingSdkMessage()?.let(::showError)
        return true
    }

    private fun handleMeizu(action: GlassesDashboardAction): Boolean = when (action) {
        GlassesDashboardAction.MeizuConnect,
        GlassesDashboardAction.MeizuDisconnect,
        GlassesDashboardAction.MeizuSendTestNotification,
        GlassesDashboardAction.MeizuShowTestTeleprompter,
        GlassesDashboardAction.MeizuSyncClock,
        GlassesDashboardAction.MeizuSetComfortBrightness,
        -> {
            showError(MEIZU_UNSUPPORTED)
            true
        }
        else -> false
    }

    private fun showError(message: String) {
        host.updateDashboard { it.copy(agentLastError = message) }
    }

    companion object {
        private const val TAG = "IosOtherGlasses"
        val SDK_CLASSES = setOf(DeviceClass.MOYOUNG_W620, DeviceClass.META_RAYBAN, DeviceClass.MENTRA_LIVE)
        const val MEIZU_UNSUPPORTED =
            "Meizu MYVU display features need the Android app: after the Bluetooth LE handshake the glasses " +
                "only talk over classic Bluetooth (RFCOMM), which iOS apps cannot open. Pair them in iOS " +
                "Settings ▸ Bluetooth to use voice questions through their microphone and speakers."
    }
}
