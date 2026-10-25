package com.fersaiyan.cyanbridge.shared.plugins.settings

import com.fersaiyan.cyanbridge.shared.platform.PlatformPreferences
import com.fersaiyan.cyanbridge.shared.plugins.NativePluginIds
import com.fersaiyan.cyanbridge.shared.prefs.PluginPrefs.MeetingSparkNotes
import com.fersaiyan.cyanbridge.shared.prefs.get

data class MeetingSparkNotesSettingsState(
    val enabled: Boolean,
    val summaryStyle: String,
    val includeParticipants: Boolean,
    val includeActionItems: Boolean,
    val maxHistory: Int,
    val customPrompt: String,
)

class MeetingSparkNotesSettingsViewModel(
    store: PlatformPreferences,
    catalog: PlatformPreferences,
    host: NativePluginHost,
    private val summarizeCurrentMeeting: () -> Unit,
) : NativePluginSettingsViewModel<MeetingSparkNotesSettingsState>(
    NativePluginIds.MEETING_SPARK_NOTES, store, catalog, host,
    {
        MeetingSparkNotesSettingsState(
            enabled = this[MeetingSparkNotes.enabled],
            summaryStyle = this[MeetingSparkNotes.summaryStyle],
            includeParticipants = this[MeetingSparkNotes.includeParticipants],
            includeActionItems = this[MeetingSparkNotes.includeActionItems],
            maxHistory = this[MeetingSparkNotes.maxHistory],
            customPrompt = this[MeetingSparkNotes.customPrompt],
        )
    },
) {
    fun onEnabledChange(enabled: Boolean) = switchPlugin(MeetingSparkNotes.enabled, enabled) { copy(enabled = it) }
    fun onSummaryStyleSelected(style: String) = save(MeetingSparkNotes.summaryStyle, style) { copy(summaryStyle = it) }
    fun onIncludeParticipantsChange(include: Boolean) =
        save(MeetingSparkNotes.includeParticipants, include) { copy(includeParticipants = it) }
    fun onIncludeActionItemsChange(include: Boolean) =
        save(MeetingSparkNotes.includeActionItems, include) { copy(includeActionItems = it) }
    fun onCustomPromptChange(prompt: String) =
        savePrompt(MeetingSparkNotes.customPrompt, prompt, MAX_CUSTOM_PROMPT_LENGTH) { copy(customPrompt = it) }
    fun onMaxHistoryChange(maxHistory: Int) = save(MeetingSparkNotes.maxHistory, maxHistory) { copy(maxHistory = it) }
    fun onSummarizeCurrentMeeting() = summarizeCurrentMeeting()

    companion object {
        const val MAX_CUSTOM_PROMPT_LENGTH = 1500
    }
}
