package com.fersaiyan.cyanbridge.privacy

import android.content.Context

/**
 * Chapter 8 (Privacy settings): SharedPreferences-backed toggles.
 *
 * Defaults:
 * - Transcript storage: ON
 * - Redact names: ON (best-effort)
 * - Include full transcription in exports: OFF
 */
object PrivacyPrefs {
    private const val PREFS = "privacy_settings"

    private const val KEY_TRANSCRIPT_STORAGE = "transcript_storage_enabled"
    private const val KEY_REDACT_NAMES = "redact_names_enabled"
    private const val KEY_INCLUDE_FULL_TRANSCRIPT_IN_EXPORTS = "include_full_transcription_in_exports"

    fun isTranscriptStorageEnabled(context: Context): Boolean {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return p.getBoolean(KEY_TRANSCRIPT_STORAGE, true)
    }

    fun setTranscriptStorageEnabled(context: Context, enabled: Boolean) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        p.edit().putBoolean(KEY_TRANSCRIPT_STORAGE, enabled).apply()
    }

    fun isRedactNamesEnabled(context: Context): Boolean {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return p.getBoolean(KEY_REDACT_NAMES, true)
    }

    fun setRedactNamesEnabled(context: Context, enabled: Boolean) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        p.edit().putBoolean(KEY_REDACT_NAMES, enabled).apply()
    }

    fun isIncludeFullTranscriptionInExportsEnabled(context: Context): Boolean {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return p.getBoolean(KEY_INCLUDE_FULL_TRANSCRIPT_IN_EXPORTS, false)
    }

    fun setIncludeFullTranscriptionInExportsEnabled(context: Context, enabled: Boolean) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        p.edit().putBoolean(KEY_INCLUDE_FULL_TRANSCRIPT_IN_EXPORTS, enabled).apply()
    }

    fun clear(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        p.edit().clear().apply()
    }
}
