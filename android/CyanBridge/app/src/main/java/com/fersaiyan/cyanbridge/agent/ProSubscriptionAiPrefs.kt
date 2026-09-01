package com.fersaiyan.cyanbridge.agent

import android.content.Context
import com.fersaiyan.cyanbridge.localmodels.settings.LocalGenerationSettings

object ProSubscriptionAiPrefs {
    private const val PREFS_NAME = "pro_subscription_ai_prefs"
    private const val KEY_REQUESTS_MODEL = "requests_model"
    private const val KEY_QUESTIONS_MODEL = "questions_model"
    private const val KEY_TASKS_MODEL = "tasks_model"
    private const val KEY_SYSTEM_PROMPT = "system_prompt"

    private const val DEFAULT_MODEL = "auto"
    private const val LEGACY_FREE_GEMMA_MODEL = "google/gemma-4-26b-a4b-it:free"
    private const val GEMMA_MODEL = "google/gemma-4-26b-a4b-it"
    private const val MAX_SYSTEM_PROMPT_CHARS = 4000

    private fun normalizeModel(model: String?): String {
        val clean = model.orEmpty().trim()
        if (clean.isBlank()) return DEFAULT_MODEL
        val withoutMultiplier = clean
            .replace(Regex("\\s*\\(\\s*\\d+\\s*x\\s*\\)\\s*$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*\\(\\s*x\\s*\\d+\\s*\\)\\s*$", RegexOption.IGNORE_CASE), "")
            .trim()
        val withoutDecoratedId = withoutMultiplier.substringBefore(" · ").trim()
        return withoutDecoratedId.ifBlank { DEFAULT_MODEL }.let {
            if (it.equals(LEGACY_FREE_GEMMA_MODEL, ignoreCase = true)) GEMMA_MODEL else it
        }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getRequestsModel(context: Context): String =
        normalizeModel(prefs(context).getString(KEY_REQUESTS_MODEL, DEFAULT_MODEL))

    fun setRequestsModel(context: Context, model: String) {
        prefs(context).edit().putString(KEY_REQUESTS_MODEL, normalizeModel(model)).apply()
    }

    private const val LIVE_MODEL = "google/gemini-3.1-flash-live-preview"

    fun getQuestionsModel(context: Context): String {
        val stored = prefs(context).getString(KEY_QUESTIONS_MODEL, null)
        // Default for multimodal (image/voice) is Gemini Live until user changes it manually in Pro settings
        if (stored == null) return LIVE_MODEL
        val normalized = normalizeModel(stored)
        // Treat "auto" (legacy default) as Live for new installs, but respect explicit user choice of other vision models
        return if (normalized.equals(DEFAULT_MODEL, ignoreCase = true)) LIVE_MODEL else normalized
    }

    fun setQuestionsModel(context: Context, model: String) {
        prefs(context).edit().putString(KEY_QUESTIONS_MODEL, normalizeModel(model)).apply()
    }

    fun getTasksModel(context: Context): String =
        normalizeModel(prefs(context).getString(KEY_TASKS_MODEL, DEFAULT_MODEL))

    fun setTasksModel(context: Context, model: String) {
        prefs(context).edit().putString(KEY_TASKS_MODEL, normalizeModel(model)).apply()
    }

    fun getSystemPrompt(context: Context): String {
        val stored = prefs(context).getString(KEY_SYSTEM_PROMPT, null)
        return LocalGenerationSettings.migrateDefaultSystemPrompt(stored.orEmpty())
            .trim()
            .ifBlank { LocalGenerationSettings.DEFAULT_SYSTEM_PROMPT }
            .take(MAX_SYSTEM_PROMPT_CHARS)
    }

    fun setSystemPrompt(context: Context, prompt: String) {
        prefs(context).edit().putString(KEY_SYSTEM_PROMPT, prompt.take(MAX_SYSTEM_PROMPT_CHARS)).apply()
    }

    fun resetSystemPrompt(context: Context) {
        prefs(context).edit().remove(KEY_SYSTEM_PROMPT).apply()
    }
}
