package com.fersaiyan.cyanbridge.localagent

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.fragment.app.FragmentActivity
import com.fersaiyan.cyanbridge.ui.ensureNotificationPermission
import com.fersaiyan.cyanbridge.ui.hasAccessibilityServicePermission
import com.fersaiyan.cyanbridge.ui.hasNotificationPermission
import com.fersaiyan.cyanbridge.ui.requestAccessibilityServicePermission

/** Best-effort launcher for the Tasker-backed Local Agent service. */
object LocalAgentController {
    private const val TAG = "LocalAgentController"

    data class CommandResult(val ok: Boolean, val userMessage: String, val error: String? = null)

    private const val DEFAULT_SERVICE_CLASS = "com.fersaiyan.cyanbridge.localagent.TaskerLocalAgentService"

    fun start(context: Context): CommandResult = start(context, goal = null)

    fun start(context: Context, goal: String?): CommandResult {
        val trimmedGoal = goal?.trim().orEmpty()
        if (trimmedGoal.isBlank()) return CommandResult(false, "No agent goal was provided.", "missing_goal")
        LocalAgentDeviceState.availability(context)
            .takeIf { it != LocalAgentDeviceState.Availability.READY }
            ?.let { return CommandResult(false, "Unlock and wake the phone before starting the Local Agent.", it.errorCode) }

        if (!hasNotificationPermission(context)) {
            if (context is FragmentActivity) {
                ensureNotificationPermission(context, "Local Agent") { start(context, goal) }
                return CommandResult(true, "Notification permission is required before the Local Agent can start.")
            }
            return CommandResult(false, "Notification permission is required to start the Local Agent.", "missing_post_notifications")
        }

        return sendServiceCommand(
            context,
            LocalAgentIntents.ACTION_START,
            extras = mapOf(LocalAgentIntents.EXTRA_GOAL to trimmedGoal),
        )
    }

    fun stop(context: Context): CommandResult = sendServiceCommand(context, LocalAgentIntents.ACTION_STOP)

    fun demo(context: Context): CommandResult {
        LocalAgentDeviceState.availability(context)
            .takeIf { it != LocalAgentDeviceState.Availability.READY }
            ?.let { return CommandResult(false, "Unlock and wake the phone before running the Local Agent demo.", it.errorCode) }
        return sendServiceCommand(context, LocalAgentIntents.ACTION_DEMO)
    }

    /** Standalone read-screen now uses Tasker + AutoInput; CyanBridge has no AccessibilityService. */
    fun readCurrentScreen(context: Context): CommandResult {
        LocalAgentDeviceState.availability(context)
            .takeIf { it != LocalAgentDeviceState.Availability.READY }
            ?.let { return CommandResult(false, "Unlock and wake the phone before reading the screen aloud.", it.errorCode) }
        if (!hasNotificationPermission(context)) {
            return CommandResult(false, "Notification permission is required to read the screen aloud.", "missing_post_notifications")
        }
        if (!hasAccessibilityServicePermission(context)) {
            if (context is FragmentActivity) requestAccessibilityServicePermission(context, "Local Agent screen reading")
            return CommandResult(false, "Enable AutoInput accessibility before reading the screen aloud.", "missing_autoinput_accessibility")
        }
        return sendServiceCommand(context, LocalAgentIntents.ACTION_READ_SCREEN_ALOUD)
    }

    fun requestStatus(context: Context): CommandResult = sendServiceCommand(context, LocalAgentIntents.ACTION_GET_STATUS)

    private fun sendServiceCommand(context: Context, action: String, extras: Map<String, String> = emptyMap()): CommandResult =
        sendCommandToClass(context, action, DEFAULT_SERVICE_CLASS, extras)

    private fun sendCommandToClass(
        context: Context,
        action: String,
        serviceClass: String,
        extras: Map<String, String>,
    ): CommandResult {
        val pm = context.packageManager
        val explicitIntent = Intent(action).setClassName(context.packageName, serviceClass)
        extras.forEach { (key, value) -> explicitIntent.putExtra(key, value) }

        if (pm.resolveService(explicitIntent, 0) == null) {
            return CommandResult(false, "Local agent is not available in this build.", "No service found for $action")
        }

        return try {
            val needsForeground = action == LocalAgentIntents.ACTION_START ||
                action == LocalAgentIntents.ACTION_READ_SCREEN_ALOUD
            if (needsForeground && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(explicitIntent)
            } else {
                context.startService(explicitIntent)
            }
            Log.i(TAG, "Command sent: ${action.substringAfterLast('.')} -> $serviceClass")
            CommandResult(true, "Command sent: ${action.substringAfterLast('.')}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send command: ${action.substringAfterLast('.')}", e)
            CommandResult(false, "Failed to send agent command.", e.message ?: e.javaClass.simpleName)
        }
    }

    @Suppress("unused")
    private fun PackageManager.queryIntentServicesCompat(intent: Intent): List<android.content.pm.ResolveInfo> {
        @Suppress("DEPRECATION")
        return queryIntentServices(intent, 0)
    }
}
