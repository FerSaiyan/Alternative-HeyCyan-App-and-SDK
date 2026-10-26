package com.fersaiyan.cyanbridge.ui.main.glasses

import com.fersaiyan.cyanbridge.ui.nav.AppRoute
import android.os.Build
import com.fersaiyan.cyanbridge.ai.questions.QuestionPhotoSource
import com.fersaiyan.cyanbridge.ai.questions.TuneBudsQuestionPhoto
import com.fersaiyan.cyanbridge.bridge.core.BatteryReporting
import com.fersaiyan.cyanbridge.bridge.core.CameraCapture
import com.fersaiyan.cyanbridge.bridge.core.DeviceInfoReporting
import com.fersaiyan.cyanbridge.bridge.core.GlassesConnectionControl
import com.fersaiyan.cyanbridge.bridge.core.LivePreviewTransport
import com.fersaiyan.cyanbridge.bridge.core.MediaSyncTransport
import com.fersaiyan.cyanbridge.bridge.core.OnboardRecording
import com.fersaiyan.cyanbridge.devices.DeviceProfileStore
import com.fersaiyan.cyanbridge.devices.tunebuds.TuneBudsManager
import com.fersaiyan.cyanbridge.glasses.GlassesSessionCoordinator
import com.fersaiyan.cyanbridge.shared.devices.GlassesFamilyProfile
import com.fersaiyan.cyanbridge.shared.devices.GlassesFamilyProfiles
import com.fersaiyan.cyanbridge.shared.glasses.LivePreviewUiState
import com.fersaiyan.cyanbridge.ui.AutoPairManager

/** TuneBuds / AB Mate glasses over [TuneBudsManager] (RFCOMM commands, hotspot media, RTSP preview). */
internal class TuneBudsGlasses(private val context: GlassesDeviceContext) : AndroidGlassesDevice {
    override val profile: GlassesFamilyProfile = GlassesFamilyProfiles.tuneBuds

    private val activity get() = context.activity
    private val manager: TuneBudsManager get() = context.managers.getOrCreateTuneBudsManager()

    override val questionPhoto: QuestionPhotoSource = TuneBudsQuestionPhoto({ manager }) { feature, onGranted ->
        context.transportPermissions.ensureGlassesTransportPermissions(feature, onGranted)
    }

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
            AutoPairManager.setAutoReconnectSuppressed(true, reason = "user_disconnect_button")
            manager.disconnect()
        }
    }

    override val camera = CameraCapture { manager.takePhoto() }

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
            manager.requestMediaCount()
        }

        override fun startSync() {
            context.actions.startTuneBudsMediaSync()
        }

        override fun stopSync() {
            context.actions.stopTuneBudsMediaSync()
        }
    }

    override val battery = object : BatteryReporting {
        override val pollsPeriodically: Boolean = false

        override fun requestBattery() {
            manager.requestBattery()
        }

        override fun refreshAfterSelection() {
            manager.takeIf { it.isConnected() }?.requestBattery()
        }
    }

    override val deviceInfo = object : DeviceInfoReporting {
        override fun requestVersion() {
            manager.requestVersion()
        }

        override fun syncTime() {
            manager.syncTime()
        }
    }

    override val livePreview = object : LivePreviewTransport {
        override fun startPreview() {
            context.actions.startTuneBudsLivePreview()
        }

        override fun stopPreview() {
            // The live session releases its lease and foreground service after cleanup.
            context.managers.tuneBudsLivePreviewManager?.stop()
        }
    }

    override fun renderConnectionStatus(connected: Boolean) {
        val tuneBuds = manager.state.value
        val storage = tuneBuds.storage?.let {
            "${it.usedMiB} MiB used / ${it.freeMiB} MiB free"
        } ?: "--"
        context.binding.statusText.text = tuneBuds.connectionLabel
        context.binding.storageText.text = storage
        context.connection.updateBatteryText(tuneBuds.batteryPercent)
        context.screen.updateDashboardState { state ->
            state.copy(
                connectionLabel = tuneBuds.connectionLabel,
                batteryPercent = tuneBuds.batteryPercent,
                storageLabel = storage,
            )
        }
        context.connection.updateDeviceClassText()
    }

    override fun onSelectedForDashboard() {
        context.managers.getOrCreateTuneBudsLivePreviewManager()
        if (GlassesSessionCoordinator.currentSession() == null) {
            manager.takeIf { it.isConnected() }?.refreshStatus()
        }
    }

    override fun dashboardLivePreview(): LivePreviewUiState = context.livePreviewUiState(
        context.managers.tuneBudsLivePreviewManager?.uiState?.value,
        isAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O,
    )

    override fun stopMediaSyncForActivityDestroy() {
        context.host.stopHeyCyanMediaSyncForDestroy()
    }
}
