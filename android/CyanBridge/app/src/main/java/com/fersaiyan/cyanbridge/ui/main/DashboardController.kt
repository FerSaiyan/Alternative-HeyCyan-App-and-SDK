package com.fersaiyan.cyanbridge.ui.main

import android.content.Intent
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.fersaiyan.cyanbridge.BuildConfig
import com.fersaiyan.cyanbridge.ai.AiWakeWordPreferences
import com.fersaiyan.cyanbridge.ai.image.ExternalAssistantAutomationSetupActivity
import com.fersaiyan.cyanbridge.ai.image.ImageThumbnailQuality
import com.fersaiyan.cyanbridge.ai.live.GeminiLiveVisionPreferences
import com.fersaiyan.cyanbridge.ai.vision.ImageQuestionPreferences
import com.fersaiyan.cyanbridge.devices.DeviceProfileStore
import com.fersaiyan.cyanbridge.devices.tunebuds.isSupportedForTuneBudsDashboard
import com.fersaiyan.cyanbridge.glasses.GlassesSessionCoordinator
import com.fersaiyan.cyanbridge.localagent.LocalAgentController
import com.fersaiyan.cyanbridge.shared.glasses.FirmwarePatchRequestUiState
import com.fersaiyan.cyanbridge.shared.glasses.GlassesAssistantMode
import com.fersaiyan.cyanbridge.shared.glasses.GlassesDashboardAction
import com.fersaiyan.cyanbridge.shared.glasses.OtaFirmwareSource
import com.fersaiyan.cyanbridge.ui.AutoPairManager
import com.fersaiyan.cyanbridge.ui.DeviceBindActivity
import com.fersaiyan.cyanbridge.ui.MetaPairingActivity
import com.fersaiyan.cyanbridge.ui.startKtxActivity
import kotlinx.coroutines.launch

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
 * Turns shared dashboard actions into calls on the selected device's manager, the main-screen
 * controllers, or the legacy HeyCyan controls (via their hidden buttons).
 */
internal class DashboardController(
    host: MainScreenHost,
    private val actions: DashboardActionHost,
    private val devices: DeviceManagerRegistry,
    private val connection: DeviceConnectionCoordinator,
    private val heyCyanSettings: HeyCyanSettingsController,
    private val transportPermissions: GlassesTransportPermissions,
    private val nativePlugins: NativePluginShortcutController,
    private val meetingCaptureUi: MeetingCaptureUiController,
    private val metaRaybanUi: MetaRaybanUiController,
    private val navigator: AppDestinationNavigator,
) : MainScreenController(host) {
    fun handleDashboardAction(action: GlassesDashboardAction) {
        if (isDashboardActionBlockedByExclusiveSession(action)) return
        if (devices.isTuneBudsSelected() && !action.isSupportedForTuneBudsDashboard()) {
            Log.i("Dashboard", "Ignoring unsupported TuneBuds dashboard action: $action")
            return
        }
        when (action) {
            is GlassesDashboardAction.Navigate -> navigator.navigateToDestination(action.destination)
            GlassesDashboardAction.Scan -> {
                if (devices.isMoyoungW620Selected()) {
                    activity.startKtxActivity<DeviceBindActivity>()
                } else if (devices.isEyevueSelected()) {
                    activity.startKtxActivity<DeviceBindActivity>()
                } else if (devices.isTuneBudsSelected()) {
                    activity.startKtxActivity<DeviceBindActivity>()
                } else if (devices.isMeizuMyvuSelected()) {
                    activity.startKtxActivity<DeviceBindActivity>()
                } else if (devices.isMetaRaybanSelected()) {
                    activity.startKtxActivity<DeviceBindActivity>()
                } else {
                    binding.btnScan.performClick()
                }
            }
            GlassesDashboardAction.Reconnect -> {
                if (devices.isMoyoungW620Selected()) {
                    DeviceProfileStore.loadLastSelected(activity)?.let { profile ->
                        devices.getOrCreateMoyoungW620Manager().connect(profile.macAddress, profile.advertisedName)
                    } ?: activity.startKtxActivity<DeviceBindActivity>()
                } else if (devices.isEyevueSelected()) {
                    DeviceProfileStore.loadLastSelected(activity)?.let { profile ->
                        devices.getOrCreateEyevueManager().connect(profile.macAddress, profile.advertisedName)
                    } ?: activity.startKtxActivity<DeviceBindActivity>()
                } else if (devices.isTuneBudsSelected()) {
                    DeviceProfileStore.loadLastSelected(activity)?.let { profile ->
                        devices.getOrCreateTuneBudsManager().connect(profile.macAddress, profile.advertisedName)
                    } ?: activity.startKtxActivity<DeviceBindActivity>()
                } else if (devices.isMeizuMyvuSelected()) {
                    DeviceProfileStore.loadLastSelected(activity)?.macAddress?.let {
                        devices.getOrCreateMeizuMyvuManager().connect(it, activity, userInitiated = true)
                    } ?: activity.startKtxActivity<DeviceBindActivity>()
                } else if (devices.isMetaRaybanSelected()) {
                    activity.startActivity(Intent(activity, MetaPairingActivity::class.java))
                } else {
                    binding.btnConnect.performClick()
                }
            }
            GlassesDashboardAction.Disconnect -> {
                if (devices.isMoyoungW620Selected()) {
                    AutoPairManager.setAutoReconnectSuppressed(true, reason = "user_disconnect_button")
                    devices.getOrCreateMoyoungW620Manager().disconnect()
                } else if (devices.isEyevueSelected()) {
                    devices.getOrCreateEyevueManager().disconnect()
                } else if (devices.isTuneBudsSelected()) {
                    AutoPairManager.setAutoReconnectSuppressed(true, reason = "user_disconnect_button")
                    devices.getOrCreateTuneBudsManager().disconnect()
                } else if (devices.isMeizuMyvuSelected()) {
                    devices.getOrCreateMeizuMyvuManager().disconnect()
                } else if (devices.isMetaRaybanSelected()) {
                    devices.metaRaybanManager?.stopSession()
                    connection.updateConnectionStatus(false)
                } else {
                    binding.btnDisconnect.performClick()
                }
            }
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
                if (devices.isHeyCyanOrEyevueSelected()) {
                    AiWakeWordPreferences.setRoute(activity, action.route)
                    updateDashboardState { state -> state.copy(aiWakeWordRoute = action.route) }
                }
            }
            is GlassesDashboardAction.SelectImageThumbnailQuality -> {
                if (!devices.isHeyCyanSelected() && !devices.isTuneBudsSelected()) return
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
            GlassesDashboardAction.CapturePhoto -> if (devices.isEyevueSelected()) {
                devices.getOrCreateEyevueManager().takeManualPhoto()
            } else if (devices.isTuneBudsSelected()) {
                devices.getOrCreateTuneBudsManager().takePhoto()
            } else {
                binding.btnCamera.performClick()
            }
            GlassesDashboardAction.ToggleVideo -> if (devices.isEyevueSelected()) {
                devices.getOrCreateEyevueManager().toggleVideo()
            } else if (devices.isTuneBudsSelected()) {
                devices.getOrCreateTuneBudsManager().toggleVideo()
            } else {
                binding.btnVideo.performClick()
            }
            GlassesDashboardAction.StartAudioRecording -> if (devices.isEyevueSelected()) {
                devices.getOrCreateEyevueManager().toggleAudio()
            } else if (devices.isTuneBudsSelected()) {
                devices.getOrCreateTuneBudsManager().toggleAudio()
            } else {
                binding.btnRecord.performClick()
            }
            GlassesDashboardAction.RequestMediaCount -> if (devices.isEyevueSelected()) {
                Toast.makeText(activity, "Requesting media count…", Toast.LENGTH_SHORT).show()
                activity.lifecycleScope.launch {
                    val count = try {
                        devices.getOrCreateEyevueManager().awaitMediaCount()
                    } catch (_: Exception) {
                        null
                    }
                    Toast.makeText(
                        activity,
                        if (count != null) "Media count: $count" else "Eyevue did not report a media count",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            } else if (devices.isTuneBudsSelected()) {
                devices.getOrCreateTuneBudsManager().requestMediaCount()
            } else if (devices.isMoyoungW620Selected()) {
                devices.getOrCreateMoyoungW620Manager().requestMediaCount()
            } else {
                binding.btnMediaCount.performClick()
            }
            GlassesDashboardAction.StartSync -> if (devices.isEyevueSelected()) {
                actions.startEyevueMediaSync()
            } else if (devices.isTuneBudsSelected()) {
                actions.startTuneBudsMediaSync()
            } else if (devices.isMoyoungW620Selected()) {
                actions.startMoyoungW620MediaSync()
            } else {
                binding.btnDataDownload.performClick()
            }
            GlassesDashboardAction.StopSync -> if (devices.isEyevueSelected()) {
                actions.stopEyevueMediaSync()
            } else if (devices.isTuneBudsSelected()) {
                actions.stopTuneBudsMediaSync()
            } else if (devices.isMoyoungW620Selected()) {
                actions.stopMoyoungW620MediaSync()
            } else {
                binding.btnTransferStop.performClick()
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
            GlassesDashboardAction.RequestBattery -> if (devices.isEyevueSelected()) {
                Toast.makeText(activity, "Requesting battery level…", Toast.LENGTH_SHORT).show()
                activity.lifecycleScope.launch {
                    val percent = try {
                        devices.getOrCreateEyevueManager().awaitBatteryPercent()
                    } catch (_: Exception) {
                        null
                    }
                    Toast.makeText(
                        activity,
                        if (percent != null) "Eyevue battery: $percent%" else "Eyevue did not report battery",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            } else if (devices.isTuneBudsSelected()) {
                devices.getOrCreateTuneBudsManager().requestBattery()
            } else if (devices.isMoyoungW620Selected()) {
                devices.getOrCreateMoyoungW620Manager().requestBattery()
            } else {
                binding.btnBattery.performClick()
            }
            GlassesDashboardAction.RequestVersion -> if (devices.isEyevueSelected()) {
                devices.getOrCreateEyevueManager().requestDeviceInfo()
            } else if (devices.isTuneBudsSelected()) {
                devices.getOrCreateTuneBudsManager().requestVersion()
            } else {
                binding.btnVersion.performClick()
            }
            GlassesDashboardAction.SyncTime -> if (devices.isEyevueSelected()) {
                devices.getOrCreateEyevueManager().syncTime()
            } else if (devices.isTuneBudsSelected()) {
                devices.getOrCreateTuneBudsManager().syncTime()
            } else {
                binding.btnSetTime.performClick()
            }
            GlassesDashboardAction.RequestVolume -> if (devices.isEyevueSelected()) {
                devices.getOrCreateEyevueManager().requestVolume()
            } else {
                binding.btnVolume.performClick()
            }
            GlassesDashboardAction.AddDeviceListener -> if (devices.isEyevueSelected()) {
                devices.getOrCreateEyevueManager().requestSupportFunction()
            } else {
                binding.btnAddListener.performClick()
            }
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
            GlassesDashboardAction.StartLivePreview -> if (devices.isEyevueSelected()) {
                actions.startEyevueLivePreview()
            } else if (devices.isTuneBudsSelected()) {
                actions.startTuneBudsLivePreview()
            } else if (devices.isHeyCyanSelected()) {
                actions.startLivePreview()
            }
            GlassesDashboardAction.StopLivePreview -> {
                Log.i("LivePreview", "BUTTON TAP: Stop Live Preview")
                if (devices.isEyevueSelected()) {
                    if (devices.eyevueLivePreviewManager?.isActive == true) {
                        devices.getOrCreateEyevueLivePreviewManager().stop()
                    }
                    com.fersaiyan.cyanbridge.devices.eyevue.EyevueLiveForegroundService.stop(activity)
                    actions.releaseLivePreviewSession()
                } else if (devices.isTuneBudsSelected()) {
                    devices.tuneBudsLivePreviewManager?.stop()
                    // Release the session and foreground service only after cleanup.
                } else {
                    actions.stopLivePreview()
                }
            }
            GlassesDashboardAction.RequestStartWifiAdbDebug -> {
                if (BuildConfig.DEBUG && devices.isHeyCyanSelected()) actions.startWifiAdbDebug()
            }
            GlassesDashboardAction.StopWifiAdbDebug -> {
                if (BuildConfig.DEBUG && devices.isHeyCyanSelected()) actions.stopWifiAdbDebug()
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
            GlassesDashboardAction.RefreshRecordingSettings -> if (devices.isEyevueSelected()) {
                devices.getOrCreateEyevueManager().requestSupportFunction()
            } else {
                heyCyanSettings.refreshHeyCyanRecordingSettings()
            }
            is GlassesDashboardAction.SetVideoRecordingDuration -> if (devices.isEyevueSelected()) {
                devices.getOrCreateEyevueManager().setRecordingDuration(action.seconds)
            } else if (devices.isHeyCyanSelected()) {
                heyCyanSettings.setHeyCyanRecordingDuration(isAudio = false, seconds = action.seconds)
            }
            is GlassesDashboardAction.SetAudioRecordingDuration -> if (devices.isEyevueSelected()) {
                devices.getOrCreateEyevueManager().setRecordingDuration(action.seconds)
            } else if (devices.isHeyCyanSelected()) {
                heyCyanSettings.setHeyCyanRecordingDuration(isAudio = true, seconds = action.seconds)
            }
            is GlassesDashboardAction.SetWearingDetection -> if (devices.isEyevueSelected()) {
                devices.getOrCreateEyevueManager().setWearingDetection(action.enabled)
            } else if (devices.isTuneBudsSelected()) {
                Log.i("Dashboard", "TuneBuds wearing detection is not exposed until hardware capability is confirmed")
            } else {
                Log.i("Dashboard", "SetWearingDetection (no-op in temp branch)")
            }
            GlassesDashboardAction.DismissTransportPermissionDialog ->
                transportPermissions.dismissTransportPermissionDialog()
            GlassesDashboardAction.RequestTransportPermission -> transportPermissions.requestPendingTransportPermission()
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
