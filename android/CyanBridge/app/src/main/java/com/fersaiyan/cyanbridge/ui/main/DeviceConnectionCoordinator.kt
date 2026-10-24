package com.fersaiyan.cyanbridge.ui.main

import android.os.Build
import android.util.Log
import android.widget.Toast
import com.fersaiyan.cyanbridge.BuildConfig
import com.fersaiyan.cyanbridge.devices.DeviceProfileStore
import com.fersaiyan.cyanbridge.devices.eyevue.EyevueLivePreviewManager
import com.fersaiyan.cyanbridge.devices.eyevue.EyevueLivePreviewPolicy
import com.fersaiyan.cyanbridge.devices.eyevue.EyevueState
import com.fersaiyan.cyanbridge.devices.meizumyvu.MeizuMyvuFailure
import com.fersaiyan.cyanbridge.devices.meizumyvu.MeizuMyvuManager
import com.fersaiyan.cyanbridge.devices.moyoung.MoyoungW620State
import com.fersaiyan.cyanbridge.devices.tunebuds.TuneBudsLivePreviewManager
import com.fersaiyan.cyanbridge.devices.tunebuds.TuneBudsState
import com.fersaiyan.cyanbridge.ota.LivePreviewState
import com.fersaiyan.cyanbridge.shared.devices.DeviceProfile
import com.fersaiyan.cyanbridge.shared.devices.GlassesManagerGating
import com.fersaiyan.cyanbridge.shared.glasses.withFamilyGating
import com.fersaiyan.cyanbridge.shared.prefs.AssistantPrefs
import com.fersaiyan.cyanbridge.shared.prefs.get
import com.fersaiyan.cyanbridge.ui.main.glasses.AndroidGlassesDevices
import com.oudmon.ble.base.communication.LargeDataHandler

/** Activity state and hooks that the connection coordinator does not own. */
internal interface DeviceConnectionHost {
    val isAiHijackEnabled: Boolean
    fun handleAiWakeWordActivation(source: String)
    fun startBatteryPolling()
    fun stopBatteryPolling()
    fun showEyevueLivePlayer(player: org.videolan.libvlc.MediaPlayer?)
    fun showTuneBudsLivePlayer(player: org.videolan.libvlc.MediaPlayer?)
    fun dismissLegacyLivePreviewDialog()
}

/**
 * Mirrors the selected glasses' connection, battery and storage into the legacy views and the
 * dashboard, and applies the device profile's capability gating. Manager callbacks arrive only
 * while their family is selected (see [DeviceManagerRegistry]).
 */
internal class DeviceConnectionCoordinator(
    host: MainScreenHost,
    private val connectionHost: DeviceConnectionHost,
    private val glasses: AndroidGlassesDevices,
    private val sessionGuard: GlassesSessionGuard,
    private val metaRaybanUi: MetaRaybanUiController,
    private val meizuMyvuUi: MeizuMyvuUiController,
) : MainScreenController(host), DeviceManagerRegistry.Listener {
    private var pendingBatteryToast = false
    var batteryCallbackRegistered = false

    override fun onMetaRaybanStateChanged() {
        metaRaybanUi.updateMetaRaybanUiState()
    }

    override fun onMeizuMyvuStateChanged(state: MeizuMyvuManager.MeizuMyvuUiState) {
        if (state.relayReady) {
            meizuMyvuUi.pendingMeizuMyvuFailure = null
        }
        meizuMyvuUi.updateMeizuMyvuUiState()
    }

    override fun onSelectedGlassesStateChanged() {
        updateConnectionStatus(false)
    }

    override fun onMeizuMyvuFailure(failure: MeizuMyvuFailure) {
        meizuMyvuUi.pendingMeizuMyvuFailure = failure
        meizuMyvuUi.showPendingMeizuMyvuFailureIfNeeded()
    }

    override fun onEyevueStateChanged(eyevue: EyevueState) {
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
        if (connectionHost.isAiHijackEnabled) {
            connectionHost.handleAiWakeWordActivation("eyevue")
        }
    }

    override fun onTuneBudsStateChanged(tuneBuds: TuneBudsState) {
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
        glasses.selected().renderConnectionStatus(connected)
    }

    /** Refuses a HeyCyan SDK-only [feature] when the selected family cannot serve it. */
    fun rejectHeyCyanOnlyFeature(feature: String): Boolean {
        val rejection = glasses.selectedProfile().heyCyanSdkControlsRejection ?: return false
        Toast.makeText(
            activity,
            rejection.message(feature),
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
        val device = glasses.selected()
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
            state.withFamilyGating(
                family = device.profile,
                aiWakeWordRoute = AssistantPrefs.WakeWord.route[activity],
                isDebugBuild = BuildConfig.DEBUG,
                livePreview = device.dashboardLivePreview(),
            )
        }

        // Only poll battery for profiles that claim to support it.
        val battery = device.battery
        if (showBattery && battery?.pollsPeriodically == true) {
            connectionHost.startBatteryPolling()
        } else {
            connectionHost.stopBatteryPolling()
            updateBatteryText(null)
            battery?.refreshAfterSelection()
        }

        if (!showStorage) {
            binding.storageText.text = "--"
        }

        device.onSelectedForDashboard()
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
