package com.fersaiyan.cyanbridge.shared.plugins.settings

import com.fersaiyan.cyanbridge.shared.platform.PlatformPreferences
import com.fersaiyan.cyanbridge.shared.plugins.NativePluginIds
import com.fersaiyan.cyanbridge.shared.prefs.PluginPrefs.ErrandBrain
import com.fersaiyan.cyanbridge.shared.prefs.get

data class ErrandBrainSettingsState(
    val enabled: Boolean,
    val autoCreateTasks: Boolean,
    val voiceCommands: Boolean,
    val reminderEnabled: Boolean,
    val defaultPriority: String,
    val defaultCategory: String,
    val maxHistory: Int,
    val customPrompt: String,
)

class ErrandBrainSettingsViewModel(
    store: PlatformPreferences,
    catalog: PlatformPreferences,
    host: NativePluginHost,
) : NativePluginSettingsViewModel<ErrandBrainSettingsState>(
    NativePluginIds.ERRAND_BRAIN, store, catalog, host,
    {
        ErrandBrainSettingsState(
            enabled = this[ErrandBrain.enabled],
            autoCreateTasks = this[ErrandBrain.autoCreateTasks],
            voiceCommands = this[ErrandBrain.voiceCommands],
            reminderEnabled = this[ErrandBrain.reminderEnabled],
            defaultPriority = this[ErrandBrain.defaultPriority],
            defaultCategory = this[ErrandBrain.defaultCategory],
            maxHistory = this[ErrandBrain.maxHistory],
            customPrompt = this[ErrandBrain.customPrompt],
        )
    },
) {
    fun onEnabledChange(enabled: Boolean) = switchPlugin(ErrandBrain.enabled, enabled) { copy(enabled = it) }
    fun onAutoCreateTasksChange(enabled: Boolean) =
        save(ErrandBrain.autoCreateTasks, enabled) { copy(autoCreateTasks = it) }
    fun onVoiceCommandsChange(enabled: Boolean) = save(ErrandBrain.voiceCommands, enabled) { copy(voiceCommands = it) }
    fun onReminderEnabledChange(enabled: Boolean) =
        save(ErrandBrain.reminderEnabled, enabled) { copy(reminderEnabled = it) }
    fun onDefaultPrioritySelected(priority: String) =
        save(ErrandBrain.defaultPriority, priority) { copy(defaultPriority = it) }
    fun onDefaultCategorySelected(category: String) =
        save(ErrandBrain.defaultCategory, category) { copy(defaultCategory = it) }
    fun onCustomPromptChange(prompt: String) =
        savePrompt(ErrandBrain.customPrompt, prompt, MAX_CUSTOM_PROMPT_LENGTH) { copy(customPrompt = it) }
    fun onMaxHistoryChange(maxHistory: Int) = save(ErrandBrain.maxHistory, maxHistory) { copy(maxHistory = it) }

    companion object {
        const val MAX_CUSTOM_PROMPT_LENGTH = 1000
    }
}
