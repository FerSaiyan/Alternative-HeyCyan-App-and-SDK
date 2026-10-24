package com.fersaiyan.cyanbridge.plugins.livecaptionrelay

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

data class LiveCaptionRelaySettingsState(
    val enabled: Boolean,
    val sourceLanguage: String,
    val targetLanguage: String,
    val translationEnabled: Boolean,
    val maxHistory: Int,
    val customPrompt: String,
) {
    companion object {
        fun load(context: Context) = LiveCaptionRelaySettingsState(
            enabled = LiveCaptionRelayPreferences.isEnabled(context),
            sourceLanguage = LiveCaptionRelayPreferences.getSourceLanguage(context),
            targetLanguage = LiveCaptionRelayPreferences.getTargetLanguage(context),
            translationEnabled = LiveCaptionRelayPreferences.isTranslationEnabled(context),
            maxHistory = LiveCaptionRelayPreferences.getMaxHistory(context),
            customPrompt = LiveCaptionRelayPreferences.getCustomPrompt(context),
        )
    }
}

interface LiveCaptionRelaySettingsActions {
    fun onBack()
    fun onEnabledChange(enabled: Boolean)
    fun onSourceLanguageSelected(language: String)
    fun onTranslationEnabledChange(enabled: Boolean)
    fun onTargetLanguageSelected(language: String)
    fun onCustomPromptChange(prompt: String)
    fun onMaxHistoryChange(maxHistory: Int)
    fun onClearHistory()
}

class LiveCaptionRelaySettingsActivity : AppCompatActivity(), LiveCaptionRelaySettingsActions {

    private lateinit var uiState: MutableState<LiveCaptionRelaySettingsState>

    internal val state: LiveCaptionRelaySettingsState get() = uiState.value

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        uiState = mutableStateOf(LiveCaptionRelaySettingsState.load(this))
        setThemedComposeScreen {
            LiveCaptionRelaySettingsScreen(state = uiState.value, actions = this)
        }
    }

    private inline fun update(transform: LiveCaptionRelaySettingsState.() -> LiveCaptionRelaySettingsState) {
        uiState.value = uiState.value.transform()
    }

    override fun onBack() = finish()

    override fun onEnabledChange(enabled: Boolean) {
        update { copy(enabled = enabled) }
        applyNativePluginSwitch(
            pluginId = NativePluginIds.LIVE_CAPTION_RELAY,
            enabled = enabled,
            persistPluginFlag = { LiveCaptionRelayPreferences.setEnabled(this, it) },
            onDisabled = { LiveCaptionRelayService.stop(this) },
        )
    }

    override fun onSourceLanguageSelected(language: String) {
        update { copy(sourceLanguage = language) }
        LiveCaptionRelayPreferences.setSourceLanguage(this, language)
    }

    override fun onTranslationEnabledChange(enabled: Boolean) {
        update { copy(translationEnabled = enabled) }
        LiveCaptionRelayPreferences.setTranslationEnabled(this, enabled)
    }

    override fun onTargetLanguageSelected(language: String) {
        update { copy(targetLanguage = language) }
        LiveCaptionRelayPreferences.setTargetLanguage(this, language)
    }

    override fun onCustomPromptChange(prompt: String) {
        if (prompt.length > MAX_CUSTOM_PROMPT_LENGTH) return
        update { copy(customPrompt = prompt) }
        LiveCaptionRelayPreferences.setCustomPrompt(this, prompt)
    }

    override fun onMaxHistoryChange(maxHistory: Int) {
        update { copy(maxHistory = maxHistory) }
        LiveCaptionRelayPreferences.setMaxHistory(this, maxHistory)
    }

    override fun onClearHistory() {
        LiveCaptionRelayStore().clear(this)
        Toast.makeText(this, getString(R.string.compose_history_cleared), Toast.LENGTH_SHORT).show()
    }

    internal companion object {
        const val MAX_CUSTOM_PROMPT_LENGTH = 1000
    }
}

private val languageOptions = listOf("en", "es", "fr", "de", "it", "pt", "zh", "ja", "ko")

@Composable
fun LiveCaptionRelaySettingsScreen(
    state: LiveCaptionRelaySettingsState,
    actions: LiveCaptionRelaySettingsActions,
) {
    PluginSettingsScaffold(
        title = stringResource(R.string.compose_plugin_settings_title, stringResource(R.string.compose_plugin_name_live_caption_relay)),
        onBack = actions::onBack,
    ) {
        // Section: General
        PluginSettingsSectionTitle(stringResource(R.string.compose_general))
        PluginSettingsCard {
            PluginSwitchRow(
                label = stringResource(R.string.compose_live_caption_enabled),
                checked = state.enabled,
                onCheckedChange = actions::onEnabledChange,
            )
        }

        PluginSettingsSectionTitle(stringResource(R.string.compose_glasses_tab))
        NativePluginShortcutPreference(
            pluginId = NativePluginIds.LIVE_CAPTION_RELAY,
            pluginTitle = stringResource(R.string.compose_plugin_name_live_caption_relay),
        )

        // Section: Language
        PluginSettingsSectionTitle(stringResource(R.string.compose_language))
        PluginSettingsCard {
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

            Spacer(modifier = Modifier.height(12.dp))

            PluginSwitchRow(
                label = stringResource(R.string.compose_enable_translation),
                checked = state.translationEnabled,
                onCheckedChange = actions::onTranslationEnabledChange,
            )

            if (state.translationEnabled) {
                Spacer(modifier = Modifier.height(8.dp))
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
        }

        // Section: Custom Instructions
        PluginSettingsSectionTitle(stringResource(R.string.compose_custom_instructions))
        PluginCustomInstructionsCard(
            description = stringResource(R.string.compose_caption_generation_description),
            label = stringResource(R.string.compose_caption_instructions),
            placeholder = stringResource(R.string.compose_caption_hint),
            value = state.customPrompt,
            maxLength = LiveCaptionRelaySettingsActivity.MAX_CUSTOM_PROMPT_LENGTH,
            minLines = 2,
            maxLines = 4,
            onValueChange = actions::onCustomPromptChange,
        )

        // Section: History
        PluginSettingsSectionTitle(stringResource(R.string.compose_caption_history))
        PluginHistoryCard(
            maxHistoryLabel = stringResource(R.string.compose_max_stored_captions, state.maxHistory),
            maxHistory = state.maxHistory,
            valueRange = 50f..500f,
            steps = 44,
            onMaxHistoryChange = actions::onMaxHistoryChange,
            onClearHistory = actions::onClearHistory,
        )

        Spacer(modifier = Modifier.height(32.dp))
    }
}
