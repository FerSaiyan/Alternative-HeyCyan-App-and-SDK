package com.fersaiyan.cyanbridge.shared.plugins.settings

import com.fersaiyan.cyanbridge.shared.platform.PlatformPreferences
import com.fersaiyan.cyanbridge.shared.plugins.NativePluginIds
import com.fersaiyan.cyanbridge.shared.prefs.PluginPrefs.LiveCaptionRelay
import com.fersaiyan.cyanbridge.shared.prefs.get

data class LiveCaptionRelaySettingsState(
    val enabled: Boolean,
    val sourceLanguage: String,
    val targetLanguage: String,
    val translationEnabled: Boolean,
    val maxHistory: Int,
    val customPrompt: String,
)

class LiveCaptionRelaySettingsViewModel(
    store: PlatformPreferences,
    catalog: PlatformPreferences,
    host: NativePluginHost,
) : NativePluginSettingsViewModel<LiveCaptionRelaySettingsState>(
    NativePluginIds.LIVE_CAPTION_RELAY, store, catalog, host,
    {
        LiveCaptionRelaySettingsState(
            enabled = this[LiveCaptionRelay.enabled],
            sourceLanguage = this[LiveCaptionRelay.sourceLanguage],
            targetLanguage = this[LiveCaptionRelay.targetLanguage],
            translationEnabled = this[LiveCaptionRelay.translationEnabled],
            maxHistory = this[LiveCaptionRelay.maxHistory],
            customPrompt = this[LiveCaptionRelay.customPrompt],
        )
    },
) {
    fun onEnabledChange(enabled: Boolean) = switchPlugin(LiveCaptionRelay.enabled, enabled) { copy(enabled = it) }
    fun onSourceLanguageSelected(language: String) =
        save(LiveCaptionRelay.sourceLanguage, language) { copy(sourceLanguage = it) }
    fun onTranslationEnabledChange(enabled: Boolean) =
        save(LiveCaptionRelay.translationEnabled, enabled) { copy(translationEnabled = it) }
    fun onTargetLanguageSelected(language: String) =
        save(LiveCaptionRelay.targetLanguage, language) { copy(targetLanguage = it) }
    fun onCustomPromptChange(prompt: String) =
        savePrompt(LiveCaptionRelay.customPrompt, prompt, MAX_CUSTOM_PROMPT_LENGTH) { copy(customPrompt = it) }
    fun onMaxHistoryChange(maxHistory: Int) = save(LiveCaptionRelay.maxHistory, maxHistory) { copy(maxHistory = it) }

    companion object {
        const val MAX_CUSTOM_PROMPT_LENGTH = 1000
    }
}
