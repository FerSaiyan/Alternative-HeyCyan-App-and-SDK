package com.fersaiyan.cyanbridge.localagent

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.fragment.app.FragmentActivity
import com.fersaiyan.cyanbridge.ui.ensureNotificationPermission
import com.fersaiyan.cyanbridge.ui.hasAccessibilityServicePermission
import com.fersaiyan.cyanbridge.ui.hasNotificationPermission
import com.fersaiyan.cyanbridge.ui.requestAccessibilityServicePermission

/**
 * Best-effort launcher for LocalAgentService via intent actions.
 *
 * Notes:
 * - Starting services via implicit intents is not allowed on Android 5.0+.
 * - We resolve the service and set an explicit component when possible.
 * - If the service is not present in this build, calls fail gracefully.
 */
object LocalAgentController {

    private const val TAG = "LocalAgentController"

    data class CommandResult(
        val ok: Boolean,
        val userMessage: String,
        val error: String? = null,
    )

    // If/when LocalAgentService is added, we expect it to live here.
    private const val DEFAULT_SERVICE_CLASS = "com.fersaiyan.cyanbridge.localagent.LocalAgentService"

    fun start(context: Context): CommandResult = start(context, goal = null)

    fun start(context: Context, goal: String?): CommandResult {
        val trimmedGoal = goal?.trim().orEmpty()
        if (trimmedGoal.isBlank()) {
            return CommandResult(
                ok = false,
                userMessage = "No agent goal was provided.",
                error = "missing_goal",
            )
        }
        LocalAgentDeviceState.availability(context).takeIf { it != LocalAgentDeviceState.Availability.READY }?.let {
            return CommandResult(
                ok = false,
                userMessage = "Unlock and wake the phone before starting the Local Agent.",
                error = it.errorCode,
            )
        }
        if (!hasNotificationPermission(context)) {
            if (context is FragmentActivity) {
                ensureNotificationPermission(context, "Local Agent") {
                    start(context, goal)
                }
                return CommandResult(
                    ok = true,
                    userMessage = "Notification permission is required before the Local Agent can start.",
                )
            }
            return CommandResult(
                ok = false,
                userMessage = "Notification permission is required to start the Local Agent.",
                error = "missing_post_notifications",
            )
        }

        if (!hasAccessibilityServicePermission(context)) {
            if (context is FragmentActivity) {
                requestAccessibilityServicePermission(context, "Local Agent")
            }
            return CommandResult(
                ok = false,
                userMessage = "Enable Accessibility access before starting the Local Agent.",
                error = "missing_accessibility_service",
            )
        }
        if (!LocalAgentAccessibilityBridge.isConnected()) {
            Log.w(TAG, "Start rejected: accessibility is enabled in settings but the service is not connected")
            return CommandResult(
                ok = false,
                userMessage = "CyanBridge Accessibility is enabled but not connected. Turn it off and on, then retry.",
                error = "accessibility_not_connected",
            )
        }

        return sendServiceCommand(
            context,
            LocalAgentIntents.ACTION_START,
            extras = mapOf(LocalAgentIntents.EXTRA_GOAL to trimmedGoal)
        )
    }

    fun stop(context: Context): CommandResult = sendServiceCommand(context, LocalAgentIntents.ACTION_STOP)

    fun demo(context: Context): CommandResult {
        LocalAgentDeviceState.availability(context).takeIf { it != LocalAgentDeviceState.Availability.READY }?.let {
            return CommandResult(
                ok = false,
                userMessage = "Unlock and wake the phone before running the Local Agent demo.",
                error = it.errorCode,
            )
        }
        return sendServiceCommand(context, LocalAgentIntents.ACTION_DEMO)
    }

    fun readCurrentScreen(context: Context): CommandResult {
        LocalAgentDeviceState.availability(context).takeIf { it != LocalAgentDeviceState.Availability.READY }?.let {
            return CommandResult(
                ok = false,
                userMessage = "Unlock and wake the phone before reading the screen aloud.",
                error = it.errorCode,
            )
        }
        if (!hasNotificationPermission(context)) {
            return CommandResult(
                ok = false,
                userMessage = "Notification permission is required to read the screen aloud.",
                error = "missing_post_notifications",
            )
        }
        if (!hasAccessibilityServicePermission(context)) {
            if (context is FragmentActivity) {
                requestAccessibilityServicePermission(context, "Local Agent")
            }
            return CommandResult(
                ok = false,
                userMessage = "Enable Accessibility access before reading the screen aloud.",
                error = "missing_accessibility_service",
            )
        }
        return sendServiceCommand(context, LocalAgentIntents.ACTION_READ_SCREEN_ALOUD)
    }

    fun requestStatus(context: Context): CommandResult =
        sendServiceCommand(context, LocalAgentIntents.ACTION_GET_STATUS)

    private fun sendServiceCommand(
        context: Context,
        action: String,
        extras: Map<String, String> = emptyMap(),
    ): CommandResult {
        val pm = context.packageManager

        // 1) Prefer resolving by action (requires LocalAgentService to declare an intent-filter).
        val implicit = Intent(action).setPackage(context.packageName)
        val resolved = pm.queryIntentServicesCompat(implicit)

        val explicitIntent = when {
            resolved.isNotEmpty() -> {
                val svcInfo = resolved.first().serviceInfo
                val comp = ComponentName(svcInfo.packageName, svcInfo.name)
                Intent(action).setComponent(comp)
            }

            // 2) Fallback to explicit class name (requires LocalAgentService to exist + be declared).
            else -> Intent(action).setClassName(context.packageName, DEFAULT_SERVICE_CLASS)
        }

        extras.forEach { (key, value) -> explicitIntent.putExtra(key, value) }

        val canResolve = pm.resolveService(explicitIntent, 0) != null
        if (!canResolve) {
            return CommandResult(
                ok = false,
                userMessage = "Local agent is not available in this build.",
                error = "No service found for $action",
            )
        }

        return try {
            val needsForeground = action == LocalAgentIntents.ACTION_START ||
                action == LocalAgentIntents.ACTION_READ_SCREEN_ALOUD
            if (needsForeground && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(explicitIntent)
            } else {
                context.startService(explicitIntent)
            }
            Log.i(TAG, "Command sent: ${action.substringAfterLast('.')}")
            CommandResult(ok = true, userMessage = "Command sent: ${action.substringAfterLast('.')}" )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send command: ${action.substringAfterLast('.')}", e)
            CommandResult(
                ok = false,
                userMessage = "Failed to send agent command.",
                error = e.message ?: e.javaClass.simpleName,
            )
        }
    }

    private fun PackageManager.queryIntentServicesCompat(intent: Intent): List<android.content.pm.ResolveInfo> {
        @Suppress("DEPRECATION")
        return queryIntentServices(intent, 0)
    }
}
