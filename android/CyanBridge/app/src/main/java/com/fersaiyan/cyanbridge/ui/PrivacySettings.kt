package com.fersaiyan.cyanbridge.ui

import android.content.Context

/**
 * Central place for privacy-related settings.
 *
 * Note: These toggles intentionally do NOT change the existing audio pipeline or transcription logic.
 * They only persist user preferences so other parts of the app can consult them.
 */
object PrivacySettings {
    private const val PREFS = "cyanbridge_privacy"

    private const val KEY_TRANSCRIPT_STORAGE_ENABLED = "transcript_storage_enabled"
    private const val KEY_REDACTION_ENABLED = "redaction_enabled"

    /** Keep transcripts unless the user explicitly opts out. */
    const val DEFAULT_TRANSCRIPT_STORAGE_ENABLED = true

    /** Privacy-first default: ON */
    const val DEFAULT_REDACTION_ENABLED = true

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isTranscriptStorageEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_TRANSCRIPT_STORAGE_ENABLED, DEFAULT_TRANSCRIPT_STORAGE_ENABLED)

    fun setTranscriptStorageEnabled(context: Context, enabled: Boolean) {
        prefs(context)
            .edit()
            .putBoolean(KEY_TRANSCRIPT_STORAGE_ENABLED, enabled)
            .apply()
    }

    fun isRedactionEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_REDACTION_ENABLED, DEFAULT_REDACTION_ENABLED)

    fun setRedactionEnabled(context: Context, enabled: Boolean) {
        prefs(context)
            .edit()
            .putBoolean(KEY_REDACTION_ENABLED, enabled)
            .apply()
    }
}
