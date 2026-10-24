package com.fersaiyan.cyanbridge.shared.platform

import com.fersaiyan.cyanbridge.bridge.core.AiPhotoCapture
import com.fersaiyan.cyanbridge.bridge.core.BatteryReporting
import com.fersaiyan.cyanbridge.bridge.core.CameraCapture
import com.fersaiyan.cyanbridge.bridge.core.DeviceInfoReporting
import com.fersaiyan.cyanbridge.bridge.core.GlassesConnectionControl
import com.fersaiyan.cyanbridge.bridge.core.OnboardRecording
import com.fersaiyan.cyanbridge.bridge.core.RecordingSettingsControl
import com.fersaiyan.cyanbridge.bridge.core.WearingDetectionControl
import com.fersaiyan.cyanbridge.shared.ble.IosBleManager
import com.fersaiyan.cyanbridge.shared.ble.IosEyevueSession
import com.fersaiyan.cyanbridge.shared.devices.GlassesFamilyProfile
import com.fersaiyan.cyanbridge.shared.devices.GlassesFamilyProfiles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Eyevue glasses over BLE GATT ([IosEyevueSession]; Android: EyevueGlasses over EyevueManager).
 * Media sync, live preview and volume are not available on iOS.
 */
internal class IosEyevueGlasses(
    private val bleManager: IosBleManager,
    private val scope: CoroutineScope,
    private val host: IosGlassesHost,
    override val connection: GlassesConnectionControl,
) : IosGlassesDevice {
    override val profile: GlassesFamilyProfile = GlassesFamilyProfiles.eyevue

    private val session = IosEyevueSession(
        bleManager = bleManager,
        onBattery = { battery ->
            scope.launch { host.updateDashboard { it.copy(batteryPercent = battery.percent) } }
        },
        onWifiSsid = { ssid ->
            scope.launch { host.updateDashboard { it.copy(transfer = it.transfer.copy(detail = "Eyevue Wi-Fi: $ssid")) } }
        },
        onPhoto = { image -> host.onGlassesPhoto(image) },
    )

    override val isLinked: Boolean get() = host.isBleConnected

    /** The BLE link came up with Eyevue selected. */
    suspend fun onConnected() {
        host.updateDashboard { it.copy(videoRecordingDurationOptionsSeconds = VIDEO_DURATION_OPTIONS_SECONDS) }
        session.onConnected()
    }

    private fun launchCommand(command: suspend () -> Unit) {
        scope.launch { command() }
    }

    override val camera = CameraCapture { launchCommand { session.takePhoto(highQuality = true) } }

    override val recording = object : OnboardRecording {
        override fun toggleVideoRecording() = launchCommand {
            val start = !host.dashboard.isVideoRecording
            if (session.setVideoRecording(start)) host.updateDashboard { it.copy(isVideoRecording = start) }
        }

        override fun toggleAudioRecording() = launchCommand {
            val start = !host.dashboard.isAudioRecording
            if (session.setAudioRecording(start)) host.updateDashboard { it.copy(isAudioRecording = start) }
        }
    }

    override val battery = object : BatteryReporting {
        override val pollsPeriodically: Boolean = false

        override fun requestBattery() = launchCommand { session.requestBattery() }

        override fun refreshAfterSelection() = Unit
    }

    override val deviceInfo = object : DeviceInfoReporting {
        /** The firmware revision from the standard BLE device information service. */
        override fun requestVersion() = launchCommand {
            val label = runCatching { bleManager.requestFirmwareVersion() }.getOrNull()?.let { "Firmware: $it" }
            host.updateDashboard {
                it.copy(
                    deviceInfoLabel = label ?: it.deviceInfoLabel,
                    agentLastError = label ?: "Firmware version unavailable",
                )
            }
        }

        override fun syncTime() = launchCommand {
            val synced = session.syncTime()
            host.updateDashboard { it.copy(agentLastError = if (synced) "Glasses clock synced" else "Clock sync failed") }
        }
    }

    override val recordingSettings = object : RecordingSettingsControl {
        /** iOS reads no Eyevue recording limits; the durations are only written. */
        override fun refreshRecordingSettings() = Unit

        override fun setRecordingDuration(isAudio: Boolean, seconds: Int) = launchCommand {
            if (session.setRecordingDuration(seconds)) {
                host.updateDashboard {
                    if (isAudio) {
                        it.copy(audioRecordingDurationSeconds = seconds)
                    } else {
                        it.copy(videoRecordingDurationSeconds = seconds)
                    }
                }
            }
        }
    }

    override val wearingDetection = WearingDetectionControl { enabled ->
        launchCommand {
            if (session.setWearingDetection(enabled)) host.updateDashboard { it.copy(wearingDetectionEnabled = enabled) }
        }
    }

    /** The AI photo arrives on AA15 through [IosGlassesHost.onGlassesPhoto]. */
    override val aiPhoto = object : AiPhotoCapture {
        override suspend fun requestAiPhoto(): Boolean =
            session.takePhoto(highQuality = host.dashboard.imageThumbnailQualitySdkValue >= 5)
    }

    override val canTakeAiPhoto: Boolean get() = isLinked
}
