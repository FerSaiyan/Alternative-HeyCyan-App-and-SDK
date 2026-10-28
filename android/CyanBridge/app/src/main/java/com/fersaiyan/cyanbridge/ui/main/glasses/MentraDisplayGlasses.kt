package com.fersaiyan.cyanbridge.ui.main.glasses

import android.util.Log
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.lifecycle.lifecycleScope
import com.fersaiyan.cyanbridge.R
import com.fersaiyan.cyanbridge.bridge.core.BatteryReporting
import com.fersaiyan.cyanbridge.bridge.core.GlassesConnectionControl
import com.fersaiyan.cyanbridge.bridge.core.InputEvent
import com.fersaiyan.cyanbridge.devices.DeviceProfileStore
import com.fersaiyan.cyanbridge.devices.mentra.MentraLiveManager
import com.fersaiyan.cyanbridge.shared.devices.GlassesFamilyProfile
import com.fersaiyan.cyanbridge.shared.devices.mentra.MentraDisplayInput
import com.fersaiyan.cyanbridge.shared.devices.mentra.MentraSdkModel
import com.fersaiyan.cyanbridge.ui.AutoPairManager
import com.fersaiyan.cyanbridge.ui.nav.AppRoute
import com.mentra.bluetoothsdk.GlassesRuntimeState
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Display glasses over the Mentra Bluetooth SDK ([MentraLiveManager], one model at a time): G1,
 * G2, Mentra Display, NIMO and AR99. They pair through Device Bind and reconnect on launch like
 * Mentra Live. Text reaches them through the display bridge
 * ([com.fersaiyan.cyanbridge.shared.devices.mentra.MentraDisplayAdapter]): assistant answers,
 * plugins and notifications. A long press asks a spoken question through the SDK microphone.
 */
internal class MentraDisplayGlasses(
    private val context: GlassesDeviceContext,
    override val profile: GlassesFamilyProfile,
) : AndroidGlassesDevice {
    private val model: MentraSdkModel = checkNotNull(MentraSdkModel.forClass(profile.deviceClass)?.takeIf { it.hasDisplay })
    private val modelName: String = profile.deviceClass.displayName()
    private val activity get() = context.activity
    private val manager: MentraLiveManager get() = MentraLiveManager.getInstance(activity)
    private var observers: Job? = null

    private val isSelected: Boolean
        get() = DeviceProfileStore.selectedClass(activity) == profile.deviceClass

    override val connection: GlassesConnectionControl = object : GlassesConnectionControl {
        override fun scan() {
            activity.startActivity(AppRoute.DEVICE_BIND.intent(activity))
        }

        override fun reconnect() {
            AutoPairManager.setAutoReconnectSuppressed(false, reason = "user_reconnect")
            if (!manager.reconnectSavedDisplay(model, reason = "user")) toast(R.string.mentra_display_reconnect_busy, modelName)
        }

        override fun disconnect() {
            AutoPairManager.setAutoReconnectSuppressed(true, reason = "user_disconnect_button")
            manager.disconnect()
        }
    }

    override val battery = object : BatteryReporting {
        override val pollsPeriodically: Boolean = false

        override fun requestBattery() {
            val level = MentraLiveManager.instanceOrNull()?.batteryLevel()
            if (level == null) toast(R.string.mentra_display_battery_unknown, modelName) else toast(R.string.mentra_display_battery, modelName, level)
        }

        override fun refreshAfterSelection() = context.connection.updateBatteryText(manager.batteryLevel())
    }

    /** The SDK keeps its own foreground service with a notification while it runs. */
    override val runsForegroundConnectionService: Boolean = true

    override fun renderConnectionStatus(connected: Boolean) {
        val state = manager.glasses.value
        val label = when {
            state is GlassesRuntimeState.Connected && state.ready -> activity.getString(R.string.mentra_display_status_connected, modelName)
            state.connected || state.connection.isBusy -> activity.getString(R.string.mentra_display_status_connecting, modelName)
            else -> activity.getString(R.string.mentra_display_status_disconnected, modelName)
        }
        context.binding.statusText.text = label
        context.connection.updateBatteryText(manager.batteryLevel())
        context.screen.updateDashboardState { it.copy(connectionLabel = label) }
        context.connection.updateDeviceClassText()
    }

    /** Mirrors the SDK state into the dashboard; a long press runs the dashboard's voice question. */
    override fun onSelectedForDashboard() {
        if (observers?.isActive == true) return
        observers = activity.lifecycleScope.launch {
            launch {
                manager.glasses.collect { state -> if (isSelected) context.connection.updateConnectionStatus(state.ready) }
            }
            launch {
                manager.inputEvents.collect { event -> if (isSelected) onInput(event) }
            }
        }
    }

    private fun onInput(event: InputEvent) {
        if (MentraDisplayInput.actionFor(event) != MentraDisplayInput.Action.VOICE_QUESTION) return
        Log.i(MentraLiveManager.TAG, "$modelName long press -> voice question")
        context.binding.btnTestHijackVoice.performClick()
    }

    override fun stopMediaSyncForActivityDestroy() = Unit

    private fun toast(@StringRes message: Int, vararg args: Any) {
        Toast.makeText(activity, activity.getString(message, *args), Toast.LENGTH_SHORT).show()
    }
}
