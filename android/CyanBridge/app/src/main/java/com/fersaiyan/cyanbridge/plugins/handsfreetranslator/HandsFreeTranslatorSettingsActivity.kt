package com.fersaiyan.cyanbridge.plugins.handsfreetranslator

import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.fersaiyan.cyanbridge.R
import com.fersaiyan.cyanbridge.plugins.settings.PluginCustomInstructionsCard
import com.fersaiyan.cyanbridge.plugins.settings.PluginFieldLabel
import com.fersaiyan.cyanbridge.plugins.settings.PluginFilterChip
import com.fersaiyan.cyanbridge.plugins.settings.PluginHistoryCard
import com.fersaiyan.cyanbridge.plugins.settings.PluginSettingsCard
import com.fersaiyan.cyanbridge.plugins.settings.PluginSettingsScaffold
import com.fersaiyan.cyanbridge.plugins.settings.PluginSettingsSectionTitle
import com.fersaiyan.cyanbridge.plugins.settings.PluginSwitchRow
import com.fersaiyan.cyanbridge.plugins.settings.applyNativePluginSwitch
import com.fersaiyan.cyanbridge.shared.plugins.NativePluginIds
import com.fersaiyan.cyanbridge.ui.NativePluginShortcutPreference
import com.fersaiyan.cyanbridge.ui.setThemedComposeScreen

data class HandsFreeTranslatorSettingsState(
    val enabled: Boolean,
    val sourceLanguage: String,
    val targetLanguage: String,
    val autoDetect: Boolean,
    val speakTranslation: Boolean,
    val maxHistory: Int,
    val customPrompt: String,
) {
    companion object {
        fun load(context: Context) = HandsFreeTranslatorSettingsState(
            enabled = HandsFreeTranslatorPreferences.isEnabled(context),
            sourceLanguage = HandsFreeTranslatorPreferences.getSourceLanguage(context),
            targetLanguage = HandsFreeTranslatorPreferences.getTargetLanguage(context),
            autoDetect = HandsFreeTranslatorPreferences.isAutoDetect(context),
            speakTranslation = HandsFreeTranslatorPreferences.isSpeakTranslation(context),
            maxHistory = HandsFreeTranslatorPreferences.getMaxHistory(context),
            customPrompt = HandsFreeTranslatorPreferences.getCustomPrompt(context),
        )
    }
}

interface HandsFreeTranslatorSettingsActions {
    fun onBack()
    fun onEnabledChange(enabled: Boolean)
    fun onAutoDetectChange(enabled: Boolean)
    fun onSourceLanguageSelected(language: String)
    fun onTargetLanguageSelected(language: String)
    fun onSpeakTranslationChange(enabled: Boolean)
    fun onCustomPromptChange(prompt: String)
    fun onMaxHistoryChange(maxHistory: Int)
    fun onClearHistory()
}

class HandsFreeTranslatorSettingsActivity : AppCompatActivity(), HandsFreeTranslatorSettingsActions {

    private lateinit var uiState: MutableState<HandsFreeTranslatorSettingsState>

    internal val state: HandsFreeTranslatorSettingsState get() = uiState.value

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        uiState = mutableStateOf(HandsFreeTranslatorSettingsState.load(this))
        setThemedComposeScreen {
            HandsFreeTranslatorSettingsScreen(state = uiState.value, actions = this)
        }
    }

    private inline fun update(transform: HandsFreeTranslatorSettingsState.() -> HandsFreeTranslatorSettingsState) {
        uiState.value = uiState.value.transform()
    }

    override fun onBack() = finish()

    override fun onEnabledChange(enabled: Boolean) {
        update { copy(enabled = enabled) }
        applyNativePluginSwitch(
            pluginId = NativePluginIds.HANDS_FREE_TRANSLATOR,
            enabled = enabled,
            persistPluginFlag = { HandsFreeTranslatorPreferences.setEnabled(this, it) },
            onDisabled = { HandsFreeTranslatorService.stop(this) },
        )
    }

    override fun onAutoDetectChange(enabled: Boolean) {
        update { copy(autoDetect = enabled) }
        HandsFreeTranslatorPreferences.setAutoDetect(this, enabled)
    }

    override fun onSourceLanguageSelected(language: String) {
        update { copy(sourceLanguage = language) }
        HandsFreeTranslatorPreferences.setSourceLanguage(this, language)
    }

    override fun onTargetLanguageSelected(language: String) {
        update { copy(targetLanguage = language) }
        HandsFreeTranslatorPreferences.setTargetLanguage(this, language)
    }

    override fun onSpeakTranslationChange(enabled: Boolean) {
        update { copy(speakTranslation = enabled) }
        HandsFreeTranslatorPreferences.setSpeakTranslation(this, enabled)
    }

    override fun onCustomPromptChange(prompt: String) {
        if (prompt.length > MAX_CUSTOM_PROMPT_LENGTH) return
        update { copy(customPrompt = prompt) }
        HandsFreeTranslatorPreferences.setCustomPrompt(this, prompt)
    }

    override fun onMaxHistoryChange(maxHistory: Int) {
        update { copy(maxHistory = maxHistory) }
        HandsFreeTranslatorPreferences.setMaxHistory(this, maxHistory)
    }

    override fun onClearHistory() {
        HandsFreeTranslatorStore().clear(this)
        Toast.makeText(this, getString(R.string.compose_history_cleared), Toast.LENGTH_SHORT).show()
    }

    internal companion object {
        const val MAX_CUSTOM_PROMPT_LENGTH = 1000
    }
}

private val languageOptions = listOf("en", "es", "fr", "de", "it", "pt", "zh", "ja", "ko")

@Composable
fun HandsFreeTranslatorSettingsScreen(
    state: HandsFreeTranslatorSettingsState,
    actions: HandsFreeTranslatorSettingsActions,
) {
    PluginSettingsScaffold(
        title = stringResource(R.string.compose_plugin_settings_title, stringResource(R.string.compose_plugin_name_handsfree_translator)),
        onBack = actions::onBack,
    ) {
        // Section: General
        PluginSettingsSectionTitle(stringResource(R.string.compose_general))
        PluginSettingsCard {
            PluginSwitchRow(
                label = stringResource(R.string.compose_handsfree_enabled),
                checked = state.enabled,
                onCheckedChange = actions::onEnabledChange,
            )
        }

        PluginSettingsSectionTitle(stringResource(R.string.compose_glasses_tab))
        NativePluginShortcutPreference(
            pluginId = NativePluginIds.HANDS_FREE_TRANSLATOR,
            pluginTitle = stringResource(R.string.compose_plugin_name_handsfree_translator),
        )

        // Section: Language
        PluginSettingsSectionTitle(stringResource(R.string.compose_language))
        PluginSettingsCard {
            PluginSwitchRow(
                label = stringResource(R.string.compose_auto_detect_language),
                checked = state.autoDetect,
                onCheckedChange = actions::onAutoDetectChange,
            )

            if (!state.autoDetect) {
                Spacer(modifier = Modifier.height(12.dp))
                PluginFieldLabel(stringResource(R.string.compose_source_language))
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    languageOptions.take(5).forEach { lang ->
                        PluginFilterChip(
                            selected = state.sourceLanguage == lang,
                            onClick = { actions.onSourceLanguageSelected(lang) },
                            label = lang.uppercase(),
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            PluginFieldLabel(stringResource(R.string.compose_target_language))
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                languageOptions.take(5).forEach { lang ->
                    PluginFilterChip(
                        selected = state.targetLanguage == lang,
                        onClick = { actions.onTargetLanguageSelected(lang) },
                        label = lang.uppercase(),
                    )
                }
            }
        }

        // Section: Output
        PluginSettingsSectionTitle(stringResource(R.string.compose_output))
        PluginSettingsCard {
            PluginSwitchRow(
                label = stringResource(R.string.compose_speak_translations),
                checked = state.speakTranslation,
                onCheckedChange = actions::onSpeakTranslationChange,
            )
        }

        // Section: Custom Instructions
        PluginSettingsSectionTitle(stringResource(R.string.compose_custom_instructions))
        PluginCustomInstructionsCard(
            description = stringResource(R.string.compose_translation_instructions_description),
            label = stringResource(R.string.compose_translation_instructions),
            placeholder = stringResource(R.string.compose_translation_hint),
            value = state.customPrompt,
            maxLength = HandsFreeTranslatorSettingsActivity.MAX_CUSTOM_PROMPT_LENGTH,
            minLines = 2,
            maxLines = 4,
            onValueChange = actions::onCustomPromptChange,
        )

        // Section: History
        PluginSettingsSectionTitle(stringResource(R.string.compose_translation_history))
        PluginHistoryCard(
            maxHistoryLabel = stringResource(R.string.compose_max_stored_translations, state.maxHistory),
            maxHistory = state.maxHistory,
            valueRange = 50f..500f,
            steps = 44,
            onMaxHistoryChange = actions::onMaxHistoryChange,
            onClearHistory = actions::onClearHistory,
        )

        Spacer(modifier = Modifier.height(32.dp))
    }
}
