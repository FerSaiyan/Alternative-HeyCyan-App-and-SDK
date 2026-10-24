package com.fersaiyan.cyanbridge.agent

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.fersaiyan.cyanbridge.localagent.LocalAgentTelegramProtocol
import com.fersaiyan.cyanbridge.shared.glasses.GlassesAssistantMode
import com.fersaiyan.cyanbridge.shared.settings.AgentProviderType

/**
 * Local agent settings in `local_agent_prefs`: automation and routing (provider, glasses mode,
 * capture), runtime status and approvals, Telegram/screenshot/Shizuku controls, and the
 * on-device runtime toggle. The Telegram bot token lives in encrypted `local_agent_secrets`.
 */
object LocalAgentPrefs {
    private const val PREFS = "local_agent_prefs"
    private const val KEY_PROVIDER_TYPE = "provider_type"
    private const val KEY_GLASSES_ASSISTANT_MODE = "glasses_assistant_mode"
    private const val KEY_REQUIRE_CONFIRMATION = "require_confirmation"
    private const val KEY_MAX_STEPS = "max_steps"
    private const val KEY_AUTOMATION_ENABLED = "automation_enabled"

    // Fast embedding gate (opt-in; model weights stay outside Git).
    private const val KEY_EMBEDDING_DECISION_ENABLED = "embedding_decision_enabled"
    private const val KEY_EMBEDDING_MODEL_PATH = "embedding_model_path"
    private const val KEY_EMBEDDING_MARGIN_THRESHOLD = "embedding_margin_threshold"

    /** Opt-in fast path: EmbeddingGemma cosine gate ahead of the single-token LLM. Default off. */
    fun isEmbeddingDecisionEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_EMBEDDING_DECISION_ENABLED, false)
    }

    fun setEmbeddingDecisionEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_EMBEDDING_DECISION_ENABLED, enabled)
            .apply()
    }

    /** Absolute path to the embedding GGUF (HIL-provisioned; never bundled). Empty disables. */
    fun getEmbeddingModelPath(context: Context): String {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_EMBEDDING_MODEL_PATH, "").orEmpty().trim()
    }

    fun setEmbeddingModelPath(context: Context, path: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_EMBEDDING_MODEL_PATH, path.trim())
            .apply()
    }

    /** Minimum cosine margin for the embedding gate; below abstains to the LLM path. */
    fun getEmbeddingMarginThreshold(context: Context): Float {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getFloat(KEY_EMBEDDING_MARGIN_THRESHOLD, 0.10f).coerceIn(0f, 2f)
    }

    fun setEmbeddingMarginThreshold(context: Context, margin: Float) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putFloat(KEY_EMBEDDING_MARGIN_THRESHOLD, margin.coerceIn(0f, 2f))
            .apply()
    }
    // Screen content capture / memory
    // KEY_AUTO_CAPTURE_ENABLED is now legacy Accessibility capture state. Tasker-backed
    // AutoDiary uses its own key so enabling AutoDiary does not also wake the old
    // CyanBridge Accessibility observer and create duplicate captures.
    private const val KEY_AUTO_CAPTURE_ENABLED = "auto_capture_enabled"
    private const val KEY_TASKER_AUTO_DIARY_ENABLED = "tasker_auto_diary_enabled"
    private const val KEY_CAPTURE_INTERVAL_MIN = "capture_interval_min"
    private const val KEY_CAPTURE_BLACKLIST = "capture_blacklist"
    private const val KEY_HIDE_SYSTEM_APPS = "hide_system_apps"
    private const val KEY_DAILY_FACTS_REMINDER_ENABLED = "daily_facts_reminder_enabled"
    private const val KEY_DAILY_SUMMARY_AUTO_REFRESH_HOURS = "daily_summary_auto_refresh_hours"

    fun getProviderType(context: Context): AgentProviderType {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_PROVIDER_TYPE, null)
            ?.trim()
            ?.uppercase()
        return when (raw) {
            AgentProviderType.LOCAL_AGENT.name -> AgentProviderType.LOCAL_AGENT
            "API_MODELS" -> AgentProviderType.PRO_SUBSCRIPTION
            AgentProviderType.PRO_SUBSCRIPTION.name -> AgentProviderType.PRO_SUBSCRIPTION
            AgentProviderType.TASKER.name -> AgentProviderType.TASKER
            null, "" -> AgentProviderType.LOCAL_AGENT
            else -> AgentProviderType.TASKER
        }
    }

    fun setProviderType(context: Context, type: AgentProviderType) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PROVIDER_TYPE, type.name)
            .apply()
    }

    fun getGlassesAssistantMode(context: Context): GlassesAssistantMode {
        val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = preferences
            .getString(KEY_GLASSES_ASSISTANT_MODE, null)
            ?.trim()
            ?.uppercase()
        val mode = when (stored) {
            GlassesAssistantMode.CUSTOM_AI_PROVIDER.name,
            "CHOSEN_PROVIDER" -> GlassesAssistantMode.CUSTOM_AI_PROVIDER

            GlassesAssistantMode.PHONE_ASSISTANT.name,
            "GEMINI",
            "CHAT_GPT",
            "PHONE_DEFAULT" -> GlassesAssistantMode.PHONE_ASSISTANT

            null,
            "" -> GlassesAssistantMode.CUSTOM_AI_PROVIDER

            else -> GlassesAssistantMode.PHONE_ASSISTANT
        }
        if (stored != mode.name) {
            preferences.edit().putString(KEY_GLASSES_ASSISTANT_MODE, mode.name).apply()
        }
        return mode
    }

    fun setGlassesAssistantMode(context: Context, mode: GlassesAssistantMode) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_GLASSES_ASSISTANT_MODE, mode.name)
            .apply()
    }

    fun isLocalAgentAutomationEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTOMATION_ENABLED, false)
    }

    fun setLocalAgentAutomationEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_AUTOMATION_ENABLED, enabled)
            .apply()
    }

    fun isRequireConfirmationEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_REQUIRE_CONFIRMATION, true)
    }

    fun setRequireConfirmationEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_REQUIRE_CONFIRMATION, enabled)
            .apply()
    }

    fun getMaxSteps(context: Context): Int {
        val v = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_MAX_STEPS, 8)
        return v.coerceIn(1, 200)
    }

    fun setMaxSteps(context: Context, steps: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_MAX_STEPS, steps.coerceIn(1, 200))
            .apply()
    }

    /** Legacy CyanBridge Accessibility-based screen capture switch. */
    fun isAutoCaptureEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTO_CAPTURE_ENABLED, false)
    }

    /** Legacy CyanBridge Accessibility-based screen capture switch. */
    fun setAutoCaptureEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_AUTO_CAPTURE_ENABLED, enabled)
            .apply()
    }

    /**
     * Tasker-backed AutoDiary feature switch. Before the first migration write, fall back
     * to the legacy capture bit so already-enabled users can be migrated without losing
     * their setting. AutoDiaryService persists this new key before clearing the legacy bit.
     */
    fun isTaskerAutoDiaryEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return if (prefs.contains(KEY_TASKER_AUTO_DIARY_ENABLED)) {
            prefs.getBoolean(KEY_TASKER_AUTO_DIARY_ENABLED, false)
        } else {
            prefs.getBoolean(KEY_AUTO_CAPTURE_ENABLED, false)
        }
    }

    fun setTaskerAutoDiaryEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_TASKER_AUTO_DIARY_ENABLED, enabled)
            .apply()
    }

    fun getCaptureIntervalMin(context: Context): Int {
        val v = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_CAPTURE_INTERVAL_MIN, 10)
        return v.coerceIn(1, 24 * 60)
    }

    fun setCaptureIntervalMin(context: Context, minutes: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_CAPTURE_INTERVAL_MIN, minutes.coerceIn(1, 24 * 60))
            .apply()
    }

    fun getCaptureBlacklistPackages(context: Context): Set<String> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY_CAPTURE_BLACKLIST, null)
            ?: emptySet()
        return raw
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() }
            .toSet()
    }

    fun setCaptureBlacklistPackages(context: Context, packages: Set<String>) {
        // Use commit() for reliability: users may blacklist many apps at once and immediately
        // leave the screen; apply() is async and can be lost if the process is killed.
        val clean = packages
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() }
            .toSet()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(KEY_CAPTURE_BLACKLIST, HashSet(clean))
            .commit()
    }

    fun isHideSystemAppsEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_HIDE_SYSTEM_APPS, true)
    }

    fun setHideSystemAppsEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_HIDE_SYSTEM_APPS, enabled)
            .apply()
    }

    fun isDailyFactsReminderEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_DAILY_FACTS_REMINDER_ENABLED, true)
    }

    fun setDailyFactsReminderEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_DAILY_FACTS_REMINDER_ENABLED, enabled)
            .apply()
    }

    fun getDailySummaryAutoRefreshHours(context: Context): Int {
        val v = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_DAILY_SUMMARY_AUTO_REFRESH_HOURS, 3)
        return v.coerceIn(1, 24)
    }

    fun setDailySummaryAutoRefreshHours(context: Context, hours: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_DAILY_SUMMARY_AUTO_REFRESH_HOURS, hours.coerceIn(1, 24))
            .apply()
    }

    // Local agent runtime: status, approvals and remote controls.
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

    // On-device runtime toggle and model id.
    private const val KEY_ENABLED = "enabled"
    private const val KEY_MODEL_ID = "model_id"

    fun isEnabled(context: Context): Boolean {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return p.getBoolean(KEY_ENABLED, false)
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        p.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    /**
     * Optional identifier for the on-device model (e.g., a GGUF filename or a friendly alias).
     */
    fun getModelId(context: Context): String? {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return p.getString(KEY_MODEL_ID, null)
    }

    fun setModelId(context: Context, modelId: String?) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        p.edit().apply {
            if (modelId.isNullOrBlank()) remove(KEY_MODEL_ID) else putString(KEY_MODEL_ID, modelId.trim())
        }.apply()
    }
}
