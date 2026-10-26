package com.fersaiyan.cyanbridge.ui.main.glasses

import com.fersaiyan.cyanbridge.ui.nav.AppRoute
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.fersaiyan.cyanbridge.R
import com.fersaiyan.cyanbridge.bridge.core.BatteryReporting
import com.fersaiyan.cyanbridge.bridge.core.GlassesConnectionControl
import com.fersaiyan.cyanbridge.devices.DeviceProfileStore
import com.fersaiyan.cyanbridge.devices.memomind.MemoMindLinkState
import com.fersaiyan.cyanbridge.devices.memomind.MemoMindManager
import com.fersaiyan.cyanbridge.devices.memomind.webplugin.MemoMindWebPluginsActivity
import com.fersaiyan.cyanbridge.shared.devices.DeviceClass
import com.fersaiyan.cyanbridge.shared.devices.GlassesFamilyProfile
import com.fersaiyan.cyanbridge.shared.devices.GlassesFamilyProfiles
import com.fersaiyan.cyanbridge.shared.glasses.GlassesDashboardAction
import com.fersaiyan.cyanbridge.shared.glasses.MemoMindPanelCommand
import com.fersaiyan.cyanbridge.shared.glasses.MemoMindPanelLabels
import com.fersaiyan.cyanbridge.shared.glasses.MemoMindUiState
import com.fersaiyan.cyanbridge.ui.AutoPairManager
import com.fersaiyan.cyanbridge.ui.startKtxActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** MemoMind glasses over [MemoMindManager] (Classic SPP, the published GM protocol). */
internal class MemoMindGlasses(private val context: GlassesDeviceContext) : AndroidGlassesDevice {
    override val profile: GlassesFamilyProfile = GlassesFamilyProfiles.memoMind

    private val activity get() = context.activity
    private val manager: MemoMindManager get() = context.managers.getOrCreateMemoMindManager()
    private var voiceQuestionJob: Job? = null

    override val connection: GlassesConnectionControl = object : GlassesConnectionControl {
        override fun scan() {
            activity.startActivity(AppRoute.DEVICE_BIND.intent(activity))
        }

        override fun reconnect() {
            DeviceProfileStore.loadLastSelected(activity)?.let { profile ->
                AutoPairManager.setAutoReconnectSuppressed(false, reason = "user_reconnect_button")
                manager.connect(profile.macAddress, profile.advertisedName)
            } ?: activity.startActivity(AppRoute.DEVICE_BIND.intent(activity))
        }

        override fun disconnect() {
            AutoPairManager.setAutoReconnectSuppressed(true, reason = "user_disconnect_button")
            manager.disconnect()
        }
    }

    override val battery = object : BatteryReporting {
        override val pollsPeriodically: Boolean = false

        override fun requestBattery() {
            manager.refreshBattery()
        }

        override fun refreshAfterSelection() {
            manager.takeIf { it.isConnected() }?.refreshBattery()
        }
    }

    override fun handlePanelAction(action: GlassesDashboardAction): Boolean {
        val command = (action as? GlassesDashboardAction.MemoMindPanel)?.command ?: return false
        when (command) {
            MemoMindPanelCommand.CONNECT -> DeviceProfileStore.loadLastSelected(activity)?.let { profile ->
                AutoPairManager.setAutoReconnectSuppressed(false, reason = "user_connect_button")
                manager.connect(profile.macAddress, profile.advertisedName)
            } ?: Toast.makeText(activity, R.string.memomind_select_first, Toast.LENGTH_LONG).show()
            MemoMindPanelCommand.DISCONNECT -> connection.disconnect()
            MemoMindPanelCommand.SEND_TEST_NOTIFICATION -> manager.sendTestNotification()
            MemoMindPanelCommand.SHOW_TEST_TEXT -> manager.showTestText()
            MemoMindPanelCommand.CLEAR_DISPLAY -> manager.clearDisplay()
            MemoMindPanelCommand.COMFORT_BRIGHTNESS -> manager.setBrightness(COMFORT_BRIGHTNESS_PERCENT)
            MemoMindPanelCommand.TEST_MICROPHONE -> manager.testMicrophone()
            MemoMindPanelCommand.TOGGLE_NOTIFICATION_FORWARDING -> toggleNotificationForwarding()
            MemoMindPanelCommand.OPEN_WEB_PLUGINS -> activity.startKtxActivity<MemoMindWebPluginsActivity>()
        }
        return true
    }

    private fun toggleNotificationForwarding() {
        val enable = !manager.state.value.forwardingNotifications
        if (!manager.setNotificationForwarding(enable)) {
            Toast.makeText(activity, R.string.memomind_notification_access_needed, Toast.LENGTH_LONG).show()
            runCatching { activity.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        }
    }

    override fun renderConnectionStatus(connected: Boolean) {
        val memoMind = manager.state.value
        val info = memoMind.device.deviceInfo
        context.binding.statusText.text = memoMind.connectionLabel
        context.connection.updateBatteryText(memoMind.batteryPercent)
        context.screen.updateDashboardState { state ->
            state.copy(
                connectionLabel = memoMind.connectionLabel,
                batteryPercent = memoMind.batteryPercent,
                memoMind = MemoMindUiState(
                    connectionLabel = memoMind.connectionLabel,
                    deviceName = memoMind.name ?: info?.name,
                    detail = info?.let {
                        activity.getString(R.string.memomind_detail, it.model ?: "-", it.firmwareVersion ?: "-")
                    },
                    batteryPercent = memoMind.batteryPercent,
                    microphoneLabel = memoMind.microphoneLabel,
                    lastError = memoMind.lastError ?: memoMind.device.lastError,
                    canConnect = memoMind.link == MemoMindLinkState.DISCONNECTED,
                    canDisconnect = memoMind.link != MemoMindLinkState.DISCONNECTED,
                    canSend = memoMind.isConnected,
                    forwardingNotifications = memoMind.forwardingNotifications,
                    labels = panelLabels,
                ),
            )
        }
        context.connection.updateDeviceClassText()
    }

    private val panelLabels: MemoMindPanelLabels by lazy {
        MemoMindPanelLabels(
            description = activity.getString(R.string.memomind_panel_description),
            clearDisplay = activity.getString(R.string.memomind_clear_display),
            testMicrophone = activity.getString(R.string.memomind_test_microphone),
            forwardNotificationsOn = activity.getString(R.string.memomind_forward_notifications_on),
            forwardNotificationsOff = activity.getString(R.string.memomind_forward_notifications_off),
            webPlugins = activity.getString(R.string.memomind_webplugins_entry),
        )
    }

    override fun onSelectedForDashboard() {
        context.managers.getOrCreateMemoMindManager()
        observeVoiceQuestions()
    }

    override fun onActivityResumed() {
        context.managers.getOrCreateMemoMindManager()
        observeVoiceQuestions()
    }

    override fun onBackgroundFeaturesRestarted() {
        if (manager.isConnected()) return
        DeviceProfileStore.loadLastSelected(activity)?.let { manager.connect(it.macAddress, it.advertisedName) }
    }

    /**
     * The glasses button asks for a voice question; it runs the dashboard's existing voice
     * question path (assistant setup, routing and answer) while this activity exists. Without the
     * activity, MemoMindManager starts the same question in the background (GlassesButtonQuestions).
     */
    private fun observeVoiceQuestions() {
        if (voiceQuestionJob != null) return
        voiceQuestionJob = activity.lifecycleScope.launch {
            manager.voiceQuestionRequests.collect {
                if (DeviceProfileStore.selectedClass(activity) == DeviceClass.MEMOMIND) {
                    context.binding.btnTestHijackVoice.performClick()
                }
            }
        }
    }

    override fun stopMediaSyncForActivityDestroy() = Unit

    private companion object {
        const val COMFORT_BRIGHTNESS_PERCENT = 70
    }
}
