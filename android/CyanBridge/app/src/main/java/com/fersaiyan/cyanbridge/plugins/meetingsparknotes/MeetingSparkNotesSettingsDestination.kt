package com.fersaiyan.cyanbridge.plugins.meetingsparknotes

import com.fersaiyan.cyanbridge.ui.nav.NavScreen
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.fersaiyan.cyanbridge.R
import com.fersaiyan.cyanbridge.plugins.settings.PluginCustomInstructionsCard
import com.fersaiyan.cyanbridge.plugins.settings.PluginFilterChip
import com.fersaiyan.cyanbridge.plugins.settings.PluginHistoryCard
import com.fersaiyan.cyanbridge.plugins.settings.PluginSettingsCard
import com.fersaiyan.cyanbridge.plugins.settings.PluginSettingsScaffold
import com.fersaiyan.cyanbridge.plugins.settings.PluginSettingsSectionTitle
import com.fersaiyan.cyanbridge.plugins.settings.PluginSwitchRow
import com.fersaiyan.cyanbridge.plugins.settings.nativePluginViewModel
import com.fersaiyan.cyanbridge.shared.plugins.NativePluginIds
import com.fersaiyan.cyanbridge.shared.plugins.settings.MeetingSparkNotesSettingsViewModel
import com.fersaiyan.cyanbridge.shared.prefs.PluginPrefs.MeetingSparkNotes
import com.fersaiyan.cyanbridge.ui.NativePluginShortcutPreference

class MeetingSparkNotesSettingsDestination : NavScreen() {

    internal val viewModel by nativePluginViewModel(MeetingSparkNotes) { store, catalog, host ->
        val app = applicationContext
        MeetingSparkNotesSettingsViewModel(store, catalog, host) { MeetingSparkNotesService.summarize(app) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MeetingSparkNotesSettingsScreen(viewModel, onBack = ::finish) }
    }
}

private val styleOptions = listOf("concise", "detailed", "action_focused")

@Composable
fun MeetingSparkNotesSettingsScreen(
    viewModel: MeetingSparkNotesSettingsViewModel,
    onBack: () -> Unit,
) {
    val state = viewModel.state
    PluginSettingsScaffold(
        title = stringResource(R.string.compose_plugin_settings_title, stringResource(R.string.compose_plugin_name_meeting_spark_notes)),
        onBack = onBack,
    ) {
        // Section: General
        PluginSettingsSectionTitle(stringResource(R.string.compose_general))
        PluginSettingsCard {
            PluginSwitchRow(
                label = stringResource(R.string.compose_meeting_enabled),
                checked = state.enabled,
                onCheckedChange = viewModel::onEnabledChange,
            )
        }

        PluginSettingsSectionTitle(stringResource(R.string.compose_glasses_tab))
        NativePluginShortcutPreference(
            pluginId = NativePluginIds.MEETING_SPARK_NOTES,
            pluginTitle = stringResource(R.string.compose_plugin_name_meeting_spark_notes),
        )

        // Section: Summary Style
        PluginSettingsSectionTitle(stringResource(R.string.compose_summary_style))
        PluginSettingsCard {
            Text(
                stringResource(R.string.compose_summary_style_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                styleOptions.forEach { style ->
                    PluginFilterChip(
                        selected = state.summaryStyle == style,
                        onClick = { viewModel.onSummaryStyleSelected(style) },
                        label = stringResource(
                            when (style) {
                                "concise" -> R.string.compose_summary_style_concise
                                "detailed" -> R.string.compose_summary_style_detailed
                                else -> R.string.compose_summary_style_action_focused
                            },
                        ),
                    )
                }
            }
        }

        // Section: Content Options
        PluginSettingsSectionTitle(stringResource(R.string.compose_content_options))
        PluginSettingsCard {
            PluginSwitchRow(
                label = stringResource(R.string.compose_include_participants),
                checked = state.includeParticipants,
                onCheckedChange = viewModel::onIncludeParticipantsChange,
            )

            Spacer(modifier = Modifier.height(8.dp))

            PluginSwitchRow(
                label = stringResource(R.string.compose_include_action_items),
                checked = state.includeActionItems,
                onCheckedChange = viewModel::onIncludeActionItemsChange,
            )
        }

        // Section: Custom Instructions
        PluginSettingsSectionTitle(stringResource(R.string.compose_custom_instructions))
        PluginCustomInstructionsCard(
            description = stringResource(R.string.compose_meeting_instructions_description),
            label = stringResource(R.string.compose_meeting_instructions),
            placeholder = stringResource(R.string.compose_meeting_hint),
            value = state.customPrompt,
            maxLength = MeetingSparkNotesSettingsViewModel.MAX_CUSTOM_PROMPT_LENGTH,
            minLines = 3,
            maxLines = 6,
            onValueChange = viewModel::onCustomPromptChange,
        )

        // Section: History
        PluginSettingsSectionTitle(stringResource(R.string.compose_meeting_history))
        PluginHistoryCard(
            maxHistoryLabel = stringResource(R.string.compose_max_stored_summaries, state.maxHistory),
            maxHistory = state.maxHistory,
            valueRange = 10f..200f,
            steps = 18,
            onMaxHistoryChange = viewModel::onMaxHistoryChange,
            onClearHistory = viewModel::onClearHistory,
        )

        // Section: Actions
        PluginSettingsSectionTitle(stringResource(R.string.compose_actions))
        PluginSettingsCard {
            Button(
                onClick = viewModel::onSummarizeCurrentMeeting,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.compose_summarize_current_meeting))
            }
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}
