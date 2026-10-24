package com.fersaiyan.cyanbridge.ui.main

import android.os.Build
import android.util.Log
import android.widget.Toast
import com.fersaiyan.cyanbridge.BuildConfig
import com.fersaiyan.cyanbridge.ai.AiQuestionForegroundService
import com.fersaiyan.cyanbridge.ai.AiWakeWordPreferences
import com.fersaiyan.cyanbridge.devices.DeviceProfileStore
import com.fersaiyan.cyanbridge.devices.eyevue.EyevueLivePreviewManager
import com.fersaiyan.cyanbridge.devices.eyevue.EyevueLivePreviewPolicy
import com.fersaiyan.cyanbridge.devices.eyevue.EyevueState
import com.fersaiyan.cyanbridge.devices.meizumyvu.MeizuMyvuFailure
import com.fersaiyan.cyanbridge.devices.meizumyvu.MeizuMyvuManager
import com.fersaiyan.cyanbridge.devices.moyoung.MoyoungW620State
import com.fersaiyan.cyanbridge.devices.tunebuds.TuneBudsLivePreviewManager
import com.fersaiyan.cyanbridge.devices.tunebuds.TuneBudsState
import com.fersaiyan.cyanbridge.glasses.GlassesSessionCoordinator
import com.fersaiyan.cyanbridge.ota.LivePreviewManager
import com.fersaiyan.cyanbridge.ota.LivePreviewState
import com.fersaiyan.cyanbridge.shared.devices.DeviceProfile
import com.fersaiyan.cyanbridge.shared.devices.GlassesManagerGating
import com.oudmon.ble.base.bluetooth.DeviceManager
import com.oudmon.ble.base.communication.LargeDataHandler

/** Activity state and hooks that the connection coordinator does not own. */
internal interface DeviceConnectionHost {
    val isAiHijackEnabled: Boolean
    val livePreviewManager: LivePreviewManager
    fun handleAiWakeWordActivation(source: String)
    fun startBatteryPolling()
    fun stopBatteryPolling()
    fun showEyevueLivePlayer(player: org.videolan.libvlc.MediaPlayer?)
    fun showTuneBudsLivePlayer(player: org.videolan.libvlc.MediaPlayer?)
    fun dismissLegacyLivePreviewDialog()
}

/**
 * Mirrors the selected glasses' connection, battery and storage into the legacy views and the
 * dashboard, and applies the device profile's capability gating.
 */
internal class DeviceConnectionCoordinator(
    host: MainScreenHost,
    private val connectionHost: DeviceConnectionHost,
    private val devices: DeviceManagerRegistry,
    private val sessionGuard: GlassesSessionGuard,
    private val heyCyanSettings: HeyCyanSettingsController,
    private val metaRaybanUi: MetaRaybanUiController,
    private val meizuMyvuUi: MeizuMyvuUiController,
) : MainScreenController(host), DeviceManagerRegistry.Listener {
    private var pendingBatteryToast = false
    var batteryCallbackRegistered = false

    override fun onMetaRaybanStateChanged() {
        metaRaybanUi.updateMetaRaybanUiState()
        if (devices.isMetaRaybanSelected()) updateConnectionStatus(false)
    }

    override fun onMeizuMyvuStateChanged(state: MeizuMyvuManager.MeizuMyvuUiState) {
        if (state.relayReady) {
            meizuMyvuUi.pendingMeizuMyvuFailure = null
        }
        meizuMyvuUi.updateMeizuMyvuUiState()
        if (devices.isMeizuMyvuSelected()) updateConnectionStatus(false)
    }

    override fun onMeizuMyvuFailure(failure: MeizuMyvuFailure) {
        meizuMyvuUi.pendingMeizuMyvuFailure = failure
        meizuMyvuUi.showPendingMeizuMyvuFailureIfNeeded()
    }

    override fun onEyevueStateChanged(eyevue: EyevueState) {
        if (!devices.isEyevueSelected()) return
        binding.statusText.text = eyevue.connectionLabel
        binding.storageText.text = eyevue.storageCount?.toString() ?: "--"
        updateBatteryText(eyevue.batteryPercent)
        updateDashboardState { state ->
            state.copy(
                connectionLabel = eyevue.connectionLabel,
                batteryPercent = eyevue.batteryPercent,
                storageLabel = eyevue.storageCount?.toString() ?: "--",
            )
        }
    }

    override fun onEyevueWakeWord() {
        if (devices.isEyevueSelected() && connectionHost.isAiHijackEnabled) {
            connectionHost.handleAiWakeWordActivation("eyevue")
        }
    }

    override fun onTuneBudsStateChanged(tuneBuds: TuneBudsState) {
        if (!devices.isTuneBudsSelected()) return
        val storage = tuneBuds.storage?.let {
            "${it.usedMiB} MiB used / ${it.freeMiB} MiB free"
        } ?: "--"
        val counts = tuneBuds.mediaCounts
        val deviceInfo = listOfNotNull(
            tuneBuds.model?.let { "Model: $it" },
            tuneBuds.firmwareVersion?.let { "Firmware: $it" },
            tuneBuds.coprocessorVersion?.let { "Coprocessor: $it" },
            counts?.let { "Media: ${it.images} photos / ${it.videos} videos / ${it.audio} audio" },
        ).joinToString("  ").ifBlank { null }
        binding.statusText.text = tuneBuds.connectionLabel
        binding.storageText.text = storage
        updateBatteryText(tuneBuds.batteryPercent)
        updateDashboardState { state ->
            state.copy(
                connectionLabel = tuneBuds.connectionLabel,
                batteryPercent = tuneBuds.batteryPercent,
                storageLabel = storage,
                deviceInfoLabel = deviceInfo,
                isVideoRecording = tuneBuds.isVideoRecording,
                isAudioRecording = tuneBuds.isAudioRecording,
                transfer = state.transfer.copy(
                    countsLabel = counts?.let {
                        "Photos: ${it.images}  Videos: ${it.videos}  Audio: ${it.audio}"
                    } ?: state.transfer.countsLabel,
                ),
            )
        }
    }

    override fun onMoyoungW620StateChanged(moyoung: MoyoungW620State) {
        if (!devices.isMoyoungW620Selected()) return
        val counts = listOfNotNull(
            moyoung.photoCount?.let { "$it photos" },
            moyoung.videoCount?.let { "$it videos" },
            moyoung.audioCount?.let { "$it audio" },
        ).joinToString(" / ").ifBlank { "--" }
        binding.statusText.text = moyoung.connectionLabel
        binding.storageText.text = counts
        updateBatteryText(moyoung.batteryPercent)
        updateDashboardState { state ->
            state.copy(
                connectionLabel = moyoung.connectionLabel,
                batteryPercent = moyoung.batteryPercent,
                storageLabel = counts,
                transfer = state.transfer.copy(countsLabel = counts),
            )
        }
    }

    override fun onEyevueLivePreviewStateChanged(manager: EyevueLivePreviewManager, lp: LivePreviewState) {
        if (!devices.isEyevueSelected()) return
        dashboardState = dashboardState.copy(
            livePreview = com.fersaiyan.cyanbridge.shared.glasses.LivePreviewUiState(
                isAvailable = EyevueLivePreviewPolicy.isSupported(Build.VERSION.SDK_INT),
                stateLabel = lp.stateLabel,
                detail = lp.detail,
                isScanning = lp.isScanning,
                isPlaying = lp.isPlaying,
                streamUrl = lp.streamUrl,
                canStart = lp.canStart,
                canStop = lp.canStop,
            ),
        )
        // Inline video (EyeVue only): expose the player to
        // Compose after live starts. No popup dialog.
        // Dismiss any legacy dialog from older builds.
        connectionHost.dismissLegacyLivePreviewDialog()
        connectionHost.showEyevueLivePlayer(if (lp.isPlaying) manager.getPlayer() else null)
    }

    override fun onTuneBudsLivePreviewStateChanged(manager: TuneBudsLivePreviewManager, lp: LivePreviewState) {
        if (!devices.isTuneBudsSelected()) return
        dashboardState = dashboardState.copy(
            livePreview = com.fersaiyan.cyanbridge.shared.glasses.LivePreviewUiState(
                isAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O,
                stateLabel = lp.stateLabel, detail = lp.detail,
                isScanning = lp.isScanning, isPlaying = lp.isPlaying,
                streamUrl = lp.streamUrl, canStart = lp.canStart, canStop = lp.canStop,
            ),
        )
        connectionHost.showTuneBudsLivePlayer(if (lp.isPlaying) manager.getPlayer() else null)
    }

    fun updateConnectionStatus(connected: Boolean) {
        if (devices.isTuneBudsSelected()) {
            val tuneBuds = devices.getOrCreateTuneBudsManager().state.value
            val storage = tuneBuds.storage?.let {
                "${it.usedMiB} MiB used / ${it.freeMiB} MiB free"
            } ?: "--"
            binding.statusText.text = tuneBuds.connectionLabel
            binding.storageText.text = storage
            updateBatteryText(tuneBuds.batteryPercent)
            updateDashboardState { state ->
                state.copy(
                    connectionLabel = tuneBuds.connectionLabel,
                    batteryPercent = tuneBuds.batteryPercent,
                    storageLabel = storage,
                )
            }
            updateDeviceClassText()
            return
        }
        if (devices.isEyevueSelected()) {
            val eyevue = devices.getOrCreateEyevueManager().state.value
            val status = eyevue.connectionLabel
            binding.statusText.text = status
            binding.storageText.text = eyevue.storageCount?.toString() ?: "--"
            updateBatteryText(eyevue.batteryPercent)
            updateDashboardState { state ->
                state.copy(
                    connectionLabel = status,
                    storageLabel = eyevue.storageCount?.toString() ?: "--",
                )
            }
            updateDeviceClassText()
            return
        }
        if (devices.isMeizuMyvuSelected()) {
            val myvu = devices.getOrCreateMeizuMyvuManager().state.value
            binding.statusText.text = myvu.connectionLabel
            updateDashboardState { state ->
                state.copy(
                    connectionLabel = myvu.connectionLabel,
                    batteryPercent = myvu.batteryPercent,
                )
            }
            updateDeviceClassText()
            return
        }
        if (devices.isMetaRaybanSelected()) {
            val manager = devices.getOrCreateMetaRaybanManager()
            val status = when {
                !manager.isInitialized.value -> "Meta Ray-Ban selected"
                manager.isCameraReady() -> "Meta Ray-Ban ready"
                manager.registrationState.value == com.fersaiyan.cyanbridge.devices.metarayban.MetaRaybanManager.RegistrationState.REGISTERED ->
                    "Meta Ray-Ban registered"
                else -> "Meta Ray-Ban not registered"
            }
            binding.statusText.text = status
            updateDashboardState { state -> state.copy(connectionLabel = status) }
            updateDeviceClassText()
            return
        }

        val deviceName = DeviceManager.getInstance().deviceName
        val status = if (connected) {
            if (!deviceName.isNullOrBlank()) {
                "Connected - $deviceName"
            } else {
                "Connected"
            }
        } else {
            "Disconnected"
        }
        binding.statusText.text = status
        updateDashboardState { state -> state.copy(connectionLabel = status) }
        updateDeviceClassText()
        if (connected) {
            heyCyanSettings.configureHeyCyanWakeWordIfNeeded()
            heyCyanSettings.refreshHeyCyanRecordingSettings(showDisconnectedMessage = false)
        } else {
            AiQuestionForegroundService.stop(activity)
            heyCyanSettings.wakeWordConfiguredForConnection = false
        }
        if (!connected) {
            updateBatteryText(null)
        }
    }

    fun rejectHeyCyanOnlyFeature(feature: String): Boolean {
        if (!devices.isMetaRaybanSelected()) return false
        Toast.makeText(
            activity,
            "$feature is unavailable for Meta Ray-Ban. Use the DAT controls instead.",
            Toast.LENGTH_LONG,
        ).show()
        return true
    }

    fun updateDeviceClassText() {
        val profile = DeviceProfileStore.loadLastSelected(activity)
        val classLabel = profile?.selectedClass?.displayName() ?: "Unknown"
        binding.tvDeviceClass.text = "Class: $classLabel"
        updateDashboardState { state -> state.copy(deviceClassLabel = classLabel) }

        applyGlassesManagerGating(profile)
    }

    /** Applies device-profile capabilities to both the legacy controls and shared dashboard. */
    private fun applyGlassesManagerGating(profile: DeviceProfile?) {
        val model = GlassesManagerGating.uiModel(profile)

        // The hidden legacy panel remains HeyCyan-specific; Compose uses granular flags below.
        binding.layoutHeycyanExtras.visibility =
            if (model.isVisible(GlassesManagerGating.Action.HEY_CYAN_EXTRAS)) android.view.View.VISIBLE else android.view.View.GONE

        // Meta Ray-Ban controls panel
        binding.layoutMetaRayban.visibility =
            if (model.isVisible(GlassesManagerGating.Action.META_RAYBAN_CONTROLS)) android.view.View.VISIBLE else android.view.View.GONE

        // Status placeholders
        val showBattery = model.isVisible(GlassesManagerGating.Action.STATUS_BATTERY)
        val showStorage = model.isVisible(GlassesManagerGating.Action.STATUS_STORAGE)

        binding.layoutBattery.visibility = if (showBattery) android.view.View.VISIBLE else android.view.View.GONE
        binding.layoutStorage.visibility = if (showStorage) android.view.View.VISIBLE else android.view.View.GONE
        binding.layoutStatusMetrics.visibility =
            if (showBattery || showStorage) android.view.View.VISIBLE else android.view.View.GONE
        updateDashboardState { state ->
            state.copy(
                showHeyCyanControls = model.isVisible(GlassesManagerGating.Action.HEY_CYAN_EXTRAS),
                showEyevueControls = model.isVisible(GlassesManagerGating.Action.EYEVUE_CONTROLS),
                showTuneBudsControls = model.isVisible(GlassesManagerGating.Action.TUNEBUDS_CONTROLS),
                showCaptureSettings = model.isVisible(GlassesManagerGating.Action.CAPTURE_SETTINGS),
                showMediaSync = true,
                showAiWakeWordRouting = model.isVisible(GlassesManagerGating.Action.AI_WAKE_WORD_ROUTING),
                showAdvancedControls = model.isVisible(GlassesManagerGating.Action.ADVANCED_CONTROLS),
                showAdvancedLocalAgent = model.isVisible(GlassesManagerGating.Action.ADVANCED_LOCAL_AGENT),
                showAdvancedDeviceInfo = model.isVisible(GlassesManagerGating.Action.ADVANCED_DEVICE_INFO),
                showAdvancedDeviceVolume = model.isVisible(GlassesManagerGating.Action.ADVANCED_DEVICE_VOLUME),
                showAdvancedImageQuality = model.isVisible(GlassesManagerGating.Action.ADVANCED_IMAGE_QUALITY),
                showAdvancedDeveloperTools = model.isVisible(GlassesManagerGating.Action.ADVANCED_DEVELOPER_TOOLS),
                showAdvancedOta = model.isVisible(GlassesManagerGating.Action.ADVANCED_OTA),
                deviceInfoLabel = state.deviceInfoLabel.takeIf {
                    model.isVisible(GlassesManagerGating.Action.TUNEBUDS_CONTROLS)
                },
                firmwarePatchRequest = state.firmwarePatchRequest.takeIf {
                    model.isVisible(GlassesManagerGating.Action.ADVANCED_OTA)
                },
                aiWakeWordRoute = AiWakeWordPreferences.route(activity),
                showMetaRaybanControls = model.isVisible(GlassesManagerGating.Action.META_RAYBAN_CONTROLS),
                showMeizuMyvuControls = model.isVisible(GlassesManagerGating.Action.MEIZU_MYVU_CONTROLS),
                showBattery = showBattery,
                showStorage = showStorage,
                storageLabel = if (showStorage) state.storageLabel else "--",
                wifiAdbDebug = state.wifiAdbDebug.copy(
                    isAvailable = BuildConfig.DEBUG &&
                        model.isVisible(GlassesManagerGating.Action.WIFI_ADB_DEBUG),
                ),
                livePreview = when {
                    model.isVisible(GlassesManagerGating.Action.HEY_CYAN_EXTRAS) ->
                        livePreviewUiState(connectionHost.livePreviewManager.uiState.value, isAvailable = true)
                    model.isVisible(GlassesManagerGating.Action.EYEVUE_CONTROLS) ->
                        livePreviewUiState(devices.eyevueLivePreviewManager?.uiState?.value,
                            isAvailable = EyevueLivePreviewPolicy.isSupported(Build.VERSION.SDK_INT))
                    model.isVisible(GlassesManagerGating.Action.TUNEBUDS_CONTROLS) ->
                        livePreviewUiState(devices.tuneBudsLivePreviewManager?.uiState?.value,
                            isAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    else -> com.fersaiyan.cyanbridge.shared.glasses.LivePreviewUiState()
                },
            )
        }

        // Only poll battery for profiles that claim to support it.
        if (showBattery &&
            DeviceProfileStore.selectedClass(activity) == com.fersaiyan.cyanbridge.shared.devices.DeviceClass.HEY_CYAN
        ) {
            connectionHost.startBatteryPolling()
        } else {
            connectionHost.stopBatteryPolling()
            updateBatteryText(null)
            if (devices.isEyevueSelected()) devices.getOrCreateEyevueManager().requestBattery()
            if (devices.isTuneBudsSelected()) {
                devices.getOrCreateTuneBudsManager().takeIf { it.isConnected() }?.requestBattery()
            }
            if (devices.isMoyoungW620Selected()) {
                devices.getOrCreateMoyoungW620Manager().takeIf { it.isConnected() }?.requestBattery()
            }
        }

        if (!showStorage) {
            binding.storageText.text = "--"
        }

        // Initialize Meta Ray-Ban manager if needed
        if (model.isVisible(GlassesManagerGating.Action.META_RAYBAN_CONTROLS)) {
            val manager = devices.getOrCreateMetaRaybanManager()
            if (metaRaybanUi.metaAndroidPermissionsMissing().isEmpty()) {
                manager.initialize()
            }
            metaRaybanUi.updateMetaRaybanUiState()
        }

        if (model.isVisible(GlassesManagerGating.Action.MEIZU_MYVU_CONTROLS)) {
            devices.getOrCreateMeizuMyvuManager()
            meizuMyvuUi.updateMeizuMyvuUiState()
        }

        if (model.isVisible(GlassesManagerGating.Action.EYEVUE_CONTROLS)) {
            devices.getOrCreateEyevueLivePreviewManager()
        }

        if (model.isVisible(GlassesManagerGating.Action.TUNEBUDS_CONTROLS)) {
            devices.getOrCreateTuneBudsLivePreviewManager()
            if (GlassesSessionCoordinator.currentSession() == null) {
                devices.getOrCreateTuneBudsManager().takeIf { it.isConnected() }?.refreshStatus()
            }
        }
        if (devices.isMoyoungW620Selected()) {
            devices.getOrCreateMoyoungW620Manager().takeIf { it.isConnected() }?.requestMediaCount()
        }
    }

    private fun livePreviewUiState(
        state: com.fersaiyan.cyanbridge.ota.LivePreviewState?,
        isAvailable: Boolean,
    ): com.fersaiyan.cyanbridge.shared.glasses.LivePreviewUiState {
        val live = state ?: com.fersaiyan.cyanbridge.ota.LivePreviewState()
        return com.fersaiyan.cyanbridge.shared.glasses.LivePreviewUiState(
            isAvailable = isAvailable, stateLabel = live.stateLabel, detail = live.detail,
            isScanning = live.isScanning, isPlaying = live.isPlaying, streamUrl = live.streamUrl,
            canStart = live.canStart, canStop = live.canStop,
        )
    }

    fun updateBatteryText(battery: Int?) {
        binding.batteryText.text = battery?.let { "$it%" } ?: "--%"
        updateDashboardState { state -> state.copy(batteryPercent = battery) }
    }

    fun requestBatteryStatus(showToast: Boolean) {
        if (rejectHeyCyanOnlyFeature("Battery status")) return
        if (sessionGuard.isGlassesCommandBlocked("battery status request")) return
        if (showToast) {
            pendingBatteryToast = true
            Toast.makeText(activity, "Requesting battery level…", Toast.LENGTH_SHORT).show()
        }
        ensureBatteryCallback()
        // Trigger battery sync
        LargeDataHandler.getInstance().syncBattery()
    }

    private fun ensureBatteryCallback() {
        if (batteryCallbackRegistered) {
            return
        }
        batteryCallbackRegistered = true
        // Add battery listener. According to the SDK docs this
        // callback is invoked when syncBattery completes.
        LargeDataHandler.getInstance().addBatteryCallBack("init") { _, response ->
            val result = BatteryResponseParser.parse(response)
            Log.i("BatteryCallback", result.message)
            activity.runOnUiThread {
                updateBatteryText(result.battery)
                if (pendingBatteryToast) {
                    Toast.makeText(
                        activity,
                        result.message,
                        Toast.LENGTH_LONG
                    ).show()
                    pendingBatteryToast = false
                }
            }
        }
    }

    fun handleBatteryReport(battery: Int, charging: Boolean) {
        val message = "Battery: $battery% (${if (charging) "charging" else "not charging"})"
        Log.i("BatteryCallback", message)
        activity.runOnUiThread {
            updateBatteryText(battery)
            if (pendingBatteryToast) {
                Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
                pendingBatteryToast = false
            }
        }
    }
}
