package com.fersaiyan.cyanbridge.privacy

import android.content.Context
import com.fersaiyan.cyanbridge.shared.prefs.MemoryPrefs.Privacy
import com.fersaiyan.cyanbridge.shared.prefs.clear
import com.fersaiyan.cyanbridge.shared.prefs.get
import com.fersaiyan.cyanbridge.shared.prefs.set

/**
 * Chapter 8 (Privacy settings): SharedPreferences-backed toggles.
 *
 * Defaults:
 * - Transcript storage: ON
 * - Redact names: ON (best-effort)
 * - Include full transcription in exports: OFF
 */
object PrivacyPrefs {
    fun isTranscriptStorageEnabled(context: Context): Boolean = Privacy.transcriptStorageEnabled[context]

    fun setTranscriptStorageEnabled(context: Context, enabled: Boolean) {
        Privacy.transcriptStorageEnabled[context] = enabled
    }

    fun isRedactNamesEnabled(context: Context): Boolean = Privacy.redactNamesEnabled[context]

    fun setRedactNamesEnabled(context: Context, enabled: Boolean) {
        Privacy.redactNamesEnabled[context] = enabled
    }

    fun isIncludeFullTranscriptionInExportsEnabled(context: Context): Boolean =
        Privacy.includeFullTranscriptionInExports[context]

    fun setIncludeFullTranscriptionInExportsEnabled(context: Context, enabled: Boolean) {
        Privacy.includeFullTranscriptionInExports[context] = enabled
    }

    fun clear(context: Context) = Privacy.clear(context)
}
