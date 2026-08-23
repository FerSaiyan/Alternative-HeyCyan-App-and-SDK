package com.fersaiyan.cyanbridge.localagent

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

object LocalAgentPrefs {
    private const val PREFS = "local_agent_prefs"
    private const val SECRET_PREFS = "local_agent_secrets"

    private const val KEY_STATUS = "status"
    private const val KEY_LAST_ERROR = "last_error"
    private const val KEY_LAST_APPROVAL_VOICE_PROMPT = "last_approval_voice_prompt"
    private const val KEY_LAST_APPROVAL_VOICE_REPLY = "last_approval_voice_reply"

    // Debug: last context injection details (normal chat System prompt)
    private const val KEY_LAST_CONTEXT_INJECTION_DEBUG = "last_context_injection_debug"
    private const val KEY_LAST_CONTEXT_INJECTION_AT_MS = "last_context_injection_at_ms"

    // Unified action approval
    private const val KEY_REQUIRE_ACTION_CONFIRMATION = "require_action_confirmation"
    private const val KEY_AUTO_EXECUTE_LOW_RISK = "auto_execute_low_risk"
    private const val KEY_WHATSAPP_NOTIFICATION_READ_ALOUD = "whatsapp_notification_read_aloud"

    // Remote Telegram control. The bot token is intentionally stored separately in encrypted
    // preferences; the allowed chat ID and polling cursor are not credentials.
    private const val KEY_TELEGRAM_REMOTE_ENABLED = "telegram_remote_enabled"
    private const val KEY_TELEGRAM_ALLOWED_CHAT_ID = "telegram_allowed_chat_id"
    private const val KEY_TELEGRAM_UPDATE_OFFSET = "telegram_update_offset"
    private const val KEY_TELEGRAM_STATUS = "telegram_status"
    private const val KEY_TELEGRAM_LAST_ERROR = "telegram_last_error"
    private const val KEY_TELEGRAM_BOT_TOKEN = "telegram_bot_token"

    // Screenshot planning is deliberately two-stage: capture and remote upload are separate
    // opt-ins so a remote planner never receives an image by default.
    private const val KEY_SCREENSHOT_PLANNING_ENABLED = "screenshot_planning_enabled"
    private const val KEY_REMOTE_SCREENSHOT_UPLOAD_ENABLED = "remote_screenshot_upload_enabled"
    private const val KEY_SCREENSHOT_STATUS = "screenshot_status"

    private const val KEY_SHIZUKU_FALLBACK_ENABLED = "shizuku_fallback_enabled"
    private const val KEY_SHIZUKU_STATUS = "shizuku_status"

    private const val MAX_TELEGRAM_TOKEN_CHARS = 256

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun secretPrefs(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context.applicationContext,
            SECRET_PREFS,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun isRequireActionConfirmationEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_REQUIRE_ACTION_CONFIRMATION, true)
    }

    fun setRequireActionConfirmationEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_REQUIRE_ACTION_CONFIRMATION, enabled)
            .apply()
    }

    fun isAutoExecuteLowRiskEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTO_EXECUTE_LOW_RISK, true)
    }

    fun setAutoExecuteLowRiskEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_AUTO_EXECUTE_LOW_RISK, enabled)
            .apply()
    }

    fun isWhatsAppNotificationReadAloudEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_WHATSAPP_NOTIFICATION_READ_ALOUD, false)
    }

    fun setWhatsAppNotificationReadAloudEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_WHATSAPP_NOTIFICATION_READ_ALOUD, enabled)
            .apply()
    }

    fun getStatus(context: Context): String {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_STATUS, "Unknown")
            ?: "Unknown"
    }

    fun setStatus(context: Context, status: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_STATUS, status)
            .apply()
    }

    fun getLastError(context: Context): String {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LAST_ERROR, "(none)")
            ?: "(none)"
    }

    fun setLastError(context: Context, error: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_ERROR, error)
            .apply()
    }

    fun clearLastError(context: Context) {
        setLastError(context, "(none)")
    }

    fun setLastApprovalVoicePrompt(context: Context, prompt: String) {
        prefs(context).edit().putString(KEY_LAST_APPROVAL_VOICE_PROMPT, prompt.take(2_000)).apply()
    }

    fun getLastApprovalVoicePrompt(context: Context): String =
        prefs(context).getString(KEY_LAST_APPROVAL_VOICE_PROMPT, "").orEmpty()

    fun clearLastApprovalVoicePrompt(context: Context) {
        prefs(context).edit().remove(KEY_LAST_APPROVAL_VOICE_PROMPT).apply()
    }

    fun setLastApprovalVoiceReply(context: Context, reply: String) {
        prefs(context).edit().putString(KEY_LAST_APPROVAL_VOICE_REPLY, reply.take(500)).apply()
    }

    fun getLastApprovalVoiceReply(context: Context): String =
        prefs(context).getString(KEY_LAST_APPROVAL_VOICE_REPLY, "").orEmpty()

    fun clearLastApprovalVoiceReply(context: Context) {
        prefs(context).edit().remove(KEY_LAST_APPROVAL_VOICE_REPLY).apply()
    }

    fun setLastContextInjectionDebug(context: Context, debugText: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_CONTEXT_INJECTION_DEBUG, debugText)
            .putLong(KEY_LAST_CONTEXT_INJECTION_AT_MS, System.currentTimeMillis())
            .apply()
    }

    fun getLastContextInjectionDebug(context: Context): String {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LAST_CONTEXT_INJECTION_DEBUG, "")
            .orEmpty()
    }

    fun getLastContextInjectionAtMs(context: Context): Long {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_LAST_CONTEXT_INJECTION_AT_MS, 0L)
    }

    fun isTelegramRemoteControlEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_TELEGRAM_REMOTE_ENABLED, false)

    fun setTelegramRemoteControlEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_TELEGRAM_REMOTE_ENABLED, enabled).apply()
    }

    fun getTelegramAllowedChatId(context: Context): String =
        LocalAgentTelegramProtocol.normalizeChatId(
            prefs(context).getString(KEY_TELEGRAM_ALLOWED_CHAT_ID, ""),
        ).orEmpty()

    fun setTelegramAllowedChatId(context: Context, chatId: String): Boolean {
        val normalized = LocalAgentTelegramProtocol.normalizeChatId(chatId) ?: return false
        return prefs(context).edit()
            .putString(KEY_TELEGRAM_ALLOWED_CHAT_ID, normalized)
            .commit()
    }

    fun getTelegramBotToken(context: Context): String = runCatching {
        secretPrefs(context).getString(KEY_TELEGRAM_BOT_TOKEN, "")?.trim().orEmpty()
    }.getOrDefault("")

    /** Stores a user-provided token only in encrypted preferences, never in plaintext prefs. */
    fun setTelegramBotToken(context: Context, token: String): Boolean {
        val clean = token.trim()
        if (clean.length > MAX_TELEGRAM_TOKEN_CHARS || !LocalAgentTelegramProtocol.isValidBotToken(clean)) {
            return false
        }
        return runCatching {
            secretPrefs(context).edit().putString(KEY_TELEGRAM_BOT_TOKEN, clean).commit()
        }.getOrDefault(false)
    }

    fun clearTelegramBotToken(context: Context): Boolean = runCatching {
        secretPrefs(context).edit().remove(KEY_TELEGRAM_BOT_TOKEN).commit()
    }.getOrDefault(false)

    fun isTelegramConfigured(context: Context): Boolean =
        getTelegramBotToken(context).isNotBlank() && getTelegramAllowedChatId(context).isNotBlank()

    fun getTelegramUpdateOffset(context: Context): Long =
        prefs(context).getLong(KEY_TELEGRAM_UPDATE_OFFSET, 0L).coerceAtLeast(0L)

    /** Commit before handling an update so a process death cannot replay a phone-control command. */
    fun setTelegramUpdateOffset(context: Context, offset: Long): Boolean =
        prefs(context).edit().putLong(KEY_TELEGRAM_UPDATE_OFFSET, offset.coerceAtLeast(0L)).commit()

    fun getTelegramStatus(context: Context): String =
        prefs(context).getString(KEY_TELEGRAM_STATUS, "Disabled") ?: "Disabled"

    fun setTelegramStatus(context: Context, status: String) {
        prefs(context).edit().putString(KEY_TELEGRAM_STATUS, status).apply()
    }

    fun getTelegramLastError(context: Context): String =
        prefs(context).getString(KEY_TELEGRAM_LAST_ERROR, "(none)") ?: "(none)"

    fun setTelegramLastError(context: Context, error: String) {
        prefs(context).edit().putString(KEY_TELEGRAM_LAST_ERROR, error).apply()
    }

    fun clearTelegramLastError(context: Context) {
        setTelegramLastError(context, "(none)")
    }

    fun isScreenshotPlanningEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SCREENSHOT_PLANNING_ENABLED, false)

    fun setScreenshotPlanningEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit()
            .putBoolean(KEY_SCREENSHOT_PLANNING_ENABLED, enabled)
            // Requiring a fresh upload opt-in after capture is disabled is the safest default.
            .putBoolean(KEY_REMOTE_SCREENSHOT_UPLOAD_ENABLED, if (enabled) {
                isRemoteScreenshotUploadEnabled(context)
            } else {
                false
            })
            .apply()
    }

    fun isRemoteScreenshotUploadEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_REMOTE_SCREENSHOT_UPLOAD_ENABLED, false)

    fun setRemoteScreenshotUploadEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit()
            .putBoolean(
                KEY_REMOTE_SCREENSHOT_UPLOAD_ENABLED,
                enabled && isScreenshotPlanningEnabled(context),
            )
            .apply()
    }

    fun getScreenshotStatus(context: Context): String =
        prefs(context).getString(KEY_SCREENSHOT_STATUS, "Text-only planning") ?: "Text-only planning"

    fun setScreenshotStatus(context: Context, status: String) {
        prefs(context).edit().putString(KEY_SCREENSHOT_STATUS, status).apply()
    }

    fun isShizukuFallbackEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SHIZUKU_FALLBACK_ENABLED, false)

    fun setShizukuFallbackEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_SHIZUKU_FALLBACK_ENABLED, enabled).apply()
    }

    fun getShizukuStatus(context: Context): String =
        prefs(context).getString(KEY_SHIZUKU_STATUS, "Disabled") ?: "Disabled"

    fun setShizukuStatus(context: Context, status: String) {
        prefs(context).edit().putString(KEY_SHIZUKU_STATUS, status).apply()
    }
}
