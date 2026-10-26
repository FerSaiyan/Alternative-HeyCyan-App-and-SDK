package com.fersaiyan.cyanbridge.ui.main.glasses

import com.fersaiyan.cyanbridge.ui.nav.AppRoute
import android.os.Build
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.fersaiyan.cyanbridge.ai.questions.EyevueQuestionPhoto
import com.fersaiyan.cyanbridge.ai.questions.QuestionPhotoSource
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
import com.fersaiyan.cyanbridge.devices.DeviceProfileStore
import com.fersaiyan.cyanbridge.devices.eyevue.EyevueLiveForegroundService
import com.fersaiyan.cyanbridge.devices.eyevue.EyevueLivePreviewPolicy
import com.fersaiyan.cyanbridge.devices.eyevue.EyevueManager
import com.fersaiyan.cyanbridge.shared.devices.GlassesFamilyProfile
import com.fersaiyan.cyanbridge.shared.devices.GlassesFamilyProfiles
import com.fersaiyan.cyanbridge.shared.glasses.LivePreviewUiState
import com.fersaiyan.cyanbridge.ui.DeviceBindDestination
import com.fersaiyan.cyanbridge.ui.startKtxActivity
import kotlinx.coroutines.launch

/** Eyevue glasses over [EyevueManager] (BLE commands, Wi-Fi media and live preview). */
internal class EyevueGlasses(private val context: GlassesDeviceContext) : AndroidGlassesDevice {
    override val profile: GlassesFamilyProfile = GlassesFamilyProfiles.eyevue

    private val activity get() = context.activity
    private val manager: EyevueManager get() = context.managers.getOrCreateEyevueManager()

    override val questionPhoto: QuestionPhotoSource = EyevueQuestionPhoto { manager }

    override val connection: GlassesConnectionControl = object : GlassesConnectionControl {
        override fun scan() {
            activity.startActivity(AppRoute.DEVICE_BIND.intent(activity))
        }

        override fun reconnect() {
            DeviceProfileStore.loadLastSelected(activity)?.let { profile ->
                manager.connect(profile.macAddress, profile.advertisedName)
            } ?: activity.startActivity(AppRoute.DEVICE_BIND.intent(activity))
        }

        override fun disconnect() {
            manager.disconnect()
        }
    }

    override val camera = CameraCapture { manager.takeManualPhoto() }

    override val recording = object : OnboardRecording {
        override fun toggleVideoRecording() {
            manager.toggleVideo()
        }

        override fun toggleAudioRecording() {
            manager.toggleAudio()
        }
    }

    override val mediaSync = object : MediaSyncTransport {
        override fun requestMediaCount() {
            Toast.makeText(activity, "Requesting media count…", Toast.LENGTH_SHORT).show()
            activity.lifecycleScope.launch {
                val count = try {
                    manager.awaitMediaCount()
                } catch (_: Exception) {
                    null
                }
                Toast.makeText(
                    activity,
                    if (count != null) "Media count: $count" else "Eyevue did not report a media count",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }

        override fun startSync() {
            context.actions.startEyevueMediaSync()
        }

        override fun stopSync() {
            context.actions.stopEyevueMediaSync()
        }
    }

    override val battery = object : BatteryReporting {
        override val pollsPeriodically: Boolean = false

        override fun requestBattery() {
            Toast.makeText(activity, "Requesting battery level…", Toast.LENGTH_SHORT).show()
            activity.lifecycleScope.launch {
                val percent = try {
                    manager.awaitBatteryPercent()
                } catch (_: Exception) {
                    null
                }
                Toast.makeText(
                    activity,
                    if (percent != null) "Eyevue battery: $percent%" else "Eyevue did not report battery",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }

        override fun refreshAfterSelection() {
            manager.requestBattery()
        }
    }

    override val deviceInfo = object : DeviceInfoReporting {
        override fun requestVersion() {
            manager.requestDeviceInfo()
        }

        override fun syncTime() {
            manager.syncTime()
        }
    }

    override val volume = VolumeReporting { manager.requestVolume() }

    override val diagnostics = FeatureReportDiagnostics { manager.requestSupportFunction() }

    override val recordingSettings = object : RecordingSettingsControl {
        override fun refreshRecordingSettings() {
            manager.requestSupportFunction()
        }

        override fun setRecordingDuration(isAudio: Boolean, seconds: Int) {
            manager.setRecordingDuration(seconds)
        }
    }

    override val wearingDetection = WearingDetectionControl { enabled -> manager.setWearingDetection(enabled) }

    override val livePreview = object : LivePreviewTransport {
        override fun startPreview() {
            context.actions.startEyevueLivePreview()
        }

        override fun stopPreview() {
            if (context.managers.eyevueLivePreviewManager?.isActive == true) {
                context.managers.getOrCreateEyevueLivePreviewManager().stop()
            }
            EyevueLiveForegroundService.stop(activity)
            context.actions.releaseLivePreviewSession()
        }
    }

    override fun renderConnectionStatus(connected: Boolean) {
        val eyevue = manager.state.value
        val status = eyevue.connectionLabel
        context.binding.statusText.text = status
        context.binding.storageText.text = eyevue.storageCount?.toString() ?: "--"
        context.connection.updateBatteryText(eyevue.batteryPercent)
        context.screen.updateDashboardState { state ->
            state.copy(
                connectionLabel = status,
                storageLabel = eyevue.storageCount?.toString() ?: "--",
            )
        }
        context.connection.updateDeviceClassText()
    }

    override fun onSelectedForDashboard() {
        context.managers.getOrCreateEyevueLivePreviewManager()
    }

    override fun dashboardLivePreview(): LivePreviewUiState = context.livePreviewUiState(
        context.managers.eyevueLivePreviewManager?.uiState?.value,
        isAvailable = EyevueLivePreviewPolicy.isSupported(Build.VERSION.SDK_INT),
    )

    override fun stopMediaSyncForActivityDestroy() {
        context.host.stopVendorMediaSyncForDestroy {
            manager.finishTransfer()
            context.host.disconnectEyevueMediaTransport()
        }
    }
}
