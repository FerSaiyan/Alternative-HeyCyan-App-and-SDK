package com.fersaiyan.cyanbridge.shared.glasses

import com.fersaiyan.cyanbridge.ai.image.ImageThumbnailQuality
import com.fersaiyan.cyanbridge.bridge.core.GlassesCapability
import com.fersaiyan.cyanbridge.shared.devices.GlassesFamilyProfile
import com.fersaiyan.cyanbridge.shared.devices.GlassesManagerGating
import com.fersaiyan.cyanbridge.shared.navigation.AppDestination
import com.fersaiyan.cyanbridge.shared.platform.PlatformLogger
import com.fersaiyan.cyanbridge.shared.plugins.NativePluginShortcutAction

/**
 * Platform side of the glasses dashboard: the selected glasses, the dashboard state, and the
 * effects that need the Android activity or the iOS host (navigation, permissions, services,
 * pickers, toasts, the assistant, preferences, OTA). [GlassesDashboardController] decides what
 * runs; the host only performs it.
 *
 * Members with a body are features a platform may not have; by default they report the action
 * through [onActionUnavailable].
 */
interface GlassesDashboardHost {
    /** The selected glasses family, as this platform implements it. */
    val selectedDevice: GlassesDevice

    var dashboard: GlassesDashboardUiState

    fun updateDashboard(transform: (GlassesDashboardUiState) -> GlassesDashboardUiState)

    /** [session] holds the glasses connection and refused a dashboard action. */
    fun onBlockedByExclusiveSession(session: GlassesSession)

    /**
     * [action] does nothing for the selected glasses on this platform: the family lacks the
     * capability or panel, or the platform lacks the feature. The controller already logged it.
     */
    fun onActionUnavailable(action: GlassesDashboardAction)

    fun navigate(destination: AppDestination)

    /** Index of the last meeting-timer option. */
    val meetingTimerLastIndex: Int

    /** Mirrors a meeting-timer choice into platform views. */
    fun onMeetingTimerSelected(index: Int) {}

    fun startMeetingCapture()

    fun stopMeetingCapture()

    fun runNativePluginShortcut(action: NativePluginShortcutAction)

    fun selectAssistantMode(mode: GlassesAssistantMode)

    fun saveAiWakeWordRoute(route: AiWakeWordRoute)

    /** Stores the AI photo thumbnail quality and returns the quality that was stored. */
    fun saveImageThumbnailQuality(sdkValue: Int): ImageThumbnailQuality

    /** Stores the Gemini Live photo delay; returns the stored seconds, or null without Gemini Live. */
    fun saveGeminiLiveImageDelay(seconds: Int): Int? = null

    fun askVoiceQuestion()

    fun askImageQuestion()

    fun openExternalImageAutomationDiagnostics() =
        onActionUnavailable(GlassesDashboardAction.OpenExternalImageAutomationDiagnostics)

    /** Mirrors the Advanced card toggle into platform views. */
    fun onAdvancedToggled() {}

    fun startLocalAgent() = onActionUnavailable(GlassesDashboardAction.StartAgent)

    fun stopLocalAgent() = onActionUnavailable(GlassesDashboardAction.StopAgent)

    fun runLocalAgentDemo() = onActionUnavailable(GlassesDashboardAction.RunAgentDemo)

    fun startClassicBluetoothScan() = onActionUnavailable(GlassesDashboardAction.StartClassicBluetoothScan)

    fun dumpOtaInfo() = onActionUnavailable(GlassesDashboardAction.DumpOtaInfo)

    fun testPullOta() = onActionUnavailable(GlassesDashboardAction.TestPullOta)

    fun requestOtaFirmware(source: OtaFirmwareSource) =
        onActionUnavailable(GlassesDashboardAction.RequestOtaFirmware(source))

    fun submitFirmwarePatchRequest(request: FirmwarePatchRequestUiState, contactEmail: String) =
        onActionUnavailable(GlassesDashboardAction.SubmitFirmwarePatchRequest(contactEmail))

    fun cancelOta() = onActionUnavailable(GlassesDashboardAction.CancelOta)

    /** Wi-Fi ADB debugging; the host checks its build type and whether [family] supports it. */
    fun startWifiAdbDebug(family: GlassesFamilyProfile) =
        onActionUnavailable(GlassesDashboardAction.RequestStartWifiAdbDebug)

    fun stopWifiAdbDebug(family: GlassesFamilyProfile) =
        onActionUnavailable(GlassesDashboardAction.StopWifiAdbDebug)

    fun dismissTransportPermissionDialog() =
        onActionUnavailable(GlassesDashboardAction.DismissTransportPermissionDialog)

    fun requestTransportPermission() = onActionUnavailable(GlassesDashboardAction.RequestTransportPermission)
}

/**
 * The glasses dashboard's action handling, shared by Android and iOS: an action passes the
 * exclusive-session policy ([DashboardSessionPolicy]) and the selected family's gating
 * ([GlassesManagerGating.isActionSupported]), then runs on the selected [GlassesDevice]
 * (capabilities, then its vendor panel) or as an app action through the [host].
 */
class GlassesDashboardController(
    private val host: GlassesDashboardHost,
    private val activeSession: () -> GlassesSession? = GlassesSessionCoordinator::currentSession,
) {
    fun handle(action: GlassesDashboardAction) {
        if (isBlockedByExclusiveSession(action)) return
        val device = host.selectedDevice
        val family = device.profile
        if (!GlassesManagerGating.isActionSupported(family, action)) {
            PlatformLogger.i(TAG, "Ignoring unsupported ${family.deviceClass} dashboard action: $action")
            host.onActionUnavailable(action)
            return
        }
        if (action == GlassesDashboardAction.StopLivePreview) {
            PlatformLogger.i("LivePreview", "BUTTON TAP: Stop Live Preview")
        }
        // Device-side actions run on the selected family's capabilities.
        if (device.handle(action)) return
        when (action) {
            is GlassesDashboardAction.Navigate -> host.navigate(action.destination)
            is GlassesDashboardAction.SelectMeetingTimer -> {
                val index = action.index.coerceIn(0, host.meetingTimerLastIndex)
                host.onMeetingTimerSelected(index)
                host.updateDashboard { state -> state.copy(meeting = state.meeting.copy(timerIndex = index)) }
            }
            GlassesDashboardAction.StartMeetingCapture -> host.startMeetingCapture()
            GlassesDashboardAction.StopMeetingCapture -> host.stopMeetingCapture()
            is GlassesDashboardAction.RunNativePluginShortcut -> host.runNativePluginShortcut(action.action)
            is GlassesDashboardAction.SelectAssistantMode -> host.selectAssistantMode(action.mode)
            is GlassesDashboardAction.SetAiWakeWordRoute -> {
                if (family.supports(GlassesCapability.WAKE_WORD)) {
                    host.saveAiWakeWordRoute(action.route)
                    host.updateDashboard { state -> state.copy(aiWakeWordRoute = action.route) }
                }
            }
            is GlassesDashboardAction.SelectImageThumbnailQuality -> {
                if (!family.supports(GlassesCapability.IMAGE_QUALITY_SELECTION)) return
                val quality = host.saveImageThumbnailQuality(action.sdkValue)
                host.updateDashboard { state ->
                    state.copy(
                        imageThumbnailQualitySdkValue = quality.sdkValue,
                        imageThumbnailQualityLabel = quality.label,
                    )
                }
            }
            is GlassesDashboardAction.SetGeminiLiveImageDelay -> {
                val seconds = host.saveGeminiLiveImageDelay(action.seconds)
                    ?: return host.onActionUnavailable(action)
                host.updateDashboard { state -> state.copy(geminiLiveImageDelaySeconds = seconds) }
            }
            GlassesDashboardAction.TestVoiceQuestion -> host.askVoiceQuestion()
            GlassesDashboardAction.TestImageQuestion -> host.askImageQuestion()
            GlassesDashboardAction.OpenExternalImageAutomationDiagnostics -> host.openExternalImageAutomationDiagnostics()
            GlassesDashboardAction.ToggleAdvanced -> {
                if (!host.dashboard.showAdvancedControls) return
                host.onAdvancedToggled()
                host.updateDashboard { state -> state.copy(advancedExpanded = !state.advancedExpanded) }
            }
            GlassesDashboardAction.StartAgent -> host.startLocalAgent()
            GlassesDashboardAction.StopAgent -> host.stopLocalAgent()
            GlassesDashboardAction.RunAgentDemo -> host.runLocalAgentDemo()
            GlassesDashboardAction.StartClassicBluetoothScan -> host.startClassicBluetoothScan()
            GlassesDashboardAction.DumpOtaInfo -> host.dumpOtaInfo()
            GlassesDashboardAction.TestPullOta -> host.testPullOta()
            is GlassesDashboardAction.RequestOtaFirmware -> host.requestOtaFirmware(action.source)
            is GlassesDashboardAction.SubmitFirmwarePatchRequest -> {
                val request = host.dashboard.firmwarePatchRequest ?: return
                if (request.isSubmitting) return
                host.dashboard = host.dashboard.copy(
                    firmwarePatchRequest = request.copy(isSubmitting = true, submissionError = null),
                )
                host.submitFirmwarePatchRequest(request, action.contactEmail)
            }
            GlassesDashboardAction.DismissFirmwarePatchRequest -> {
                if (host.dashboard.firmwarePatchRequest?.isSubmitting != true) {
                    host.dashboard = host.dashboard.copy(firmwarePatchRequest = null)
                }
            }
            GlassesDashboardAction.CancelOta -> host.cancelOta()
            GlassesDashboardAction.RequestStartWifiAdbDebug -> host.startWifiAdbDebug(family)
            GlassesDashboardAction.StopWifiAdbDebug -> host.stopWifiAdbDebug(family)
            GlassesDashboardAction.DismissTransportPermissionDialog -> host.dismissTransportPermissionDialog()
            GlassesDashboardAction.RequestTransportPermission -> host.requestTransportPermission()
            // Vendor panel actions run on the family that shows the panel.
            GlassesDashboardAction.MetaRegister,
            GlassesDashboardAction.MetaOpenPairing,
            GlassesDashboardAction.MetaOpenMetaAi,
            GlassesDashboardAction.MetaUnregister,
            GlassesDashboardAction.MetaStartSession,
            GlassesDashboardAction.MetaStopSession,
            GlassesDashboardAction.MetaStartStream,
            GlassesDashboardAction.MetaStopStream,
            GlassesDashboardAction.MetaCapturePhoto,
            GlassesDashboardAction.MetaViewPhoto,
            GlassesDashboardAction.MetaStartDisplay,
            GlassesDashboardAction.MetaStopDisplay,
            GlassesDashboardAction.MetaSendDiagnostics,
            GlassesDashboardAction.MeizuConnect,
            GlassesDashboardAction.MeizuDisconnect,
            GlassesDashboardAction.MeizuSendTestNotification,
            GlassesDashboardAction.MeizuShowTestTeleprompter,
            GlassesDashboardAction.MeizuSyncClock,
            GlassesDashboardAction.MeizuSetComfortBrightness,
            is GlassesDashboardAction.MemoMindPanel -> {
                if (!device.handlePanelAction(action)) reportNoCapability(family, action)
            }
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
            is GlassesDashboardAction.SetWearingDetection -> reportNoCapability(family, action)
        }
    }

    private fun reportNoCapability(family: GlassesFamilyProfile, action: GlassesDashboardAction) {
        PlatformLogger.i(TAG, "${family.deviceClass} has no capability for $action")
        host.onActionUnavailable(action)
    }

    private fun isBlockedByExclusiveSession(action: GlassesDashboardAction): Boolean {
        if (DashboardSessionPolicy.ignoresExclusiveSession(action)) return false
        val session = activeSession() ?: return false
        if (DashboardSessionPolicy.isAllowedDuring(action, session)) return false
        host.onBlockedByExclusiveSession(session)
        return true
    }

    private companion object {
        const val TAG = "Dashboard"
    }
}
