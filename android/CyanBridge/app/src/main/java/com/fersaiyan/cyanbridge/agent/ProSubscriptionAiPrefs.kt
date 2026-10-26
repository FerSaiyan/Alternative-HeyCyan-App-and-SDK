package com.fersaiyan.cyanbridge.agent

import android.content.Context
import com.fersaiyan.cyanbridge.localmodels.settings.LocalGenerationSettings
import com.fersaiyan.cyanbridge.shared.prefs.ProPrefs.Ai
import com.fersaiyan.cyanbridge.shared.prefs.edit
import com.fersaiyan.cyanbridge.shared.prefs.get
import com.fersaiyan.cyanbridge.shared.prefs.remove
import com.fersaiyan.cyanbridge.shared.prefs.set

object ProSubscriptionAiPrefs {
    private const val AUDIO_MODELS_MAX_AGE_MS = 24 * 60 * 60 * 1000L
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

    fun getRequestsModel(context: Context): String =
        normalizeModel(Ai.requestsModel[context])

    fun setRequestsModel(context: Context, model: String) {
        Ai.requestsModel[context] = normalizeModel(model)
    }

    private const val LIVE_MODEL = "google/gemini-3.1-flash-live-preview"

    fun shouldUseGeminiLiveForQuestions(isProActive: Boolean, questionsModel: String): Boolean {
        if (!isProActive) return true
        val normalized = normalizeModel(questionsModel)
        return normalized.equals(LIVE_MODEL, ignoreCase = true) ||
            normalized.equals("live", ignoreCase = true) ||
            normalized.equals(DEFAULT_MODEL, ignoreCase = true)
    }

    fun getQuestionsModel(context: Context): String {
        val stored = Ai.questionsModel[context]
        // Default for multimodal (image/voice) is Gemini Live until user changes it manually in Pro settings
        if (stored == null) return LIVE_MODEL
        val normalized = normalizeModel(stored)
        // Treat "auto" (legacy default) as Live for new installs, but respect explicit user choice of other vision models
        return if (normalized.equals(DEFAULT_MODEL, ignoreCase = true)) LIVE_MODEL else normalized
    }

    fun setQuestionsModel(context: Context, model: String) {
        Ai.questionsModel[context] = normalizeModel(model)
    }

    fun getTasksModel(context: Context): String =
        normalizeModel(Ai.tasksModel[context])

    fun setTasksModel(context: Context, model: String) {
        Ai.tasksModel[context] = normalizeModel(model)
    }

    fun getSystemPrompt(context: Context): String {
        val stored = Ai.systemPrompt[context]
        return LocalGenerationSettings.migrateDefaultSystemPrompt(stored.orEmpty())
            .trim()
            .ifBlank { LocalGenerationSettings.DEFAULT_SYSTEM_PROMPT }
            .take(MAX_SYSTEM_PROMPT_CHARS)
    }

    fun setSystemPrompt(context: Context, prompt: String) {
        Ai.systemPrompt[context] = prompt.take(MAX_SYSTEM_PROMPT_CHARS)
    }

    fun resetSystemPrompt(context: Context) {
        Ai.systemPrompt.remove(context)
    }

    /** Relay models that accept audio input (`supports_audio` in /models), cached by fetchModelCatalog. */
    fun saveAudioCapableModels(context: Context, modelIds: Collection<String>, nowMs: Long = System.currentTimeMillis()) {
        Ai.edit(context) {
            this[Ai.audioCapableModels] = modelIds.map { normalizeModel(it).lowercase() }.toSet()
            this[Ai.audioCapableModelsAtMs] = nowMs
        }
    }

    fun audioCapableModelsStale(context: Context, nowMs: Long = System.currentTimeMillis()): Boolean =
        nowMs - Ai.audioCapableModelsAtMs[context] > AUDIO_MODELS_MAX_AGE_MS

    /**
     * True when the questions model can hear a recording, so glasses questions go to the relay as
     * audio. Unknown (never fetched) means false: on-device STT first, as before.
     */
    fun questionsModelHearsAudio(context: Context): Boolean {
        val ids = Ai.audioCapableModels[context] ?: return false
        return normalizeModel(getQuestionsModel(context)).lowercase() in ids
    }
}
