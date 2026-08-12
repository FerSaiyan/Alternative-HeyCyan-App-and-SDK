package com.fersaiyan.cyanbridge.devices.tunebuds

import com.fersaiyan.cyanbridge.shared.glasses.AiWakeWordRoute
import com.fersaiyan.cyanbridge.shared.glasses.GlassesDashboardAction
import com.fersaiyan.cyanbridge.shared.glasses.OtaFirmwareSource
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TuneBudsDashboardActionPolicyTest {
    @Test
    fun validatedTuneBudsActionsAreExplicitlyAllowed() {
        val actions = listOf(
            GlassesDashboardAction.Scan,
            GlassesDashboardAction.Reconnect,
            GlassesDashboardAction.Disconnect,
            GlassesDashboardAction.TestVoiceQuestion,
            GlassesDashboardAction.TestImageQuestion,
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
        )

        actions.forEach { action -> assertTrue(action.toString(), action.isSupportedForTuneBudsDashboard()) }
    }

    @Test
    fun unvalidatedTuneBudsActionsCannotReachVendorHandlers() {
        val actions = listOf(
            GlassesDashboardAction.SetAiWakeWordRoute(AiWakeWordRoute.IMAGE_QUESTION),
            GlassesDashboardAction.SelectImageThumbnailQuality(5),
            GlassesDashboardAction.RefreshRecordingSettings,
            GlassesDashboardAction.SetWearingDetection(true),
            GlassesDashboardAction.SetVideoRecordingDuration(60),
            GlassesDashboardAction.SetAudioRecordingDuration(3_600),
            GlassesDashboardAction.RequestVolume,
            GlassesDashboardAction.AddDeviceListener,
            GlassesDashboardAction.StartClassicBluetoothScan,
            GlassesDashboardAction.DumpOtaInfo,
            GlassesDashboardAction.TestPullOta,
            GlassesDashboardAction.RequestOtaFirmware(OtaFirmwareSource.PERSONAL_FILE),
            GlassesDashboardAction.CancelOta,
            GlassesDashboardAction.StartLivePreview,
            GlassesDashboardAction.StopLivePreview,
            GlassesDashboardAction.RequestStartWifiAdbDebug,
            GlassesDashboardAction.StopWifiAdbDebug,
            GlassesDashboardAction.MeizuShowTestTeleprompter,
        )

        actions.forEach { action -> assertFalse(action.toString(), action.isSupportedForTuneBudsDashboard()) }
    }
}
