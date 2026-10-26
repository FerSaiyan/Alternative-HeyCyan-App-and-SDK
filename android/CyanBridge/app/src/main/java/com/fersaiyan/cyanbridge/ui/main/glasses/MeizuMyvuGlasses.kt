package com.fersaiyan.cyanbridge.ui.main.glasses

import com.fersaiyan.cyanbridge.ui.nav.AppRoute
import android.widget.Toast
import com.fersaiyan.cyanbridge.bridge.core.GlassesConnectionControl
import com.fersaiyan.cyanbridge.devices.DeviceProfileStore
import com.fersaiyan.cyanbridge.devices.meizumyvu.MeizuMyvuManager
import com.fersaiyan.cyanbridge.shared.devices.GlassesFamilyProfiles
import com.fersaiyan.cyanbridge.shared.glasses.GlassesDashboardAction
import com.fersaiyan.cyanbridge.ui.DeviceBindDestination
import com.fersaiyan.cyanbridge.ui.startKtxActivity

/** Meizu MYVU display glasses over [MeizuMyvuManager] (BLE pairing, RFCOMM relay). */
internal class MeizuMyvuGlasses(context: GlassesDeviceContext) :
    LegacySdkGlasses(GlassesFamilyProfiles.meizuMyvu, context) {
    private val activity get() = context.activity
    private val manager: MeizuMyvuManager get() = context.managers.getOrCreateMeizuMyvuManager()

    override val connection: GlassesConnectionControl = object : GlassesConnectionControl {
        override fun scan() {
            activity.startActivity(AppRoute.DEVICE_BIND.intent(activity))
        }

        override fun reconnect() {
            DeviceProfileStore.loadLastSelected(activity)?.macAddress?.let {
                manager.connect(it, activity, userInitiated = true)
            } ?: activity.startActivity(AppRoute.DEVICE_BIND.intent(activity))
        }

        override fun disconnect() {
            manager.disconnect()
        }
    }

    override val runsForegroundConnectionService: Boolean = true

    /** The MYVU panel: connection, test notification and teleprompter, clock and brightness. */
    override fun handlePanelAction(action: GlassesDashboardAction): Boolean {
        when (action) {
            GlassesDashboardAction.MeizuConnect -> {
                DeviceProfileStore.loadLastSelected(activity)?.macAddress?.let {
                    manager.connect(it, activity, userInitiated = true)
                } ?: Toast.makeText(activity, "Select MYVU glasses from Scan first", Toast.LENGTH_LONG).show()
            }
            GlassesDashboardAction.MeizuDisconnect -> manager.disconnect()
            GlassesDashboardAction.MeizuSendTestNotification -> manager.sendTestNotification()
            GlassesDashboardAction.MeizuShowTestTeleprompter -> manager.showTeleprompter(
                "CyanBridge",
                "MYVU display connected\n\nNative voice plugins can now use the MYVU headset microphone and display bridge.",
            )
            GlassesDashboardAction.MeizuSyncClock -> manager.syncClock()
            GlassesDashboardAction.MeizuSetComfortBrightness -> manager.setBrightness(70)
            else -> return false
        }
        return true
    }

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
