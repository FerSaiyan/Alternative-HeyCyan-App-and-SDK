package com.fersaiyan.cyanbridge.agent

import android.content.Context
import com.fersaiyan.cyanbridge.shared.glasses.GlassesAssistantMode
import com.fersaiyan.cyanbridge.shared.settings.AgentProviderType

object LocalAgentPrefs {
    private const val PREFS = "local_agent_prefs"
    private const val KEY_PROVIDER_TYPE = "provider_type"
    private const val KEY_GLASSES_ASSISTANT_MODE = "glasses_assistant_mode"
    private const val KEY_REQUIRE_CONFIRMATION = "require_confirmation"
    private const val KEY_MAX_STEPS = "max_steps"
    private const val KEY_AUTOMATION_ENABLED = "automation_enabled"

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
}
