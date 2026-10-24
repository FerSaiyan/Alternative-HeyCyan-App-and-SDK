package com.fersaiyan.cyanbridge.plugins.meetingsparknotes

import android.content.Context
import com.fersaiyan.cyanbridge.shared.prefs.PluginPrefs.MeetingSparkNotes
import com.fersaiyan.cyanbridge.shared.prefs.edit
import com.fersaiyan.cyanbridge.shared.prefs.get
import com.fersaiyan.cyanbridge.shared.prefs.set

object MeetingSparkNotesPreferences {
    fun isEnabled(context: Context): Boolean = MeetingSparkNotes.enabled[context]

    fun setEnabled(context: Context, enabled: Boolean) {
        MeetingSparkNotes.enabled[context] = enabled
    }

    fun isAutoRecord(context: Context): Boolean = MeetingSparkNotes.autoRecord[context]

    fun setAutoRecord(context: Context, autoRecord: Boolean) {
        MeetingSparkNotes.autoRecord[context] = autoRecord
    }

    fun getSummaryStyle(context: Context): String = MeetingSparkNotes.summaryStyle[context]

    fun setSummaryStyle(context: Context, style: String) {
        MeetingSparkNotes.summaryStyle[context] = style
    }

    fun isIncludeParticipants(context: Context): Boolean = MeetingSparkNotes.includeParticipants[context]

    fun setIncludeParticipants(context: Context, include: Boolean) {
        MeetingSparkNotes.includeParticipants[context] = include
    }

    fun isIncludeActionItems(context: Context): Boolean = MeetingSparkNotes.includeActionItems[context]

    fun setIncludeActionItems(context: Context, include: Boolean) {
        MeetingSparkNotes.includeActionItems[context] = include
    }

    fun isIncludeTimestamps(context: Context): Boolean = MeetingSparkNotes.includeTimestamps[context]

    fun setIncludeTimestamps(context: Context, include: Boolean) {
        MeetingSparkNotes.includeTimestamps[context] = include
    }

    fun getCloudModelId(context: Context): String = MeetingSparkNotes.cloudModelId[context]

    fun setCloudModelId(context: Context, modelId: String) {
        MeetingSparkNotes.cloudModelId[context] = modelId
    }

    fun getMaxHistory(context: Context): Int = MeetingSparkNotes.maxHistory[context]

    fun setMaxHistory(context: Context, count: Int) {
        MeetingSparkNotes.maxHistory[context] = count
    }

    fun getCustomPrompt(context: Context): String = MeetingSparkNotes.customPrompt[context]

    fun setCustomPrompt(context: Context, prompt: String) {
        MeetingSparkNotes.customPrompt[context] = prompt
    }

    fun getLastSummarizedSessionId(context: Context): Long = MeetingSparkNotes.lastSummarizedSessionId[context]

    fun getLastSavedNoteId(context: Context): Long = MeetingSparkNotes.lastSavedNoteId[context]

    fun setLastSavedNote(context: Context, captureSessionId: Long, noteId: Long) {
        MeetingSparkNotes.edit(context) {
            this[MeetingSparkNotes.lastSummarizedSessionId] = captureSessionId
            this[MeetingSparkNotes.lastSavedNoteId] = noteId
        }
    }
}
