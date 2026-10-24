package com.fersaiyan.cyanbridge.ui.main

import com.fersaiyan.cyanbridge.glasses.GlassesSession
import com.fersaiyan.cyanbridge.shared.glasses.AiWakeWordRoute
import com.fersaiyan.cyanbridge.shared.glasses.GlassesAssistantMode
import com.fersaiyan.cyanbridge.shared.glasses.GlassesDashboardAction
import com.fersaiyan.cyanbridge.shared.glasses.OtaFirmwareSource
import com.fersaiyan.cyanbridge.shared.navigation.AppDestination
import com.fersaiyan.cyanbridge.shared.plugins.NativePluginShortcutAction
import org.junit.Assert.assertEquals
import org.junit.Test

class DashboardSessionPolicyTest {
    private val allActions: List<GlassesDashboardAction> = listOf(
        GlassesDashboardAction.Navigate(AppDestination.SETTINGS),
        GlassesDashboardAction.Scan,
        GlassesDashboardAction.Reconnect,
        GlassesDashboardAction.Disconnect,
        GlassesDashboardAction.SelectMeetingTimer(1),
        GlassesDashboardAction.StartMeetingCapture,
        GlassesDashboardAction.StopMeetingCapture,
        GlassesDashboardAction.RunNativePluginShortcut(NativePluginShortcutAction.START),
        GlassesDashboardAction.SelectAssistantMode(GlassesAssistantMode.PHONE_ASSISTANT),
        GlassesDashboardAction.SetAiWakeWordRoute(AiWakeWordRoute.entries.first()),
        GlassesDashboardAction.SelectImageThumbnailQuality(1),
        GlassesDashboardAction.SetGeminiLiveImageDelay(2),
        GlassesDashboardAction.RefreshRecordingSettings,
        GlassesDashboardAction.SetWearingDetection(true),
        GlassesDashboardAction.SetVideoRecordingDuration(30),
        GlassesDashboardAction.SetAudioRecordingDuration(1_800),
        GlassesDashboardAction.TestVoiceQuestion,
        GlassesDashboardAction.TestImageQuestion,
        GlassesDashboardAction.OpenExternalImageAutomationDiagnostics,
        GlassesDashboardAction.CapturePhoto,
        GlassesDashboardAction.ToggleVideo,
        GlassesDashboardAction.StartAudioRecording,
        GlassesDashboardAction.RequestMediaCount,
        GlassesDashboardAction.StartSync,
        GlassesDashboardAction.StopSync,
        GlassesDashboardAction.ToggleAdvanced,
        GlassesDashboardAction.StartAgent,
        GlassesDashboardAction.StopAgent,
        GlassesDashboardAction.RunAgentDemo,
        GlassesDashboardAction.RequestBattery,
        GlassesDashboardAction.RequestVersion,
        GlassesDashboardAction.SyncTime,
        GlassesDashboardAction.RequestVolume,
        GlassesDashboardAction.AddDeviceListener,
        GlassesDashboardAction.StartClassicBluetoothScan,
        GlassesDashboardAction.DumpOtaInfo,
        GlassesDashboardAction.TestPullOta,
        GlassesDashboardAction.RequestOtaFirmware(OtaFirmwareSource.entries.first()),
        GlassesDashboardAction.SubmitFirmwarePatchRequest("user@example.com"),
        GlassesDashboardAction.DismissFirmwarePatchRequest,
        GlassesDashboardAction.CancelOta,
        GlassesDashboardAction.StartLivePreview,
        GlassesDashboardAction.StopLivePreview,
        GlassesDashboardAction.RequestStartWifiAdbDebug,
        GlassesDashboardAction.StopWifiAdbDebug,
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
        GlassesDashboardAction.DismissTransportPermissionDialog,
        GlassesDashboardAction.RequestTransportPermission,
    )

    private val ignoringSession = setOf(
        "SubmitFirmwarePatchRequest",
        "SelectImageThumbnailQuality",
        "SetGeminiLiveImageDelay",
        "SetAiWakeWordRoute",
        "DismissFirmwarePatchRequest",
        "MetaSendDiagnostics",
    )

    private val allowedDuringMetaCamera = setOf(
        "Navigate",
        "StartMeetingCapture",
        "StopMeetingCapture",
        "RunNativePluginShortcut",
        "SelectAssistantMode",
        "TestVoiceQuestion",
        "TestImageQuestion",
        "OpenExternalImageAutomationDiagnostics",
        "StartAgent",
        "StopAgent",
        "RunAgentDemo",
        "MetaStopSession",
        "MetaStopStream",
        "MetaStopDisplay",
        "MetaCapturePhoto",
        "MetaViewPhoto",
        "MetaStartSession",
        "MetaStartStream",
        "MetaStartDisplay",
        "MetaSendDiagnostics",
    )

    private fun GlassesDashboardAction.name(): String = this::class.simpleName!!

    /** Fails to compile when a new action is added without extending [allActions]. */
    private fun requireListed(action: GlassesDashboardAction): Unit = when (action) {
        is GlassesDashboardAction.Navigate,
        GlassesDashboardAction.Scan,
        GlassesDashboardAction.Reconnect,
        GlassesDashboardAction.Disconnect,
        is GlassesDashboardAction.SelectMeetingTimer,
        GlassesDashboardAction.StartMeetingCapture,
        GlassesDashboardAction.StopMeetingCapture,
        is GlassesDashboardAction.RunNativePluginShortcut,
        is GlassesDashboardAction.SelectAssistantMode,
        is GlassesDashboardAction.SetAiWakeWordRoute,
        is GlassesDashboardAction.SelectImageThumbnailQuality,
        is GlassesDashboardAction.SetGeminiLiveImageDelay,
        GlassesDashboardAction.RefreshRecordingSettings,
        is GlassesDashboardAction.SetWearingDetection,
        is GlassesDashboardAction.SetVideoRecordingDuration,
        is GlassesDashboardAction.SetAudioRecordingDuration,
        GlassesDashboardAction.TestVoiceQuestion,
        GlassesDashboardAction.TestImageQuestion,
        GlassesDashboardAction.OpenExternalImageAutomationDiagnostics,
        GlassesDashboardAction.CapturePhoto,
        GlassesDashboardAction.ToggleVideo,
        GlassesDashboardAction.StartAudioRecording,
        GlassesDashboardAction.RequestMediaCount,
        GlassesDashboardAction.StartSync,
        GlassesDashboardAction.StopSync,
        GlassesDashboardAction.ToggleAdvanced,
        GlassesDashboardAction.StartAgent,
        GlassesDashboardAction.StopAgent,
        GlassesDashboardAction.RunAgentDemo,
        GlassesDashboardAction.RequestBattery,
        GlassesDashboardAction.RequestVersion,
        GlassesDashboardAction.SyncTime,
        GlassesDashboardAction.RequestVolume,
        GlassesDashboardAction.AddDeviceListener,
        GlassesDashboardAction.StartClassicBluetoothScan,
        GlassesDashboardAction.DumpOtaInfo,
        GlassesDashboardAction.TestPullOta,
        is GlassesDashboardAction.RequestOtaFirmware,
        is GlassesDashboardAction.SubmitFirmwarePatchRequest,
        GlassesDashboardAction.DismissFirmwarePatchRequest,
        GlassesDashboardAction.CancelOta,
        GlassesDashboardAction.StartLivePreview,
        GlassesDashboardAction.StopLivePreview,
        GlassesDashboardAction.RequestStartWifiAdbDebug,
        GlassesDashboardAction.StopWifiAdbDebug,
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
        GlassesDashboardAction.DismissTransportPermissionDialog,
        GlassesDashboardAction.RequestTransportPermission -> Unit
    }

    @Test
    fun everyDashboardActionIsListedOnce() {
        allActions.forEach(::requireListed)
        assertEquals(66, allActions.size)
        assertEquals(allActions.size, allActions.map { it.name() }.toSet().size)
    }

    @Test
    fun onlyAppStateActionsIgnoreTheExclusiveSession() {
        allActions.forEach { action ->
            assertEquals(
                action.name(),
                action.name() in ignoringSession,
                DashboardSessionPolicy.ignoresExclusiveSession(action),
            )
        }
    }

    @Test
    fun wifiAdbDebugOnlyAllowsItsOwnStop() {
        allActions.forEach { action ->
            assertEquals(
                action.name(),
                action == GlassesDashboardAction.StopWifiAdbDebug,
                DashboardSessionPolicy.isAllowedDuring(action, GlassesSession.WIFI_ADB_DEBUG),
            )
        }
    }

    @Test
    fun metaCameraAllowsMetaAssistantAndAppActions() {
        allActions.forEach { action ->
            assertEquals(
                action.name(),
                action.name() in allowedDuringMetaCamera,
                DashboardSessionPolicy.isAllowedDuring(action, GlassesSession.META_CAMERA),
            )
        }
    }

    @Test
    fun heyCyanSessionsOnlyAllowNavigationAndTheirOwnStop() {
        val ownStop = mapOf(
            GlassesSession.MEDIA_SYNC to GlassesDashboardAction.StopSync,
            GlassesSession.LIVE_PREVIEW to GlassesDashboardAction.StopLivePreview,
            GlassesSession.OTA to GlassesDashboardAction.CancelOta,
        )
        ownStop.keys.forEach { session ->
            allActions.forEach { action ->
                val expected = action is GlassesDashboardAction.Navigate || action == ownStop[session]
                assertEquals(
                    "${action.name()} during $session",
                    expected,
                    DashboardSessionPolicy.isAllowedDuring(action, session),
                )
            }
        }
    }
}
