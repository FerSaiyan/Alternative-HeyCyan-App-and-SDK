package com.fersaiyan.cyanbridge.ui.main

import android.content.Intent
import android.util.Log
import android.widget.Toast
import com.fersaiyan.cyanbridge.BuildConfig
import com.fersaiyan.cyanbridge.ai.image.ExternalAssistantAutomationSetupActivity
import com.fersaiyan.cyanbridge.ai.image.ImageThumbnailQuality
import com.fersaiyan.cyanbridge.ai.live.GeminiLiveVisionPreferences
import com.fersaiyan.cyanbridge.ai.vision.ImageQuestionPreferences
import com.fersaiyan.cyanbridge.bridge.core.GlassesCapability
import com.fersaiyan.cyanbridge.devices.DeviceProfileStore
import com.fersaiyan.cyanbridge.glasses.GlassesSessionCoordinator
import com.fersaiyan.cyanbridge.localagent.LocalAgentController
import com.fersaiyan.cyanbridge.shared.devices.GlassesManagerGating
import com.fersaiyan.cyanbridge.shared.glasses.DashboardSessionPolicy
import com.fersaiyan.cyanbridge.shared.glasses.FirmwarePatchRequestUiState
import com.fersaiyan.cyanbridge.shared.glasses.GlassesAssistantMode
import com.fersaiyan.cyanbridge.shared.glasses.GlassesDashboardAction
import com.fersaiyan.cyanbridge.shared.glasses.OtaFirmwareSource
import com.fersaiyan.cyanbridge.shared.prefs.AssistantPrefs
import com.fersaiyan.cyanbridge.shared.prefs.set
import com.fersaiyan.cyanbridge.ui.MetaPairingActivity
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
 * Turns shared dashboard actions into calls on the selected glasses family's capabilities
 * ([AndroidGlassesDevices]), the main-screen controllers, or app-level work.
 */
internal class DashboardController(
    host: MainScreenHost,
    private val actions: DashboardActionHost,
    private val devices: DeviceManagerRegistry,
    private val glasses: AndroidGlassesDevices,
    private val transportPermissions: GlassesTransportPermissions,
    private val nativePlugins: NativePluginShortcutController,
    private val meetingCaptureUi: MeetingCaptureUiController,
    private val metaRaybanUi: MetaRaybanUiController,
    private val navigator: AppDestinationNavigator,
) : MainScreenController(host) {
    fun handleDashboardAction(action: GlassesDashboardAction) {
        if (isDashboardActionBlockedByExclusiveSession(action)) return
        val device = glasses.selected()
        val family = device.profile
        if (!GlassesManagerGating.isActionSupported(family, action)) {
            Log.i("Dashboard", "Ignoring unsupported ${family.deviceClass} dashboard action: $action")
            return
        }
        if (action == GlassesDashboardAction.StopLivePreview) {
            Log.i("LivePreview", "BUTTON TAP: Stop Live Preview")
        }
        // Device-side actions run on the selected family's capabilities.
        if (device.handle(action)) return
        when (action) {
            is GlassesDashboardAction.Navigate -> navigator.navigateToDestination(action.destination)
            is GlassesDashboardAction.SelectMeetingTimer -> {
                val index = action.index.coerceIn(0, meetingCaptureUi.meetingTimerOptions.lastIndex)
                binding.spinnerMeetingTimer.setSelection(index)
                updateDashboardState { state ->
                    state.copy(meeting = state.meeting.copy(timerIndex = index))
                }
            }
            GlassesDashboardAction.StartMeetingCapture -> binding.btnMeetingStart.performClick()
            GlassesDashboardAction.StopMeetingCapture -> binding.btnMeetingStop.performClick()
            is GlassesDashboardAction.RunNativePluginShortcut -> {
                nativePlugins.runNativePluginShortcut(action.action)
            }
            is GlassesDashboardAction.SelectAssistantMode -> when (action.mode) {
                GlassesAssistantMode.PHONE_ASSISTANT -> actions.selectPhoneAssistant()
                GlassesAssistantMode.CUSTOM_AI_PROVIDER -> actions.selectCustomAiProvider()
            }
            is GlassesDashboardAction.SetAiWakeWordRoute -> {
                if (family.supports(GlassesCapability.WAKE_WORD)) {
                    AssistantPrefs.WakeWord.route[activity] = action.route
                    updateDashboardState { state -> state.copy(aiWakeWordRoute = action.route) }
                }
            }
            is GlassesDashboardAction.SelectImageThumbnailQuality -> {
                if (!family.supports(GlassesCapability.IMAGE_QUALITY_SELECTION)) return
                val quality = ImageQuestionPreferences.setThumbnailQuality(activity, action.sdkValue)
                actions.onImageThumbnailQualitySelected(quality)
                updateDashboardState { state ->
                    state.copy(
                        imageThumbnailQualitySdkValue = quality.sdkValue,
                        imageThumbnailQualityLabel = quality.label,
                    )
                }
            }
            is GlassesDashboardAction.SetGeminiLiveImageDelay -> {
                val seconds = GeminiLiveVisionPreferences.setImageDelaySeconds(activity, action.seconds)
                updateDashboardState { state -> state.copy(geminiLiveImageDelaySeconds = seconds) }
            }
            GlassesDashboardAction.TestVoiceQuestion -> binding.btnTestHijackVoice.performClick()
            GlassesDashboardAction.TestImageQuestion -> binding.btnTestHijackImage.performClick()
            GlassesDashboardAction.OpenExternalImageAutomationDiagnostics -> {
                activity.startActivity(Intent(activity, ExternalAssistantAutomationSetupActivity::class.java))
            }
            GlassesDashboardAction.ToggleAdvanced -> {
                if (!dashboardState.showAdvancedControls) return
                binding.btnToggleAdvanced.performClick()
                updateDashboardState { state ->
                    state.copy(advancedExpanded = !state.advancedExpanded)
                }
            }
            GlassesDashboardAction.StartAgent -> LocalAgentController.requestStatus(activity)
            GlassesDashboardAction.StopAgent -> binding.btnAgentStop.performClick()
            GlassesDashboardAction.RunAgentDemo -> binding.btnAgentDemo.performClick()
            GlassesDashboardAction.StartClassicBluetoothScan -> binding.btnBt.performClick()
            GlassesDashboardAction.DumpOtaInfo -> binding.btnOtaInfo.performClick()
            GlassesDashboardAction.TestPullOta -> binding.btnPullOtaTest.performClick()
            is GlassesDashboardAction.RequestOtaFirmware -> actions.requestOtaFirmware(action.source)
            is GlassesDashboardAction.SubmitFirmwarePatchRequest -> {
                val request = dashboardState.firmwarePatchRequest ?: return
                if (request.isSubmitting) return
                dashboardState = dashboardState.copy(
                    firmwarePatchRequest = request.copy(
                        isSubmitting = true,
                        submissionError = null,
                    ),
                )
                actions.submitFirmwarePatchRequest(request, action.contactEmail)
            }
            GlassesDashboardAction.DismissFirmwarePatchRequest -> {
                if (dashboardState.firmwarePatchRequest?.isSubmitting != true) {
                    dashboardState = dashboardState.copy(firmwarePatchRequest = null)
                }
            }
            GlassesDashboardAction.CancelOta -> actions.cancelOta()
            GlassesDashboardAction.RequestStartWifiAdbDebug -> {
                if (BuildConfig.DEBUG && family.supports(GlassesCapability.WIFI_ADB_DEBUG)) actions.startWifiAdbDebug()
            }
            GlassesDashboardAction.StopWifiAdbDebug -> {
                if (BuildConfig.DEBUG && family.supports(GlassesCapability.WIFI_ADB_DEBUG)) actions.stopWifiAdbDebug()
            }
            GlassesDashboardAction.MetaRegister -> binding.btnMetaRegister.performClick()
            GlassesDashboardAction.MetaOpenPairing ->
                activity.startActivity(Intent(activity, MetaPairingActivity::class.java))
            GlassesDashboardAction.MetaOpenMetaAi -> metaRaybanUi.openMetaAiAppOrStore()
            GlassesDashboardAction.MetaUnregister -> binding.btnMetaUnregister.performClick()
            GlassesDashboardAction.MetaStartSession -> binding.btnMetaSessionStart.performClick()
            GlassesDashboardAction.MetaStopSession -> binding.btnMetaSessionStop.performClick()
            GlassesDashboardAction.MetaStartStream -> binding.btnMetaStreamStart.performClick()
            GlassesDashboardAction.MetaStopStream -> binding.btnMetaStreamStop.performClick()
            GlassesDashboardAction.MetaCapturePhoto -> binding.btnMetaCapturePhoto.performClick()
            GlassesDashboardAction.MetaViewPhoto -> binding.btnMetaViewPhoto.performClick()
            GlassesDashboardAction.MetaStartDisplay -> binding.btnMetaDisplayStart.performClick()
            GlassesDashboardAction.MetaStopDisplay -> binding.btnMetaDisplayStop.performClick()
            GlassesDashboardAction.MetaSendDiagnostics -> metaRaybanUi.showMetaDiagnostics()
            GlassesDashboardAction.MeizuConnect -> {
                DeviceProfileStore.loadLastSelected(activity)?.macAddress?.let {
                    devices.getOrCreateMeizuMyvuManager().connect(it, activity, userInitiated = true)
                } ?: Toast.makeText(activity, "Select MYVU glasses from Scan first", Toast.LENGTH_LONG).show()
            }
            GlassesDashboardAction.MeizuDisconnect -> devices.getOrCreateMeizuMyvuManager().disconnect()
            GlassesDashboardAction.MeizuSendTestNotification -> devices.getOrCreateMeizuMyvuManager().sendTestNotification()
            GlassesDashboardAction.MeizuShowTestTeleprompter -> devices.getOrCreateMeizuMyvuManager().showTeleprompter(
                "CyanBridge",
                "MYVU display connected\n\nNative voice plugins can now use the MYVU headset microphone and display bridge.",
            )
            GlassesDashboardAction.MeizuSyncClock -> devices.getOrCreateMeizuMyvuManager().syncClock()
            GlassesDashboardAction.MeizuSetComfortBrightness -> devices.getOrCreateMeizuMyvuManager().setBrightness(70)
            GlassesDashboardAction.DismissTransportPermissionDialog ->
                transportPermissions.dismissTransportPermissionDialog()
            GlassesDashboardAction.RequestTransportPermission -> transportPermissions.requestPendingTransportPermission()
            // Device-side actions the selected family has no capability for.
            GlassesDashboardAction.Scan,
            GlassesDashboardAction.Reconnect,
            GlassesDashboardAction.Disconnect,
            GlassesDashboardAction.CapturePhoto,
            GlassesDashboardAction.ToggleVideo,
            GlassesDashboardAction.StartAudioRecording,
            GlassesDashboardAction.RequestMediaCount,
            GlassesDashboardAction.StartSync,
            GlassesDashboardAction.StopSync,
            GlassesDashboardAction.StartLivePreview,
            GlassesDashboardAction.StopLivePreview,
            GlassesDashboardAction.RequestBattery,
            GlassesDashboardAction.RequestVersion,
            GlassesDashboardAction.SyncTime,
            GlassesDashboardAction.RequestVolume,
            GlassesDashboardAction.AddDeviceListener,
            GlassesDashboardAction.RefreshRecordingSettings,
            is GlassesDashboardAction.SetVideoRecordingDuration,
            is GlassesDashboardAction.SetAudioRecordingDuration,
            is GlassesDashboardAction.SetWearingDetection ->
                Log.i("Dashboard", "${family.deviceClass} has no capability for $action")
        }
    }

    private fun isDashboardActionBlockedByExclusiveSession(action: GlassesDashboardAction): Boolean {
        if (DashboardSessionPolicy.ignoresExclusiveSession(action)) {
            return false
        }
        val activeSession = GlassesSessionCoordinator.currentSession() ?: return false
        val isAllowed = DashboardSessionPolicy.isAllowedDuring(action, activeSession)
        if (isAllowed) return false

        Toast.makeText(
            activity,
            "${activeSession.label.replaceFirstChar { it.uppercase() }} is using the glasses connection.",
            Toast.LENGTH_SHORT,
        ).show()
        return true
    }
}
