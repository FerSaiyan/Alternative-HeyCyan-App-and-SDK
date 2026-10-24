package com.fersaiyan.cyanbridge.ui.main.glasses

import com.fersaiyan.cyanbridge.bridge.core.GlassesConnectionControl
import com.fersaiyan.cyanbridge.devices.DeviceProfileStore
import com.fersaiyan.cyanbridge.devices.meizumyvu.MeizuMyvuManager
import com.fersaiyan.cyanbridge.shared.devices.GlassesFamilyProfiles
import com.fersaiyan.cyanbridge.ui.DeviceBindActivity
import com.fersaiyan.cyanbridge.ui.startKtxActivity

/** Meizu MYVU display glasses over [MeizuMyvuManager] (BLE pairing, RFCOMM relay). */
internal class MeizuMyvuGlasses(context: GlassesDeviceContext) :
    LegacySdkGlasses(GlassesFamilyProfiles.meizuMyvu, context) {
    private val activity get() = context.activity
    private val manager: MeizuMyvuManager get() = context.managers.getOrCreateMeizuMyvuManager()

    override val connection: GlassesConnectionControl = object : GlassesConnectionControl {
        override fun scan() {
            activity.startKtxActivity<DeviceBindActivity>()
        }

        override fun reconnect() {
            DeviceProfileStore.loadLastSelected(activity)?.macAddress?.let {
                manager.connect(it, activity, userInitiated = true)
            } ?: activity.startKtxActivity<DeviceBindActivity>()
        }

        override fun disconnect() {
            manager.disconnect()
        }
    }

    override val runsForegroundConnectionService: Boolean = true

    override fun renderConnectionStatus(connected: Boolean) {
        val myvu = manager.state.value
        binding.statusText.text = myvu.connectionLabel
        context.screen.updateDashboardState { state ->
            state.copy(
                connectionLabel = myvu.connectionLabel,
                batteryPercent = myvu.batteryPercent,
            )
        }
        context.connection.updateDeviceClassText()
    }

    override fun onSelectedForDashboard() {
        context.managers.getOrCreateMeizuMyvuManager()
        context.meizuMyvuUi.updateMeizuMyvuUiState()
    }

    override fun onActivityResumed() {
        context.managers.getOrCreateMeizuMyvuManager()
    }

    override fun onBackgroundFeaturesRestarted() {
        DeviceProfileStore.loadLastSelected(activity)?.macAddress?.let { address ->
            manager.connect(address, activity)
        }
    }
}
