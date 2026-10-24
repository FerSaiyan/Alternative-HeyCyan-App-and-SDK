package com.fersaiyan.cyanbridge.shared.glasses

import com.fersaiyan.cyanbridge.bridge.core.AiPhotoCapture
import com.fersaiyan.cyanbridge.bridge.core.BatteryReporting
import com.fersaiyan.cyanbridge.bridge.core.CameraCapture
import com.fersaiyan.cyanbridge.bridge.core.DeviceInfoReporting
import com.fersaiyan.cyanbridge.bridge.core.FeatureReportDiagnostics
import com.fersaiyan.cyanbridge.bridge.core.GlassesConnectionControl
import com.fersaiyan.cyanbridge.bridge.core.LivePreviewTransport
import com.fersaiyan.cyanbridge.bridge.core.MediaSyncTransport
import com.fersaiyan.cyanbridge.bridge.core.OnboardRecording
import com.fersaiyan.cyanbridge.bridge.core.RecordingSettingsControl
import com.fersaiyan.cyanbridge.bridge.core.VolumeReporting
import com.fersaiyan.cyanbridge.bridge.core.WearingDetectionControl
import com.fersaiyan.cyanbridge.shared.assistant.AssistantGlassesHooks
import com.fersaiyan.cyanbridge.shared.devices.GlassesFamilyProfile

/**
 * The selected glasses, as one family implements them on a platform. [profile] declares what
 * the family supports (and drives the dashboard gating); each capability property is the
 * family's implementation, or null when the family has none. Android and iOS implement one
 * [GlassesDevice] per family over their vendor managers/SDKs, and their dashboard controllers
 * call [handle] instead of branching on the device class.
 */
interface GlassesDevice {
    val profile: GlassesFamilyProfile

    val connection: GlassesConnectionControl
    val camera: CameraCapture? get() = null
    val recording: OnboardRecording? get() = null
    val mediaSync: MediaSyncTransport? get() = null
    val livePreview: LivePreviewTransport? get() = null
    val battery: BatteryReporting? get() = null
    val deviceInfo: DeviceInfoReporting? get() = null
    val volume: VolumeReporting? get() = null
    val diagnostics: FeatureReportDiagnostics? get() = null
    val recordingSettings: RecordingSettingsControl? get() = null
    val wearingDetection: WearingDetectionControl? get() = null
    val aiPhoto: AiPhotoCapture? get() = null

    /** What the glasses need around an assistant answer (for example HeyCyan AI-speak modes). */
    val assistantHooks: AssistantGlassesHooks get() = AssistantGlassesHooks.None

    val canTakeAiPhoto: Boolean get() = aiPhoto != null

    suspend fun requestAiPhoto(): Boolean = aiPhoto?.requestAiPhoto() ?: false

    fun connect() = connection.reconnect()

    fun disconnect() = connection.disconnect()

    /**
     * Runs a device-side dashboard action through this family's capabilities. Returns false for
     * actions that belong to the app (navigation, assistant, OTA, plugins) or that this family
     * has no capability for, so the caller can handle or ignore them.
     */
    fun handle(action: GlassesDashboardAction): Boolean = GlassesDeviceActions.handle(this, action)
}

/** Maps device-side dashboard actions to capabilities; shared by every [GlassesDevice]. */
object GlassesDeviceActions {
    fun handle(device: GlassesDevice, action: GlassesDashboardAction): Boolean {
        val run: (() -> Unit)? = when (action) {
            GlassesDashboardAction.Scan -> device.connection::scan
            GlassesDashboardAction.Reconnect -> device.connection::reconnect
            GlassesDashboardAction.Disconnect -> device.connection::disconnect
            GlassesDashboardAction.CapturePhoto -> device.camera?.let { it::capturePhoto }
            GlassesDashboardAction.ToggleVideo -> device.recording?.let { it::toggleVideoRecording }
            GlassesDashboardAction.StartAudioRecording -> device.recording?.let { it::toggleAudioRecording }
            GlassesDashboardAction.RequestMediaCount -> device.mediaSync?.let { it::requestMediaCount }
            GlassesDashboardAction.StartSync -> device.mediaSync?.let { it::startSync }
            GlassesDashboardAction.StopSync -> device.mediaSync?.let { it::stopSync }
            GlassesDashboardAction.StartLivePreview -> device.livePreview?.let { it::startPreview }
            GlassesDashboardAction.StopLivePreview -> device.livePreview?.let { it::stopPreview }
            GlassesDashboardAction.RequestBattery -> device.battery?.let { it::requestBattery }
            GlassesDashboardAction.RequestVersion -> device.deviceInfo?.let { it::requestVersion }
            GlassesDashboardAction.SyncTime -> device.deviceInfo?.let { it::syncTime }
            GlassesDashboardAction.RequestVolume -> device.volume?.let { it::requestVolume }
            GlassesDashboardAction.AddDeviceListener -> device.diagnostics?.let { it::requestFeatureReport }
            GlassesDashboardAction.RefreshRecordingSettings ->
                device.recordingSettings?.let { it::refreshRecordingSettings }
            is GlassesDashboardAction.SetVideoRecordingDuration -> device.recordingSettings?.let { settings ->
                { settings.setRecordingDuration(isAudio = false, seconds = action.seconds) }
            }
            is GlassesDashboardAction.SetAudioRecordingDuration -> device.recordingSettings?.let { settings ->
                { settings.setRecordingDuration(isAudio = true, seconds = action.seconds) }
            }
            is GlassesDashboardAction.SetWearingDetection -> device.wearingDetection?.let { control ->
                { control.setWearingDetection(action.enabled) }
            }
            else -> null
        }
        run?.invoke()
        return run != null
    }
}
