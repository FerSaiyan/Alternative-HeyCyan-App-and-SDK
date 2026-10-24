package com.fersaiyan.cyanbridge.shared.glasses

import com.fersaiyan.cyanbridge.ai.image.ImageThumbnailQuality
import com.fersaiyan.cyanbridge.bridge.core.CameraCapture
import com.fersaiyan.cyanbridge.bridge.core.GlassesConnectionControl
import com.fersaiyan.cyanbridge.bridge.core.VolumeReporting
import com.fersaiyan.cyanbridge.shared.devices.DeviceClass
import com.fersaiyan.cyanbridge.shared.devices.GlassesFamilyProfile
import com.fersaiyan.cyanbridge.shared.devices.GlassesFamilyProfiles
import com.fersaiyan.cyanbridge.shared.devices.GlassesManagerGating
import com.fersaiyan.cyanbridge.shared.navigation.AppDestination
import com.fersaiyan.cyanbridge.shared.plugins.NativePluginShortcutAction
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the dashboard routing Android's main screen had before it moved to :shared: session
 * policy, then family gating, then the device's capabilities or vendor panel, then the host.
 * Policy and gating tables live in DashboardSessionPolicyTest and GlassesManagerGatingMatrixTest.
 */
class GlassesDashboardControllerTest {
    private val calls = mutableListOf<String>()
    private var session: GlassesSession? = null

    /** A family with a camera and volume reading; its vendor panel accepts its own actions. */
    private inner class FakeDevice(override val profile: GlassesFamilyProfile) : GlassesDevice {
        override val connection = object : GlassesConnectionControl {
            override fun scan() { calls += "device:scan" }
            override fun reconnect() { calls += "device:reconnect" }
            override fun disconnect() { calls += "device:disconnect" }
        }
        override val camera = CameraCapture { calls += "device:photo" }
        override val volume = VolumeReporting { calls += "device:volume" }

        override fun handlePanelAction(action: GlassesDashboardAction): Boolean {
            if (GlassesManagerGating.requiredPanel(action) != profile.controlPanel) return false
            calls += "panel:${action.name()}"
            return true
        }
    }

    private inner class FakeHost : GlassesDashboardHost {
        var device: GlassesDevice = FakeDevice(GlassesFamilyProfiles.heyCyan)
        override val selectedDevice: GlassesDevice get() = device
        override var dashboard = GlassesDashboardUiState()
        override fun updateDashboard(transform: (GlassesDashboardUiState) -> GlassesDashboardUiState) {
            dashboard = transform(dashboard)
        }
        override fun onBlockedByExclusiveSession(session: GlassesSession) { calls += "blocked:$session" }
        override fun onActionUnavailable(action: GlassesDashboardAction) { calls += "unavailable:${action.name()}" }
        override fun navigate(destination: AppDestination) { calls += "navigate:$destination" }
        override val meetingTimerLastIndex = 3
        override fun onMeetingTimerSelected(index: Int) { calls += "timer:$index" }
        override fun startMeetingCapture() { calls += "meeting:start" }
        override fun stopMeetingCapture() { calls += "meeting:stop" }
        override fun runNativePluginShortcut(action: NativePluginShortcutAction) { calls += "plugin:$action" }
        override fun selectAssistantMode(mode: GlassesAssistantMode) { calls += "mode:$mode" }
        override fun saveAiWakeWordRoute(route: AiWakeWordRoute) { calls += "route:$route" }
        override fun saveImageThumbnailQuality(sdkValue: Int): ImageThumbnailQuality {
            calls += "quality:$sdkValue"
            return ImageThumbnailQuality.entries.first { it.sdkValue == sdkValue }
        }
        override fun askVoiceQuestion() { calls += "ask:voice" }
        override fun askImageQuestion() { calls += "ask:image" }
        override fun onAdvancedToggled() { calls += "advanced" }
        override fun requestOtaFirmware(source: OtaFirmwareSource) { calls += "ota:$source" }
        override fun submitFirmwarePatchRequest(request: FirmwarePatchRequestUiState, contactEmail: String) {
            calls += "patch:$contactEmail"
        }
        override fun startWifiAdbDebug(family: GlassesFamilyProfile) { calls += "wifiAdb:${family.deviceClass}" }
    }

    private val host = FakeHost()
    private val controller = GlassesDashboardController(host) { session }

    private fun dispatch(deviceClass: DeviceClass, action: GlassesDashboardAction): List<String> {
        calls.clear()
        host.device = FakeDevice(GlassesFamilyProfiles.forClass(deviceClass))
        controller.handle(action)
        return calls.toList()
    }

    private fun GlassesDashboardAction.name(): String = this::class.simpleName!!

    @Test
    fun actionsReachTheDeviceItsPanelOrTheHost() {
        val rows: List<Triple<DeviceClass, GlassesDashboardAction, List<String>>> = listOf(
            Triple(DeviceClass.HEY_CYAN, GlassesDashboardAction.Scan, listOf("device:scan")),
            Triple(DeviceClass.HEY_CYAN, GlassesDashboardAction.CapturePhoto, listOf("device:photo")),
            Triple(DeviceClass.EYEVUE, GlassesDashboardAction.RequestVolume, listOf("device:volume")),
            Triple(DeviceClass.HEY_CYAN, GlassesDashboardAction.StartLivePreview, listOf("unavailable:StartLivePreview")),
            // TuneBuds limits its dashboard to declared capabilities: no volume reading.
            Triple(DeviceClass.TUNEBUDS, GlassesDashboardAction.RequestVolume, listOf("unavailable:RequestVolume")),
            Triple(DeviceClass.TUNEBUDS, GlassesDashboardAction.MetaRegister, listOf("unavailable:MetaRegister")),
            Triple(DeviceClass.META_RAYBAN, GlassesDashboardAction.MetaRegister, listOf("panel:MetaRegister")),
            Triple(DeviceClass.MEIZU_MYVU, GlassesDashboardAction.MeizuSyncClock, listOf("panel:MeizuSyncClock")),
            Triple(DeviceClass.HEY_CYAN, GlassesDashboardAction.MeizuSyncClock, listOf("unavailable:MeizuSyncClock")),
            Triple(DeviceClass.UNKNOWN, GlassesDashboardAction.Navigate(AppDestination.SETTINGS), listOf("navigate:SETTINGS")),
            Triple(DeviceClass.UNKNOWN, GlassesDashboardAction.TestImageQuestion, listOf("ask:image")),
            Triple(DeviceClass.META_RAYBAN, GlassesDashboardAction.TestVoiceQuestion, listOf("ask:voice")),
            Triple(DeviceClass.EYEVUE, GlassesDashboardAction.SetAiWakeWordRoute(AiWakeWordRoute.IMAGE_QUESTION), listOf("route:IMAGE_QUESTION")),
            Triple(DeviceClass.MOYOUNG_W620, GlassesDashboardAction.SetAiWakeWordRoute(AiWakeWordRoute.IMAGE_QUESTION), emptyList()),
            Triple(DeviceClass.TUNEBUDS, GlassesDashboardAction.SelectImageThumbnailQuality(5), listOf("quality:5")),
            Triple(DeviceClass.EYEVUE, GlassesDashboardAction.SelectImageThumbnailQuality(5), emptyList()),
            Triple(DeviceClass.HEY_CYAN, GlassesDashboardAction.RequestStartWifiAdbDebug, listOf("wifiAdb:HEY_CYAN")),
            Triple(
                DeviceClass.HEY_CYAN,
                GlassesDashboardAction.RequestOtaFirmware(OtaFirmwareSource.PERSONAL_FILE),
                listOf("ota:PERSONAL_FILE"),
            ),
            Triple(DeviceClass.TUNEBUDS, GlassesDashboardAction.CancelOta, listOf("unavailable:CancelOta")),
            // Features a host does not implement report themselves unavailable.
            Triple(DeviceClass.HEY_CYAN, GlassesDashboardAction.SetGeminiLiveImageDelay(3), listOf("unavailable:SetGeminiLiveImageDelay")),
            Triple(DeviceClass.HEY_CYAN, GlassesDashboardAction.StartAgent, listOf("unavailable:StartAgent")),
        )
        rows.forEach { (deviceClass, action, expected) ->
            assertEquals(expected, dispatch(deviceClass, action), "$deviceClass $action")
        }
    }

    @Test
    fun anExclusiveSessionBlocksAllButItsOwnStop() {
        session = GlassesSession.OTA
        assertEquals(listOf("blocked:OTA"), dispatch(DeviceClass.HEY_CYAN, GlassesDashboardAction.CapturePhoto))
        assertEquals(
            listOf("route:VOICE_QUESTION"),
            dispatch(DeviceClass.HEY_CYAN, GlassesDashboardAction.SetAiWakeWordRoute(AiWakeWordRoute.VOICE_QUESTION)),
        )
        session = GlassesSession.META_CAMERA
        assertEquals(listOf("panel:MetaStopSession"), dispatch(DeviceClass.META_RAYBAN, GlassesDashboardAction.MetaStopSession))
    }

    @Test
    fun dashboardStateFollowsTheAction() {
        dispatch(DeviceClass.HEY_CYAN, GlassesDashboardAction.SelectMeetingTimer(9))
        assertEquals(listOf("timer:3"), calls)
        assertEquals(3, host.dashboard.meeting.timerIndex)

        dispatch(DeviceClass.TUNEBUDS, GlassesDashboardAction.SelectImageThumbnailQuality(5))
        assertEquals(5 to "Detailed", host.dashboard.imageThumbnailQualitySdkValue to host.dashboard.imageThumbnailQualityLabel)

        // The Advanced card only toggles while it is shown.
        assertEquals(emptyList<String>(), dispatch(DeviceClass.HEY_CYAN, GlassesDashboardAction.ToggleAdvanced))
        host.dashboard = host.dashboard.copy(showAdvancedControls = true)
        assertEquals(listOf("advanced"), dispatch(DeviceClass.HEY_CYAN, GlassesDashboardAction.ToggleAdvanced))
        assertEquals(true, host.dashboard.advancedExpanded)

        // A patch request is submitted once, and cannot be dismissed while it is submitting.
        val request = FirmwarePatchRequestUiState(
            source = OtaFirmwareSource.STEALTH_CATALOG,
            target = OtaTargetSelection.JIELI_BLE,
            targetHardwareVersion = "hw",
            targetFirmwareVersion = "fw",
            wifiHardwareVersion = "",
            wifiFirmwareVersion = "",
            bleHardwareVersion = "hw",
            bleFirmwareVersion = "fw",
            relayMessage = "",
        )
        host.dashboard = host.dashboard.copy(firmwarePatchRequest = request)
        val submit = GlassesDashboardAction.SubmitFirmwarePatchRequest("user@example.com")
        assertEquals(listOf("patch:user@example.com"), dispatch(DeviceClass.HEY_CYAN, submit))
        assertEquals(emptyList<String>(), dispatch(DeviceClass.HEY_CYAN, submit))
        dispatch(DeviceClass.HEY_CYAN, GlassesDashboardAction.DismissFirmwarePatchRequest)
        assertEquals(true, host.dashboard.firmwarePatchRequest?.isSubmitting)
        host.dashboard = host.dashboard.copy(firmwarePatchRequest = request)
        dispatch(DeviceClass.HEY_CYAN, GlassesDashboardAction.DismissFirmwarePatchRequest)
        assertEquals(null, host.dashboard.firmwarePatchRequest)
    }
}
