package com.fersaiyan.cyanbridge.plugins.meetingsparknotes

import android.content.Context
import android.os.Bundle
import android.widget.Toast
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
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
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
import com.fersaiyan.cyanbridge.plugins.settings.applyNativePluginSwitch
import com.fersaiyan.cyanbridge.shared.plugins.NativePluginIds
import com.fersaiyan.cyanbridge.ui.NativePluginShortcutPreference
import com.fersaiyan.cyanbridge.ui.setThemedComposeScreen

data class MeetingSparkNotesSettingsState(
    val enabled: Boolean,
    val summaryStyle: String,
    val includeParticipants: Boolean,
    val includeActionItems: Boolean,
    val maxHistory: Int,
    val customPrompt: String,
) {
    companion object {
        fun load(context: Context) = MeetingSparkNotesSettingsState(
            enabled = MeetingSparkNotesPreferences.isEnabled(context),
            summaryStyle = MeetingSparkNotesPreferences.getSummaryStyle(context),
            includeParticipants = MeetingSparkNotesPreferences.isIncludeParticipants(context),
            includeActionItems = MeetingSparkNotesPreferences.isIncludeActionItems(context),
            maxHistory = MeetingSparkNotesPreferences.getMaxHistory(context),
            customPrompt = MeetingSparkNotesPreferences.getCustomPrompt(context),
        )
    }
}

interface MeetingSparkNotesSettingsActions {
    fun onBack()
    fun onEnabledChange(enabled: Boolean)
    fun onSummaryStyleSelected(style: String)
    fun onIncludeParticipantsChange(include: Boolean)
    fun onIncludeActionItemsChange(include: Boolean)
    fun onCustomPromptChange(prompt: String)
    fun onMaxHistoryChange(maxHistory: Int)
    fun onClearHistory()
    fun onSummarizeCurrentMeeting()
}

class MeetingSparkNotesSettingsActivity : AppCompatActivity(), MeetingSparkNotesSettingsActions {

    private lateinit var uiState: MutableState<MeetingSparkNotesSettingsState>

    internal val state: MeetingSparkNotesSettingsState get() = uiState.value

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        uiState = mutableStateOf(MeetingSparkNotesSettingsState.load(this))
        setThemedComposeScreen {
            MeetingSparkNotesSettingsScreen(state = uiState.value, actions = this)
        }
    }

    private inline fun update(transform: MeetingSparkNotesSettingsState.() -> MeetingSparkNotesSettingsState) {
        uiState.value = uiState.value.transform()
    }

    override fun onBack() = finish()

    override fun onEnabledChange(enabled: Boolean) {
        update { copy(enabled = enabled) }
        applyNativePluginSwitch(
            pluginId = NativePluginIds.MEETING_SPARK_NOTES,
            enabled = enabled,
            persistPluginFlag = { MeetingSparkNotesPreferences.setEnabled(this, it) },
            onDisabled = { MeetingSparkNotesService.deactivate(this) },
        )
    }

    override fun onSummaryStyleSelected(style: String) {
        update { copy(summaryStyle = style) }
        MeetingSparkNotesPreferences.setSummaryStyle(this, style)
    }

    override fun onIncludeParticipantsChange(include: Boolean) {
        update { copy(includeParticipants = include) }
        MeetingSparkNotesPreferences.setIncludeParticipants(this, include)
    }

    override fun onIncludeActionItemsChange(include: Boolean) {
        update { copy(includeActionItems = include) }
        MeetingSparkNotesPreferences.setIncludeActionItems(this, include)
    }

    override fun onCustomPromptChange(prompt: String) {
        if (prompt.length > MAX_CUSTOM_PROMPT_LENGTH) return
        update { copy(customPrompt = prompt) }
        MeetingSparkNotesPreferences.setCustomPrompt(this, prompt)
    }

    override fun onMaxHistoryChange(maxHistory: Int) {
        update { copy(maxHistory = maxHistory) }
        MeetingSparkNotesPreferences.setMaxHistory(this, maxHistory)
    }

    override fun onClearHistory() {
        MeetingSparkNotesStore().clear(this)
        Toast.makeText(this, getString(R.string.compose_history_cleared), Toast.LENGTH_SHORT).show()
    }

    override fun onSummarizeCurrentMeeting() {
        MeetingSparkNotesService.summarize(this)
    }

    internal companion object {
        const val MAX_CUSTOM_PROMPT_LENGTH = 1500
    }
}

private val styleOptions = listOf("concise", "detailed", "action_focused")

@Composable
fun MeetingSparkNotesSettingsScreen(
    state: MeetingSparkNotesSettingsState,
    actions: MeetingSparkNotesSettingsActions,
) {
    PluginSettingsScaffold(
        title = stringResource(R.string.compose_plugin_settings_title, stringResource(R.string.compose_plugin_name_meeting_spark_notes)),
        onBack = actions::onBack,
    ) {
        // Section: General
        PluginSettingsSectionTitle(stringResource(R.string.compose_general))
        PluginSettingsCard {
            PluginSwitchRow(
                label = stringResource(R.string.compose_meeting_enabled),
                checked = state.enabled,
                onCheckedChange = actions::onEnabledChange,
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
                        onClick = { actions.onSummaryStyleSelected(style) },
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
                onCheckedChange = actions::onIncludeParticipantsChange,
            )

            Spacer(modifier = Modifier.height(8.dp))

            PluginSwitchRow(
                label = stringResource(R.string.compose_include_action_items),
                checked = state.includeActionItems,
                onCheckedChange = actions::onIncludeActionItemsChange,
            )
        }

        // Section: Custom Instructions
        PluginSettingsSectionTitle(stringResource(R.string.compose_custom_instructions))
        PluginCustomInstructionsCard(
            description = stringResource(R.string.compose_meeting_instructions_description),
            label = stringResource(R.string.compose_meeting_instructions),
            placeholder = stringResource(R.string.compose_meeting_hint),
            value = state.customPrompt,
            maxLength = MeetingSparkNotesSettingsActivity.MAX_CUSTOM_PROMPT_LENGTH,
            minLines = 3,
            maxLines = 6,
            onValueChange = actions::onCustomPromptChange,
        )

        // Section: History
        PluginSettingsSectionTitle(stringResource(R.string.compose_meeting_history))
        PluginHistoryCard(
            maxHistoryLabel = stringResource(R.string.compose_max_stored_summaries, state.maxHistory),
            maxHistory = state.maxHistory,
            valueRange = 10f..200f,
            steps = 18,
            onMaxHistoryChange = actions::onMaxHistoryChange,
            onClearHistory = actions::onClearHistory,
        )

        // Section: Actions
        PluginSettingsSectionTitle(stringResource(R.string.compose_actions))
        PluginSettingsCard {
            Button(
                onClick = actions::onSummarizeCurrentMeeting,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.compose_summarize_current_meeting))
            }
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}
