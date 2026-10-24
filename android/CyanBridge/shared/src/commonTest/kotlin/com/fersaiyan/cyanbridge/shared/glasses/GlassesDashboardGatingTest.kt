package com.fersaiyan.cyanbridge.shared.glasses

import com.fersaiyan.cyanbridge.shared.devices.DeviceClass
import com.fersaiyan.cyanbridge.shared.devices.GlassesFamilyProfiles
import com.fersaiyan.cyanbridge.shared.devices.GlassesManagerGating
import com.fersaiyan.cyanbridge.shared.devices.GlassesManagerGating.Action
import kotlin.test.Test
import kotlin.test.assertEquals

/** The dashboard state each device class produces, derived from the pinned gating matrix. */
class GlassesDashboardGatingTest {
    private val sample = GlassesDashboardUiState(
        storageLabel = "12 photos",
        deviceInfoLabel = "Model: X",
        showMediaSync = false,
        firmwarePatchRequest = FirmwarePatchRequestUiState(
            source = OtaFirmwareSource.STEALTH_CATALOG,
            target = OtaTargetSelection.V821_WIFI,
            targetHardwareVersion = "h",
            targetFirmwareVersion = "f",
            wifiHardwareVersion = "wh",
            wifiFirmwareVersion = "wf",
            bleHardwareVersion = "bh",
            bleFirmwareVersion = "bf",
            relayMessage = "m",
        ),
        wifiAdbDebug = WifiAdbDebugUiState(isAvailable = true, stateLabel = "Idle"),
        livePreview = LivePreviewUiState(isAvailable = true, stateLabel = "stale"),
    )
    private val live = LivePreviewUiState(isAvailable = true, stateLabel = "Playing", isPlaying = true)

    private fun gate(deviceClass: DeviceClass, debug: Boolean = true) = sample.withFamilyGating(
        family = GlassesFamilyProfiles.forClass(deviceClass),
        aiWakeWordRoute = AiWakeWordRoute.IMAGE_QUESTION,
        isDebugBuild = debug,
        livePreview = live,
    )

    @Test
    fun everyDeviceClassMapsTheGatingMatrixOntoTheDashboardState() {
        DeviceClass.entries.forEach { deviceClass ->
            val visible = GlassesManagerGating.visibleActions(deviceClass)
            val state = gate(deviceClass)
            val expected = sample.copy(
                showHeyCyanControls = Action.HEY_CYAN_EXTRAS in visible,
                showEyevueControls = Action.EYEVUE_CONTROLS in visible,
                showTuneBudsControls = Action.TUNEBUDS_CONTROLS in visible,
                showCaptureSettings = Action.CAPTURE_SETTINGS in visible,
                showMediaSync = true,
                showAiWakeWordRouting = Action.AI_WAKE_WORD_ROUTING in visible,
                showAdvancedControls = Action.ADVANCED_CONTROLS in visible,
                showAdvancedLocalAgent = false,
                showAdvancedDeviceInfo = Action.ADVANCED_DEVICE_INFO in visible,
                showAdvancedDeviceVolume = Action.ADVANCED_DEVICE_VOLUME in visible,
                showAdvancedImageQuality = Action.ADVANCED_IMAGE_QUALITY in visible,
                showAdvancedDeveloperTools = Action.ADVANCED_DEVELOPER_TOOLS in visible,
                showAdvancedOta = Action.ADVANCED_OTA in visible,
                deviceInfoLabel = sample.deviceInfoLabel.takeIf { deviceClass == DeviceClass.TUNEBUDS },
                firmwarePatchRequest = sample.firmwarePatchRequest
                    .takeIf { deviceClass == DeviceClass.HEY_CYAN || deviceClass == DeviceClass.EYEVUE },
                aiWakeWordRoute = AiWakeWordRoute.IMAGE_QUESTION,
                showMetaRaybanControls = deviceClass == DeviceClass.META_RAYBAN,
                showMeizuMyvuControls = deviceClass == DeviceClass.MEIZU_MYVU,
                showBattery = Action.STATUS_BATTERY in visible,
                showStorage = Action.STATUS_STORAGE in visible,
                storageLabel = if (Action.STATUS_STORAGE in visible) sample.storageLabel else "--",
                wifiAdbDebug = sample.wifiAdbDebug.copy(isAvailable = deviceClass == DeviceClass.HEY_CYAN),
                livePreview = if (
                    deviceClass == DeviceClass.HEY_CYAN ||
                    deviceClass == DeviceClass.EYEVUE ||
                    deviceClass == DeviceClass.TUNEBUDS
                ) {
                    live
                } else {
                    LivePreviewUiState()
                },
            )
            assertEquals(expected, state, "dashboard state for $deviceClass")
        }
    }

    @Test
    fun wifiAdbDebugIsHiddenInReleaseBuilds() {
        DeviceClass.entries.forEach { assertEquals(false, gate(it, debug = false).wifiAdbDebug.isAvailable) }
    }

    @Test
    fun missingLivePreviewFallsBackToTheIdleState() {
        val state = sample.withFamilyGating(
            family = GlassesFamilyProfiles.heyCyan,
            aiWakeWordRoute = AiWakeWordRoute.VOICE_QUESTION,
            isDebugBuild = false,
            livePreview = null,
        )
        assertEquals(LivePreviewUiState(), state.livePreview)
    }
}
