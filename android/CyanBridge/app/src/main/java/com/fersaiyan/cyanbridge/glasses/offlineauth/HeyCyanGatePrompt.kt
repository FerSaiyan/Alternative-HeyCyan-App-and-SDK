package com.fersaiyan.cyanbridge.glasses.offlineauth

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import com.fersaiyan.cyanbridge.ui.dialog.ExpressiveDialogs
import kotlinx.coroutines.delay

/**
 * Material 3 Expressive popups (via [ExpressiveDialogs], per android/AGENTS.md — no View dialogs) for
 * the HeyCyan offline gate unlock: a front-button prompt with a live countdown for the ~20 s bind
 * window, Cancel, and a success/failure result popup.
 *
 * It never tells the user the official HeyCyan app is required — the whole point is that CyanBridge
 * activates the glasses on its own.
 */
object HeyCyanGatePrompt {

    /** Shows "press the front button now" with a countdown. Returns a handle to dismiss on completion. */
    fun showFrontButtonPrompt(
        activity: Activity,
        windowMs: Long,
        onCancel: () -> Unit,
    ): ExpressiveDialogs.Handle? = ExpressiveDialogs.alert(
        activity = activity,
        title = "Activate your glasses",
        confirm = null,
        dismiss = ExpressiveDialogs.Action("Cancel") { onCancel() },
        cancelable = false,
        body = { FrontButtonCountdown(windowMs) },
    )

    @Composable
    private fun FrontButtonCountdown(windowMs: Long) {
        var remaining by remember { mutableIntStateOf((windowMs / 1000L).toInt().coerceAtLeast(1)) }
        LaunchedEffect(Unit) {
            while (remaining > 0) {
                delay(1_000)
                remaining -= 1
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "Press the front button on your glasses once now to finish connecting.",
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                "Waiting for your press — ${remaining}s remaining",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }

    /** Brief success/failure feedback after an unlock attempt. */
    fun showResult(activity: Activity, result: OfflineUnlockResult): ExpressiveDialogs.Handle? {
        val (title, message) = when (result) {
            OfflineUnlockResult.SUCCESS,
            OfflineUnlockResult.ALREADY_UNLOCKED,
            -> "Glasses activated" to "Your glasses are ready to sync."

            OfflineUnlockResult.NOT_REQUIRED -> return null // nothing to tell the user

            OfflineUnlockResult.BIND_REJECTED ->
                "Button not pressed" to
                    "No front-button press was detected in time. Reconnect and try again, pressing the " +
                    "front button once when prompted."

            OfflineUnlockResult.BOUND_TO_OTHER ->
                "Glasses bound to another account" to
                    "These glasses are bound to a different controller. Reset or unbind them on the glasses, " +
                    "then reconnect to activate them here."

            OfflineUnlockResult.AUTH_FAILED ->
                "Could not activate" to
                    "The glasses did not accept the activation handshake. Reconnect and try again."

            OfflineUnlockResult.NOT_CONNECTED,
            OfflineUnlockResult.TRANSPORT_ERROR,
            -> "Connection problem" to
                "Lost communication with the glasses during activation. Reconnect and try again."

            OfflineUnlockResult.DISABLED,
            OfflineUnlockResult.IN_PROGRESS,
            -> return null
        }
        return ExpressiveDialogs.alert(
            activity = activity,
            title = title,
            message = message,
            confirm = ExpressiveDialogs.Action("OK"),
        )
    }
}
