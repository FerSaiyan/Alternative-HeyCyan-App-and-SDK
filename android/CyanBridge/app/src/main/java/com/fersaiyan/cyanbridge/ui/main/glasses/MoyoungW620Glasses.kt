package com.fersaiyan.cyanbridge.ui.main.glasses

import com.fersaiyan.cyanbridge.ui.nav.AppRoute
import com.fersaiyan.cyanbridge.bridge.core.BatteryReporting
import com.fersaiyan.cyanbridge.bridge.core.GlassesConnectionControl
import com.fersaiyan.cyanbridge.bridge.core.MediaSyncTransport
import com.fersaiyan.cyanbridge.devices.DeviceProfileStore
import com.fersaiyan.cyanbridge.devices.moyoung.MoyoungW620Manager
import com.fersaiyan.cyanbridge.shared.devices.GlassesFamilyProfiles
import com.fersaiyan.cyanbridge.ui.AutoPairManager
import com.fersaiyan.cyanbridge.ui.DeviceBindDestination
import com.fersaiyan.cyanbridge.ui.startKtxActivity

/**
 * MoYoung / W620 glasses over [MoyoungW620Manager]: connection, battery and Wi-Fi media sync.
 * Camera, recording and device-info commands still use the legacy SDK controls.
 */
internal class MoyoungW620Glasses(context: GlassesDeviceContext) :
    LegacySdkGlasses(GlassesFamilyProfiles.moyoungW620, context) {
    private val activity get() = context.activity
    private val manager: MoyoungW620Manager get() = context.managers.getOrCreateMoyoungW620Manager()

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

    override val mediaSync: MediaSyncTransport = object : MediaSyncTransport {
        override fun requestMediaCount() {
            manager.requestMediaCount()
        }

        override fun startSync() {
            context.actions.startMoyoungW620MediaSync()
        }

        override fun stopSync() {
            context.actions.stopMoyoungW620MediaSync()
        }
    }

    override val battery: BatteryReporting = object : BatteryReporting {
        override val pollsPeriodically: Boolean = false

        override fun requestBattery() {
            manager.requestBattery()
        }

        override fun refreshAfterSelection() {
            manager.takeIf { it.isConnected() }?.requestBattery()
        }
    }

    /** The hidden Wi-Fi download control (plugin shortcut, Tasker) starts the MoYoung sync. */
    override val legacyDataDownload: MediaSyncTransport get() = mediaSync

    override fun onSelectedForDashboard() {
        manager.takeIf { it.isConnected() }?.requestMediaCount()
    }

    override fun stopMediaSyncForActivityDestroy() {
        context.host.stopVendorMediaSyncForDestroy {
            context.managers.moyoungW620Manager?.stopMediaSync()
        }
    }
}
