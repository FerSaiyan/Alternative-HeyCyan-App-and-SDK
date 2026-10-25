package com.fersaiyan.cyanbridge.plugins.livecaptionrelay

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
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
import com.fersaiyan.cyanbridge.plugins.settings.nativePluginViewModel
import com.fersaiyan.cyanbridge.shared.plugins.NativePluginIds
import com.fersaiyan.cyanbridge.shared.plugins.settings.LiveCaptionRelaySettingsViewModel
import com.fersaiyan.cyanbridge.shared.prefs.PluginPrefs.LiveCaptionRelay
import com.fersaiyan.cyanbridge.ui.NativePluginShortcutPreference
import com.fersaiyan.cyanbridge.ui.setThemedComposeScreen

class LiveCaptionRelaySettingsActivity : AppCompatActivity() {

    internal val viewModel by nativePluginViewModel(LiveCaptionRelay, ::LiveCaptionRelaySettingsViewModel)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setThemedComposeScreen { LiveCaptionRelaySettingsScreen(viewModel, onBack = ::finish) }
    }
}

private val languageOptions = listOf("en", "es", "fr", "de", "it", "pt", "zh", "ja", "ko")

@Composable
fun LiveCaptionRelaySettingsScreen(
    viewModel: LiveCaptionRelaySettingsViewModel,
    onBack: () -> Unit,
) {
    val state = viewModel.state
    PluginSettingsScaffold(
        title = stringResource(R.string.compose_plugin_settings_title, stringResource(R.string.compose_plugin_name_live_caption_relay)),
        onBack = onBack,
    ) {
        // Section: General
        PluginSettingsSectionTitle(stringResource(R.string.compose_general))
        PluginSettingsCard {
            PluginSwitchRow(
                label = stringResource(R.string.compose_live_caption_enabled),
                checked = state.enabled,
                onCheckedChange = viewModel::onEnabledChange,
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
                        onClick = { viewModel.onSourceLanguageSelected(lang) },
                        label = lang.uppercase(),
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            PluginSwitchRow(
                label = stringResource(R.string.compose_enable_translation),
                checked = state.translationEnabled,
                onCheckedChange = viewModel::onTranslationEnabledChange,
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
                            onClick = { viewModel.onTargetLanguageSelected(lang) },
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
            maxLength = LiveCaptionRelaySettingsViewModel.MAX_CUSTOM_PROMPT_LENGTH,
            minLines = 2,
            maxLines = 4,
            onValueChange = viewModel::onCustomPromptChange,
        )

        // Section: History
        PluginSettingsSectionTitle(stringResource(R.string.compose_caption_history))
        PluginHistoryCard(
            maxHistoryLabel = stringResource(R.string.compose_max_stored_captions, state.maxHistory),
            maxHistory = state.maxHistory,
            valueRange = 50f..500f,
            steps = 44,
            onMaxHistoryChange = viewModel::onMaxHistoryChange,
            onClearHistory = viewModel::onClearHistory,
        )

        Spacer(modifier = Modifier.height(32.dp))
    }
}
