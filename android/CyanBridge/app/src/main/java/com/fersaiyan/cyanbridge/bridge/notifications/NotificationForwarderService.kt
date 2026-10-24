package com.fersaiyan.cyanbridge.bridge.notifications

import android.app.KeyguardManager
import android.content.ComponentName
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.fersaiyan.cyanbridge.bridge.core.DisplayCommand
import com.fersaiyan.cyanbridge.bridge.core.GlassesBridge
import com.fersaiyan.cyanbridge.agent.LocalAgentPrefs as AutomationPrefs
import com.fersaiyan.cyanbridge.localagent.LocalAgentNotificationSpeaker
import com.fersaiyan.cyanbridge.agent.LocalAgentPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Listens for phone notifications and forwards them to the connected glasses
 * via [GlassesBridge].
 *
 * Uses the notification wire route (group 0x05, opcode 0x01) — the same
 * channel the official MemoMind app uses for phone notifications.
 *
 * Enable/disable via [setEnabled]. The service itself is always bound when
 * the user grants Notification Listener access in system settings; the
 * flag controls whether incoming notifications are actually forwarded.
 */
class NotificationForwarderService : NotificationListenerService() {

    private var lastReadAloudFingerprint: String? = null
    private var lastReadAloudAtMs: Long = 0L

    companion object {
        private const val TAG = "NotificationForwarder"
        private const val CYANBRIDGE_PACKAGE = "com.fersaiyan.cyanbridge"

        /** Global toggle — set from UI. */
        @Volatile
        var enabled: Boolean = false
            private set

        /** Packages to never forward (avoid loops). */
        private val BLOCKED_PACKAGES = setOf(
            CYANBRIDGE_PACKAGE,
            "android",
            "com.android.systemui",
        )
        private val WHATSAPP_PACKAGES = setOf(
            "com.whatsapp",
            "com.whatsapp.w4b",
        )
        private const val DUPLICATE_WINDOW_MS = 10_000L

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        fun setForwardingEnabled(value: Boolean) {
            enabled = value
            Log.i(TAG, "Notification forwarding ${if (value) "enabled" else "disabled"}")
        }

        /** Check if the listener is currently bound by the system. */
        fun isListenerBound(context: Context): Boolean {
            val flat = ComponentName(context, NotificationForwarderService::class.java).flattenToString()
            val enabledListeners = android.provider.Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners",
            )
            return enabledListeners?.contains(flat) == true
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val notification = sbn ?: return

        val packageName = notification.packageName ?: return
        if (packageName in BLOCKED_PACKAGES) return

        val extras = notification.notification?.extras ?: return
        val title = extras.getCharSequence("android.title")?.toString() ?: return
        val text = extras.getCharSequence("android.text")?.toString() ?: ""

        maybeReadWhatsAppNotificationAloud(notification, title, text)

        if (!enabled) return

        // Resolve a human-readable app name
        val appName = resolveAppName(packageName)

        Log.i(TAG, "Forwarding: [$appName] $title — $text")

        scope.launch {
            try {
                // Use the notification wire route (group 0x05)
                // showText() routes through encodeNotification() internally
                val result = GlassesBridge.showText(
                    DisplayCommand.Text(text = "$appName: $title\n$text")
                )
                if (result.isFailure) {
                    Log.w(TAG, "Failed to forward notification: ${result.exceptionOrNull()?.message}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error forwarding notification", e)
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // No-op — we don't track removal
    }

    private fun maybeReadWhatsAppNotificationAloud(
        notification: StatusBarNotification,
        title: String,
        text: String,
    ) {
        if (!AutomationPrefs.isLocalAgentAutomationEnabled(applicationContext)) return
        if (!LocalAgentPrefs.isWhatsAppNotificationReadAloudEnabled(applicationContext)) return
        if (notification.packageName !in WHATSAPP_PACKAGES) return
        if (notification.isOngoing || text.isBlank()) return
        val keyguard = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        if (keyguard?.isKeyguardLocked == true) return

        val now = System.currentTimeMillis()
        val fingerprint = "${notification.key}|$title|$text"
        if (fingerprint == lastReadAloudFingerprint && now - lastReadAloudAtMs < DUPLICATE_WINDOW_MS) return
        lastReadAloudFingerprint = fingerprint
        lastReadAloudAtMs = now

        LocalAgentNotificationSpeaker.speak(
            applicationContext,
            "WhatsApp message from $title. $text",
        )
    }

    private fun resolveAppName(packageName: String): String {
        return try {
            val pm = applicationContext.packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (_: Exception) {
            packageName.substringAfterLast('.')
        }
    }
}
