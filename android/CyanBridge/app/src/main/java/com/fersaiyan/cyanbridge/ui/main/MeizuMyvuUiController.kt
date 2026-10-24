package com.fersaiyan.cyanbridge.ui.main

import com.fersaiyan.cyanbridge.devices.meizumyvu.MeizuMyvuFailure
import com.fersaiyan.cyanbridge.shared.glasses.MeizuMyvuUiState
import com.fersaiyan.cyanbridge.ui.debug.DebugLogSupport

/** Meizu MYVU section of the dashboard and its connection-failure support prompt. */
internal class MeizuMyvuUiController(
    host: MainScreenHost,
    private val devices: DeviceManagerRegistry,
) : MainScreenController(host) {
    var pendingMeizuMyvuFailure: MeizuMyvuFailure? = null

    fun showPendingMeizuMyvuFailureIfNeeded() {
        val failure = pendingMeizuMyvuFailure ?: devices.meizuMyvuManager?.failurePrompt?.value ?: return
        if (!devices.isMeizuMyvuSelected()) {
            pendingMeizuMyvuFailure = null
            devices.meizuMyvuManager?.consumeFailurePrompt()
            return
        }
        if (!activity.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) return

        val manager = devices.getOrCreateMeizuMyvuManager()
        pendingMeizuMyvuFailure = null
        manager.consumeFailurePrompt()
        DebugLogSupport.showSupportOptionsDialog(
            activity = activity,
            title = "MYVU connection failed",
            issueType = "Meizu MYVU connection failure",
            description = buildString {
                appendLine("CyanBridge could not finish the MYVU connection during ${failure.stage}.")
                appendLine()
                appendLine("Reason: ${failure.reason}")
                appendLine()
                append("Force-stop the official MYVU app, confirm the glasses are paired in Android Bluetooth settings, and toggle Bluetooth off and on if RFCOMM remains stuck. You can send the redacted diagnostics below to CyanBridge support.")
            },
            extraInfo = linkedMapOf(
                "myvu_failure_stage" to failure.stage,
                "myvu_failure_reason" to failure.reason,
            ),
            dismissButtonLabel = "Later",
        )
    }

    fun updateMeizuMyvuUiState() {
        val manager = devices.meizuMyvuManager ?: return
        val myvu = manager.state.value
        val connected = myvu.relayReady
        updateDashboardState { state ->
            state.copy(
                connectionLabel = myvu.connectionLabel,
                batteryPercent = myvu.batteryPercent,
                meizuMyvu = MeizuMyvuUiState(
                    connectionLabel = myvu.connectionLabel,
                    protocolState = myvu.protocolState,
                    deviceName = myvu.deviceName,
                    batteryPercent = myvu.batteryPercent,
                    lastError = myvu.lastError,
                    canConnect = !connected && myvu.selectedAddress != null,
                    canDisconnect = myvu.protocolState != com.myvu.client.service.ConnectionState.IDLE.name,
                    canSend = connected,
                ),
            )
        }
    }
}
