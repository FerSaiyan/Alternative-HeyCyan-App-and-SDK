package com.fersaiyan.cyanbridge.shared.glasses

import com.fersaiyan.cyanbridge.bridge.core.BatteryReporting
import com.fersaiyan.cyanbridge.bridge.core.CameraCapture
import com.fersaiyan.cyanbridge.bridge.core.GlassesConnectionControl
import com.fersaiyan.cyanbridge.bridge.core.MediaSyncTransport
import com.fersaiyan.cyanbridge.bridge.core.RecordingSettingsControl
import com.fersaiyan.cyanbridge.bridge.core.WearingDetectionControl
import com.fersaiyan.cyanbridge.shared.devices.GlassesFamilyProfiles
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GlassesDeviceTest {
    private val calls = mutableListOf<String>()

    private val device = object : GlassesDevice {
        override val profile = GlassesFamilyProfiles.eyevue
        override val connection = object : GlassesConnectionControl {
            override fun scan() { calls += "scan" }
            override fun reconnect() { calls += "reconnect" }
            override fun disconnect() { calls += "disconnect" }
        }
        override val camera = CameraCapture { calls += "photo" }
        override val mediaSync = object : MediaSyncTransport {
            override fun requestMediaCount() { calls += "count" }
            override fun startSync() { calls += "sync" }
            override fun stopSync() { calls += "stop-sync" }
        }
        override val battery = object : BatteryReporting {
            override val pollsPeriodically = false
            override fun requestBattery() { calls += "battery" }
            override fun refreshAfterSelection() = Unit
        }
        override val recordingSettings = object : RecordingSettingsControl {
            override fun refreshRecordingSettings() { calls += "limits" }
            override fun setRecordingDuration(isAudio: Boolean, seconds: Int) { calls += "limit:$isAudio:$seconds" }
        }
        override val wearingDetection = WearingDetectionControl { calls += "wear:$it" }
    }

    @Test
    fun deviceActionsRunTheMatchingCapability() {
        listOf(
            GlassesDashboardAction.Scan,
            GlassesDashboardAction.Reconnect,
            GlassesDashboardAction.Disconnect,
            GlassesDashboardAction.CapturePhoto,
            GlassesDashboardAction.RequestMediaCount,
            GlassesDashboardAction.StartSync,
            GlassesDashboardAction.StopSync,
            GlassesDashboardAction.RequestBattery,
            GlassesDashboardAction.RefreshRecordingSettings,
            GlassesDashboardAction.SetVideoRecordingDuration(30),
            GlassesDashboardAction.SetAudioRecordingDuration(60),
            GlassesDashboardAction.SetWearingDetection(true),
        ).forEach { assertTrue(device.handle(it), "$it") }
        assertEquals(
            listOf(
                "scan", "reconnect", "disconnect", "photo", "count", "sync", "stop-sync", "battery",
                "limits", "limit:false:30", "limit:true:60", "wear:true",
            ),
            calls,
        )
    }

    @Test
    fun missingCapabilitiesAndAppActionsAreLeftToTheCaller() {
        listOf(
            GlassesDashboardAction.ToggleVideo,
            GlassesDashboardAction.StartLivePreview,
            GlassesDashboardAction.RequestVolume,
            GlassesDashboardAction.TestVoiceQuestion,
            GlassesDashboardAction.CancelOta,
            GlassesDashboardAction.MeizuConnect,
        ).forEach { assertFalse(device.handle(it), "$it") }
        assertTrue(calls.isEmpty())
        assertFalse(device.canTakeAiPhoto)
    }
}
