package com.fersaiyan.cyanbridge.ui.main.glasses

import com.fersaiyan.cyanbridge.ai.AiQuestionForegroundService
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
import com.fersaiyan.cyanbridge.shared.devices.GlassesFamilyProfile
import com.fersaiyan.cyanbridge.shared.devices.GlassesFamilyProfiles
import com.fersaiyan.cyanbridge.shared.glasses.LivePreviewUiState
import com.oudmon.ble.base.bluetooth.DeviceManager

/**
 * Glasses driven by the HeyCyan / Oudmon SDK through the hidden legacy controls, so every
 * command passes their shared guards (exclusive session, SDK-control rejection, Bluetooth
 * permission, glasses audio stop). Families without their own transport for a capability use
 * these controls too.
 */
internal open class LegacySdkGlasses(
    final override val profile: GlassesFamilyProfile,
    protected val context: GlassesDeviceContext,
) : AndroidGlassesDevice {
    protected val binding get() = context.binding

    override val connection: GlassesConnectionControl = object : GlassesConnectionControl {
        override fun scan() {
            binding.btnScan.performClick()
        }

        override fun reconnect() {
            binding.btnConnect.performClick()
        }

        override fun disconnect() {
            binding.btnDisconnect.performClick()
        }
    }

    override val camera: CameraCapture? = CameraCapture { binding.btnCamera.performClick() }

    override val recording: OnboardRecording? = object : OnboardRecording {
        override fun toggleVideoRecording() {
            binding.btnVideo.performClick()
        }

        override fun toggleAudioRecording() {
            binding.btnRecord.performClick()
        }
    }

    override val mediaSync: MediaSyncTransport? = object : MediaSyncTransport {
        override fun requestMediaCount() {
            binding.btnMediaCount.performClick()
        }

        override fun startSync() {
            binding.btnDataDownload.performClick()
        }

        override fun stopSync() {
            binding.btnTransferStop.performClick()
        }
    }

    override val battery: BatteryReporting? = object : BatteryReporting {
        override val pollsPeriodically: Boolean = false

        override fun requestBattery() {
            binding.btnBattery.performClick()
        }

        override fun refreshAfterSelection() = Unit
    }

    override val deviceInfo: DeviceInfoReporting? = object : DeviceInfoReporting {
        override fun requestVersion() {
            binding.btnVersion.performClick()
        }

        override fun syncTime() {
            binding.btnSetTime.performClick()
        }
    }

    override val volume: VolumeReporting? = VolumeReporting { binding.btnVolume.performClick() }

    override val diagnostics: FeatureReportDiagnostics? =
        FeatureReportDiagnostics { binding.btnAddListener.performClick() }

    override fun renderConnectionStatus(connected: Boolean) {
        val deviceName = DeviceManager.getInstance().deviceName
        val status = if (connected) {
            if (!deviceName.isNullOrBlank()) {
                "Connected - $deviceName"
            } else {
                "Connected"
            }
        } else {
            "Disconnected"
        }
        binding.statusText.text = status
        context.screen.updateDashboardState { state -> state.copy(connectionLabel = status) }
        context.connection.updateDeviceClassText()
        if (connected) {
            context.heyCyanSettings.configureHeyCyanWakeWordIfNeeded()
            context.heyCyanSettings.refreshHeyCyanRecordingSettings(showDisconnectedMessage = false)
        } else {
            AiQuestionForegroundService.stop(context.activity)
            context.heyCyanSettings.wakeWordConfiguredForConnection = false
        }
        if (!connected) {
            context.connection.updateBatteryText(null)
        }
    }

    override fun stopMediaSyncForActivityDestroy() {
        context.host.stopHeyCyanMediaSyncForDestroy()
    }
}

/** HeyCyan glasses: the SDK controls plus battery polling, live preview and recording limits. */
internal class HeyCyanGlasses(context: GlassesDeviceContext) :
    LegacySdkGlasses(GlassesFamilyProfiles.heyCyan, context) {
    override val battery: BatteryReporting = object : BatteryReporting {
        override val pollsPeriodically: Boolean = true

        override fun requestBattery() {
            binding.btnBattery.performClick()
        }

        override fun refreshAfterSelection() = Unit
    }

    override val livePreview: LivePreviewTransport = object : LivePreviewTransport {
        override fun startPreview() {
            context.actions.startLivePreview()
        }

        override fun stopPreview() {
            context.actions.stopLivePreview()
        }
    }

    override val recordingSettings: RecordingSettingsControl = object : RecordingSettingsControl {
        override fun refreshRecordingSettings() {
            context.heyCyanSettings.refreshHeyCyanRecordingSettings()
        }

        override fun setRecordingDuration(isAudio: Boolean, seconds: Int) {
            context.heyCyanSettings.setHeyCyanRecordingDuration(isAudio = isAudio, seconds = seconds)
        }
    }

    override fun dashboardLivePreview(): LivePreviewUiState =
        context.livePreviewUiState(context.host.heyCyanLivePreviewManager.uiState.value, isAvailable = true)
}
