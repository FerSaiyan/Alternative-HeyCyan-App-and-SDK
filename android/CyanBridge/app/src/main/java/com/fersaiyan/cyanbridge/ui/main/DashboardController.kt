package com.fersaiyan.cyanbridge.ui.main

import android.content.Intent
import android.widget.Toast
import com.fersaiyan.cyanbridge.BuildConfig
import com.fersaiyan.cyanbridge.ai.image.ExternalAssistantAutomationSetupActivity
import com.fersaiyan.cyanbridge.ai.image.ImageThumbnailQuality
import com.fersaiyan.cyanbridge.ai.live.GeminiLiveVisionPreferences
import com.fersaiyan.cyanbridge.ai.vision.ImageQuestionPreferences
import com.fersaiyan.cyanbridge.bridge.core.GlassesCapability
import com.fersaiyan.cyanbridge.localagent.LocalAgentController
import com.fersaiyan.cyanbridge.shared.devices.GlassesFamilyProfile
import com.fersaiyan.cyanbridge.shared.glasses.AiWakeWordRoute
import com.fersaiyan.cyanbridge.shared.glasses.FirmwarePatchRequestUiState
import com.fersaiyan.cyanbridge.shared.glasses.GlassesAssistantMode
import com.fersaiyan.cyanbridge.shared.glasses.GlassesDashboardAction
import com.fersaiyan.cyanbridge.shared.glasses.GlassesDashboardController
import com.fersaiyan.cyanbridge.shared.glasses.GlassesDashboardHost
import com.fersaiyan.cyanbridge.shared.glasses.GlassesDashboardUiState
import com.fersaiyan.cyanbridge.shared.glasses.GlassesDevice
import com.fersaiyan.cyanbridge.shared.glasses.GlassesSession
import com.fersaiyan.cyanbridge.shared.glasses.OtaFirmwareSource
import com.fersaiyan.cyanbridge.shared.navigation.AppDestination
import com.fersaiyan.cyanbridge.shared.plugins.NativePluginShortcutAction
import com.fersaiyan.cyanbridge.shared.prefs.AssistantPrefs
import com.fersaiyan.cyanbridge.shared.prefs.set
import com.fersaiyan.cyanbridge.ui.main.glasses.AndroidGlassesDevices

/**
 * Activity work that dashboard actions reach outside the device controllers: assistant mode,
 * media sync, OTA, live preview and Wi-Fi ADB debug.
 */
internal interface DashboardActionHost {
    fun selectPhoneAssistant()
    fun selectCustomAiProvider()
    fun onImageThumbnailQualitySelected(quality: ImageThumbnailQuality)
    fun startEyevueMediaSync()
    fun startTuneBudsMediaSync()
    fun startMoyoungW620MediaSync()
    fun stopEyevueMediaSync()
    fun stopTuneBudsMediaSync()
    fun stopMoyoungW620MediaSync()
    fun requestOtaFirmware(source: OtaFirmwareSource)
    fun submitFirmwarePatchRequest(request: FirmwarePatchRequestUiState, contactEmail: String)
    fun cancelOta()
    fun startEyevueLivePreview()
    fun startTuneBudsLivePreview()
    fun startLivePreview()
    fun stopLivePreview()

    /** Releases the live-preview session lease after an Eyevue preview stops. */
    fun releaseLivePreviewSession()
    fun startWifiAdbDebug()
    fun stopWifiAdbDebug()
}

/**
 * Android host of the shared [GlassesDashboardController]: the selected glasses family
 * ([AndroidGlassesDevices]) and the activity work behind the dashboard actions (legacy views,
 * preferences, activities, toasts and the [DashboardActionHost] coordinators).
 */
internal class DashboardController(
    host: MainScreenHost,
    private val actions: DashboardActionHost,
    private val glasses: AndroidGlassesDevices,
    private val transportPermissions: GlassesTransportPermissions,
    private val nativePlugins: NativePluginShortcutController,
    private val meetingCaptureUi: MeetingCaptureUiController,
    private val navigator: AppDestinationNavigator,
) : MainScreenController(host), GlassesDashboardHost {
    private val controller = GlassesDashboardController(this)

    fun handleDashboardAction(action: GlassesDashboardAction) = controller.handle(action)

    override val selectedDevice: GlassesDevice get() = glasses.selected()

    override var dashboard: GlassesDashboardUiState
        get() = dashboardState
        set(value) {
            dashboardState = value
        }

    override fun updateDashboard(transform: (GlassesDashboardUiState) -> GlassesDashboardUiState) =
        updateDashboardState(transform)

    override fun onBlockedByExclusiveSession(session: GlassesSession) {
        Toast.makeText(
            activity,
            "${session.label.replaceFirstChar { it.uppercase() }} is using the glasses connection.",
            Toast.LENGTH_SHORT,
        ).show()
    }

    /** The shared controller logged it; Android shows nothing more. */
    override fun onActionUnavailable(action: GlassesDashboardAction) = Unit

    override fun navigate(destination: AppDestination) = navigator.navigateToDestination(destination)

    override val meetingTimerLastIndex: Int get() = meetingCaptureUi.meetingTimerOptions.lastIndex

    override fun onMeetingTimerSelected(index: Int) = binding.spinnerMeetingTimer.setSelection(index)

    override fun startMeetingCapture() {
        binding.btnMeetingStart.performClick()
    }

    override fun stopMeetingCapture() {
        binding.btnMeetingStop.performClick()
    }

    override fun runNativePluginShortcut(action: NativePluginShortcutAction) =
        nativePlugins.runNativePluginShortcut(action)

    override fun selectAssistantMode(mode: GlassesAssistantMode) = when (mode) {
        GlassesAssistantMode.PHONE_ASSISTANT -> actions.selectPhoneAssistant()
        GlassesAssistantMode.CUSTOM_AI_PROVIDER -> actions.selectCustomAiProvider()
    }

    override fun saveAiWakeWordRoute(route: AiWakeWordRoute) {
        AssistantPrefs.WakeWord.route[activity] = route
    }

    override fun saveImageThumbnailQuality(sdkValue: Int): ImageThumbnailQuality {
        val quality = ImageQuestionPreferences.setThumbnailQuality(activity, sdkValue)
        actions.onImageThumbnailQualitySelected(quality)
        return quality
    }

    override fun saveGeminiLiveImageDelay(seconds: Int): Int =
        GeminiLiveVisionPreferences.setImageDelaySeconds(activity, seconds)

    override fun askVoiceQuestion() {
        binding.btnTestHijackVoice.performClick()
    }

    override fun askImageQuestion() {
        binding.btnTestHijackImage.performClick()
    }

    override fun openExternalImageAutomationDiagnostics() {
        activity.startActivity(Intent(activity, ExternalAssistantAutomationSetupActivity::class.java))
    }

    override fun onAdvancedToggled() {
        binding.btnToggleAdvanced.performClick()
    }

    override fun startLocalAgent() {
        LocalAgentController.requestStatus(activity)
    }

    override fun stopLocalAgent() {
        binding.btnAgentStop.performClick()
    }

    override fun runLocalAgentDemo() {
        binding.btnAgentDemo.performClick()
    }

    override fun startClassicBluetoothScan() {
        binding.btnBt.performClick()
    }

    override fun dumpOtaInfo() {
        binding.btnOtaInfo.performClick()
    }

    override fun testPullOta() {
        binding.btnPullOtaTest.performClick()
    }

    override fun requestOtaFirmware(source: OtaFirmwareSource) = actions.requestOtaFirmware(source)

    override fun submitFirmwarePatchRequest(request: FirmwarePatchRequestUiState, contactEmail: String) =
        actions.submitFirmwarePatchRequest(request, contactEmail)

    override fun cancelOta() = actions.cancelOta()

    override fun startWifiAdbDebug(family: GlassesFamilyProfile) {
        if (BuildConfig.DEBUG && family.supports(GlassesCapability.WIFI_ADB_DEBUG)) actions.startWifiAdbDebug()
    }

    override fun stopWifiAdbDebug(family: GlassesFamilyProfile) {
        if (BuildConfig.DEBUG && family.supports(GlassesCapability.WIFI_ADB_DEBUG)) actions.stopWifiAdbDebug()
    }

    override fun dismissTransportPermissionDialog() = transportPermissions.dismissTransportPermissionDialog()

    override fun requestTransportPermission() = transportPermissions.requestPendingTransportPermission()
}
