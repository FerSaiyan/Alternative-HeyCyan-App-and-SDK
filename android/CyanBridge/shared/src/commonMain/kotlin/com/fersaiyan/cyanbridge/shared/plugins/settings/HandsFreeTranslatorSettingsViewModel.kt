package com.fersaiyan.cyanbridge.shared.plugins.settings

import com.fersaiyan.cyanbridge.shared.platform.PlatformPreferences
import com.fersaiyan.cyanbridge.shared.plugins.NativePluginIds
import com.fersaiyan.cyanbridge.shared.prefs.PluginPrefs.HandsFreeTranslator
import com.fersaiyan.cyanbridge.shared.prefs.get

data class HandsFreeTranslatorSettingsState(
    val enabled: Boolean,
    val sourceLanguage: String,
    val targetLanguage: String,
    val autoDetect: Boolean,
    val speakTranslation: Boolean,
    val maxHistory: Int,
    val customPrompt: String,
)

class HandsFreeTranslatorSettingsViewModel(
    store: PlatformPreferences,
    catalog: PlatformPreferences,
    host: NativePluginHost,
) : NativePluginSettingsViewModel<HandsFreeTranslatorSettingsState>(
    NativePluginIds.HANDS_FREE_TRANSLATOR, store, catalog, host,
    {
        HandsFreeTranslatorSettingsState(
            enabled = this[HandsFreeTranslator.enabled],
            sourceLanguage = this[HandsFreeTranslator.sourceLanguage],
            targetLanguage = this[HandsFreeTranslator.targetLanguage],
            autoDetect = this[HandsFreeTranslator.autoDetect],
            speakTranslation = this[HandsFreeTranslator.speakTranslation],
            maxHistory = this[HandsFreeTranslator.maxHistory],
            customPrompt = this[HandsFreeTranslator.customPrompt],
        )
    },
) {
    fun onEnabledChange(enabled: Boolean) = switchPlugin(HandsFreeTranslator.enabled, enabled) { copy(enabled = it) }
    fun onAutoDetectChange(enabled: Boolean) = save(HandsFreeTranslator.autoDetect, enabled) { copy(autoDetect = it) }
    fun onSourceLanguageSelected(language: String) =
        save(HandsFreeTranslator.sourceLanguage, language) { copy(sourceLanguage = it) }
    fun onTargetLanguageSelected(language: String) =
        save(HandsFreeTranslator.targetLanguage, language) { copy(targetLanguage = it) }
    fun onSpeakTranslationChange(enabled: Boolean) =
        save(HandsFreeTranslator.speakTranslation, enabled) { copy(speakTranslation = it) }
    fun onCustomPromptChange(prompt: String) =
        savePrompt(HandsFreeTranslator.customPrompt, prompt, MAX_CUSTOM_PROMPT_LENGTH) { copy(customPrompt = it) }
    fun onMaxHistoryChange(maxHistory: Int) = save(HandsFreeTranslator.maxHistory, maxHistory) { copy(maxHistory = it) }

    companion object {
        const val MAX_CUSTOM_PROMPT_LENGTH = 1000
    }
}
