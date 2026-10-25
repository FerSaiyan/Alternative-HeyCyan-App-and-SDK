package com.fersaiyan.cyanbridge.shared.glasses

import com.fersaiyan.cyanbridge.shared.ai.AgentProviderAvailability
import com.fersaiyan.cyanbridge.shared.devices.DeviceClass
import com.fersaiyan.cyanbridge.shared.devices.GlassesFamilyProfiles
import com.fersaiyan.cyanbridge.shared.devices.GlassesManagerGating
import com.fersaiyan.cyanbridge.shared.devices.GlassesManagerGating.Action
import com.fersaiyan.cyanbridge.shared.settings.AgentProviderType
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

    /**
     * The iOS host showed HeyCyan sections only while linked, the other families' sections while
     * linked (Meta and Meizu cards always), and the Eyevue panel alone. The shared gating keeps
     * that; Eyevue now also shows Android's device-info section.
     */
    @Test
    fun iosGatingKeepsTheSectionsOfTheFormerIosHost() {
        val otherGlasses = setOf(
            DeviceClass.TUNEBUDS, DeviceClass.MOYOUNG_W620, DeviceClass.META_RAYBAN,
            DeviceClass.MENTRA_LIVE, DeviceClass.MEIZU_MYVU,
        )
        DeviceClass.entries.forEach { deviceClass ->
            listOf(true, false).forEach { linked ->
                val heyCyan = linked && deviceClass == DeviceClass.HEY_CYAN
                val eyevue = linked && deviceClass == DeviceClass.EYEVUE
                val shown = linked || deviceClass == DeviceClass.META_RAYBAN || deviceClass == DeviceClass.MEIZU_MYVU
                val other = if (deviceClass in otherGlasses && shown) {
                    GlassesManagerGating.visibleActions(deviceClass)
                } else {
                    emptySet()
                }
                val state = sample.withIosFamilyGating(GlassesFamilyProfiles.forClass(deviceClass), linked)
                val flags = listOf(
                    state.showHeyCyanControls, state.showCaptureSettings, state.showAiWakeWordRouting,
                    state.showAdvancedControls, state.showAdvancedLocalAgent, state.showAdvancedDeviceInfo,
                    state.showAdvancedDeviceVolume, state.showAdvancedImageQuality, state.showAdvancedDeveloperTools,
                    state.showAdvancedOta, state.showMetaRaybanControls, state.showMeizuMyvuControls,
                    state.showTuneBudsControls, state.showEyevueControls,
                )
                val expected = listOf(
                    heyCyan, heyCyan, heyCyan,
                    heyCyan || eyevue || Action.ADVANCED_CONTROLS in other, false,
                    heyCyan || eyevue || Action.ADVANCED_DEVICE_INFO in other,
                    heyCyan, heyCyan || Action.ADVANCED_IMAGE_QUALITY in other, heyCyan,
                    heyCyan, Action.META_RAYBAN_CONTROLS in other, Action.MEIZU_MYVU_CONTROLS in other,
                    Action.TUNEBUDS_CONTROLS in other, eyevue,
                )
                assertEquals(expected, flags, "iOS sections for $deviceClass, linked=$linked")
                assertEquals(sample.deviceInfoLabel, state.deviceInfoLabel)
                assertEquals(false, state.wifiAdbDebug.isAvailable)
                assertEquals(LivePreviewUiState(), state.livePreview)
            }
        }
    }

    /**
     * iOS has no Tasker: no Tasker provider (a saved one runs as Local) and no Local Agent.
     * Android keeps all three providers; its dashboard gating is pinned by the test above.
     */
    @Test
    fun iosHasNoTaskerProviderOrLocalAgent() {
        assertEquals(AgentProviderType.entries, AgentProviderAvailability.available(supportsTasker = true))
        val ios = AgentProviderAvailability.available(supportsTasker = false)
        assertEquals(listOf(AgentProviderType.LOCAL_AGENT, AgentProviderType.PRO_SUBSCRIPTION), ios)
        assertEquals(AgentProviderType.LOCAL_AGENT, AgentProviderAvailability.effective(AgentProviderType.TASKER, ios))
        assertEquals(AgentProviderType.TASKER, AgentProviderAvailability.effective(AgentProviderType.TASKER, AgentProviderType.entries))

        val heyCyan = GlassesFamilyProfiles.forClass(DeviceClass.HEY_CYAN)
        assertEquals(false, sample.withIosFamilyGating(heyCyan, linked = true).showAdvancedLocalAgent)
    }
}
