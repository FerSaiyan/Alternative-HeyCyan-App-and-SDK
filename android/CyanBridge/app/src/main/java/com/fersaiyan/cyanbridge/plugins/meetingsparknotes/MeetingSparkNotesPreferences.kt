package com.fersaiyan.cyanbridge.plugins.meetingsparknotes

import android.content.Context

object MeetingSparkNotesPreferences {
    private const val PREFS = "meeting_spark_notes_prefs"

    private const val KEY_ENABLED = "enabled"
    private const val KEY_AUTO_RECORD = "auto_record"
    private const val KEY_SUMMARY_STYLE = "summary_style"
    private const val KEY_INCLUDE_PARTICIPANTS = "include_participants"
    private const val KEY_INCLUDE_ACTION_ITEMS = "include_action_items"
    private const val KEY_INCLUDE_TIMESTAMPS = "include_timestamps"
    private const val KEY_CLOUD_MODEL_ID = "cloud_model_id"
    private const val KEY_MAX_HISTORY = "max_history"
    private const val KEY_CUSTOM_PROMPT = "custom_prompt"
    private const val KEY_LAST_SUMMARIZED_SESSION_ID = "last_summarized_session_id"
    private const val KEY_LAST_SAVED_NOTE_ID = "last_saved_note_id"
    private const val MAX_CUSTOM_PROMPT_CHARS = 1_500

    private const val DEFAULT_ENABLED = false
    private const val DEFAULT_AUTO_RECORD = true
    private const val DEFAULT_SUMMARY_STYLE = "concise"
    private const val DEFAULT_INCLUDE_PARTICIPANTS = true
    private const val DEFAULT_INCLUDE_ACTION_ITEMS = true
    private const val DEFAULT_INCLUDE_TIMESTAMPS = true
    private const val DEFAULT_CLOUD_MODEL_ID = "deepseek/deepseek-v4-flash"
    private const val DEFAULT_MAX_HISTORY = 50

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, DEFAULT_ENABLED)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun isAutoRecord(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO_RECORD, DEFAULT_AUTO_RECORD)

    fun setAutoRecord(context: Context, autoRecord: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_RECORD, autoRecord).apply()
    }

    fun getSummaryStyle(context: Context): String =
        prefs(context).getString(KEY_SUMMARY_STYLE, DEFAULT_SUMMARY_STYLE).orEmpty()

    fun setSummaryStyle(context: Context, style: String) {
        prefs(context).edit().putString(KEY_SUMMARY_STYLE, style).apply()
    }

    fun isIncludeParticipants(context: Context): Boolean =
        prefs(context).getBoolean(KEY_INCLUDE_PARTICIPANTS, DEFAULT_INCLUDE_PARTICIPANTS)

    fun setIncludeParticipants(context: Context, include: Boolean) {
        prefs(context).edit().putBoolean(KEY_INCLUDE_PARTICIPANTS, include).apply()
    }

    fun isIncludeActionItems(context: Context): Boolean =
        prefs(context).getBoolean(KEY_INCLUDE_ACTION_ITEMS, DEFAULT_INCLUDE_ACTION_ITEMS)

    fun setIncludeActionItems(context: Context, include: Boolean) {
        prefs(context).edit().putBoolean(KEY_INCLUDE_ACTION_ITEMS, include).apply()
    }

    fun isIncludeTimestamps(context: Context): Boolean =
        prefs(context).getBoolean(KEY_INCLUDE_TIMESTAMPS, DEFAULT_INCLUDE_TIMESTAMPS)

    fun setIncludeTimestamps(context: Context, include: Boolean) {
        prefs(context).edit().putBoolean(KEY_INCLUDE_TIMESTAMPS, include).apply()
    }

    fun getCloudModelId(context: Context): String =
        prefs(context).getString(KEY_CLOUD_MODEL_ID, DEFAULT_CLOUD_MODEL_ID).orEmpty()

    fun setCloudModelId(context: Context, modelId: String) {
        prefs(context).edit().putString(KEY_CLOUD_MODEL_ID, modelId).apply()
    }

    fun getMaxHistory(context: Context): Int =
        prefs(context).getInt(KEY_MAX_HISTORY, DEFAULT_MAX_HISTORY).coerceIn(10, 200)

    fun setMaxHistory(context: Context, count: Int) {
        prefs(context).edit().putInt(KEY_MAX_HISTORY, count.coerceIn(10, 200)).apply()
    }

    fun getCustomPrompt(context: Context): String =
        prefs(context).getString(KEY_CUSTOM_PROMPT, "").orEmpty()
            .take(MAX_CUSTOM_PROMPT_CHARS)

    fun setCustomPrompt(context: Context, prompt: String) {
        prefs(context).edit().putString(KEY_CUSTOM_PROMPT, prompt.trim().take(MAX_CUSTOM_PROMPT_CHARS)).apply()
    }

    fun getLastSummarizedSessionId(context: Context): Long =
        prefs(context).getLong(KEY_LAST_SUMMARIZED_SESSION_ID, 0L)

    fun getLastSavedNoteId(context: Context): Long =
        prefs(context).getLong(KEY_LAST_SAVED_NOTE_ID, 0L)

    fun setLastSavedNote(context: Context, captureSessionId: Long, noteId: Long) {
        prefs(context).edit()
            .putLong(KEY_LAST_SUMMARIZED_SESSION_ID, captureSessionId)
            .putLong(KEY_LAST_SAVED_NOTE_ID, noteId)
            .apply()
    }
}
