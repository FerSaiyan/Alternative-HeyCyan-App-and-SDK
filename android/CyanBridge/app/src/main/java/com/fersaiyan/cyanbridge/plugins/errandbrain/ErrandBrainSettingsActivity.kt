package com.fersaiyan.cyanbridge.plugins.errandbrain

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import com.fersaiyan.cyanbridge.shared.plugins.settings.ErrandBrainSettingsViewModel
import com.fersaiyan.cyanbridge.shared.prefs.PluginPrefs.ErrandBrain
import com.fersaiyan.cyanbridge.ui.NativePluginShortcutPreference
import com.fersaiyan.cyanbridge.ui.setThemedComposeScreen

class ErrandBrainSettingsActivity : AppCompatActivity() {

    internal val viewModel by nativePluginViewModel(ErrandBrain, ::ErrandBrainSettingsViewModel)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setThemedComposeScreen { ErrandBrainSettingsScreen(viewModel, onBack = ::finish) }
    }
}

private val priorityOptions = listOf("low", "medium", "high", "urgent")
private val categoryOptions = listOf("personal", "work", "shopping", "health", "finance")

@Composable
fun ErrandBrainSettingsScreen(
    viewModel: ErrandBrainSettingsViewModel,
    onBack: () -> Unit,
) {
    val state = viewModel.state
    PluginSettingsScaffold(
        title = stringResource(R.string.compose_plugin_settings_title, stringResource(R.string.compose_plugin_name_errand_brain)),
        onBack = onBack,
    ) {
        // Section: General
        PluginSettingsSectionTitle(stringResource(R.string.compose_general))
        PluginSettingsCard {
            PluginSwitchRow(
                label = stringResource(R.string.compose_errand_enabled),
                checked = state.enabled,
                onCheckedChange = viewModel::onEnabledChange,
            )

            Spacer(modifier = Modifier.height(12.dp))

            PluginSwitchRow(
                label = stringResource(R.string.compose_auto_create_tasks_voice),
                checked = state.autoCreateTasks,
                onCheckedChange = viewModel::onAutoCreateTasksChange,
            )

            Spacer(modifier = Modifier.height(8.dp))

            PluginSwitchRow(
                label = stringResource(R.string.compose_voice_commands),
                checked = state.voiceCommands,
                onCheckedChange = viewModel::onVoiceCommandsChange,
            )
        }

        PluginSettingsSectionTitle(stringResource(R.string.compose_glasses_tab))
        NativePluginShortcutPreference(
            pluginId = NativePluginIds.ERRAND_BRAIN,
            pluginTitle = stringResource(R.string.compose_plugin_name_errand_brain),
        )

        // Section: Reminders
        PluginSettingsSectionTitle(stringResource(R.string.compose_reminders))
        PluginSettingsCard {
            PluginSwitchRow(
                label = stringResource(R.string.compose_reminders_enabled),
                checked = state.reminderEnabled,
                onCheckedChange = viewModel::onReminderEnabledChange,
            )

            if (state.reminderEnabled) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    stringResource(R.string.compose_reminder_example),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Section: Defaults
        PluginSettingsSectionTitle(stringResource(R.string.compose_defaults))
        PluginSettingsCard {
            PluginFieldLabel(stringResource(R.string.compose_default_priority))
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                priorityOptions.forEach { priority ->
                    PluginFilterChip(
                        selected = state.defaultPriority == priority,
                        onClick = { viewModel.onDefaultPrioritySelected(priority) },
                        label = priority.replaceFirstChar { it.titlecase() },
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            PluginFieldLabel(stringResource(R.string.compose_default_category))
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                categoryOptions.take(5).forEach { category ->
                    PluginFilterChip(
                        selected = state.defaultCategory == category,
                        onClick = { viewModel.onDefaultCategorySelected(category) },
                        label = category.replaceFirstChar { it.titlecase() },
                    )
                }
            }
        }

        // Section: Custom Instructions
        PluginSettingsSectionTitle(stringResource(R.string.compose_custom_instructions))
        PluginCustomInstructionsCard(
            description = stringResource(R.string.compose_additional_task_instructions),
            label = stringResource(R.string.compose_task_parsing_instructions),
            placeholder = stringResource(R.string.compose_task_parsing_hint),
            value = state.customPrompt,
            maxLength = ErrandBrainSettingsViewModel.MAX_CUSTOM_PROMPT_LENGTH,
            minLines = 2,
            maxLines = 4,
            onValueChange = viewModel::onCustomPromptChange,
        )

        // Section: History
        PluginSettingsSectionTitle(stringResource(R.string.compose_task_history))
        PluginHistoryCard(
            maxHistoryLabel = stringResource(R.string.compose_max_stored_tasks, state.maxHistory),
            maxHistory = state.maxHistory,
            valueRange = 50f..1000f,
            steps = 94,
            onMaxHistoryChange = viewModel::onMaxHistoryChange,
            onClearHistory = viewModel::onClearHistory,
        )

        Spacer(modifier = Modifier.height(32.dp))
    }
}
