package com.fersaiyan.cyanbridge.devices.tunebuds

import com.fersaiyan.cyanbridge.shared.glasses.AiWakeWordRoute
import com.fersaiyan.cyanbridge.shared.glasses.GlassesAssistantMode
import com.fersaiyan.cyanbridge.shared.glasses.GlassesDashboardAction
import com.fersaiyan.cyanbridge.shared.glasses.OtaFirmwareSource
import com.fersaiyan.cyanbridge.shared.navigation.AppDestination
import com.fersaiyan.cyanbridge.shared.plugins.NativePluginShortcutAction
import org.junit.Assert.assertEquals
import org.junit.Test

/** Pins, for every dashboard action, whether the TuneBuds dashboard lets it reach a handler. */
class TuneBudsDashboardActionMatrixTest {
    private val allowed = listOf(
        GlassesDashboardAction.Navigate(AppDestination.entries.first()),
        GlassesDashboardAction.Scan,
        GlassesDashboardAction.Reconnect,
        GlassesDashboardAction.Disconnect,
        GlassesDashboardAction.SelectMeetingTimer(1),
        GlassesDashboardAction.StartMeetingCapture,
        GlassesDashboardAction.StopMeetingCapture,
        GlassesDashboardAction.RunNativePluginShortcut(NativePluginShortcutAction.entries.first()),
        GlassesDashboardAction.SelectAssistantMode(GlassesAssistantMode.PHONE_ASSISTANT),
        GlassesDashboardAction.SelectImageThumbnailQuality(4),
        GlassesDashboardAction.SetGeminiLiveImageDelay(5),
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
        GlassesDashboardAction.StartLivePreview,
        GlassesDashboardAction.StopLivePreview,
        GlassesDashboardAction.DismissTransportPermissionDialog,
        GlassesDashboardAction.RequestTransportPermission,
    )

    private val rejected = listOf(
        GlassesDashboardAction.SetAiWakeWordRoute(AiWakeWordRoute.VOICE_QUESTION),
        GlassesDashboardAction.RefreshRecordingSettings,
        GlassesDashboardAction.SetWearingDetection(false),
        GlassesDashboardAction.SetVideoRecordingDuration(30),
        GlassesDashboardAction.SetAudioRecordingDuration(30),
        GlassesDashboardAction.RequestVolume,
        GlassesDashboardAction.AddDeviceListener,
        GlassesDashboardAction.StartClassicBluetoothScan,
        GlassesDashboardAction.DumpOtaInfo,
        GlassesDashboardAction.TestPullOta,
        GlassesDashboardAction.RequestOtaFirmware(OtaFirmwareSource.STEALTH_CATALOG),
        GlassesDashboardAction.SubmitFirmwarePatchRequest("a@b.c"),
        GlassesDashboardAction.DismissFirmwarePatchRequest,
        GlassesDashboardAction.CancelOta,
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
    )

    @Test
    fun everyDashboardActionTypeIsCovered() {
        val covered = (allowed + rejected).map { it::class }.toSet()
        assertEquals(66, covered.size)
    }

    @Test
    fun tuneBudsDashboardAllowsExactlyTheValidatedActions() {
        allowed.forEach { assertEquals(it.toString(), true, it.isSupportedForTuneBudsDashboard()) }
        rejected.forEach { assertEquals(it.toString(), false, it.isSupportedForTuneBudsDashboard()) }
    }
}
