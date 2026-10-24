package com.fersaiyan.cyanbridge.plugins.errandbrain

import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
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

data class ErrandBrainSettingsState(
    val enabled: Boolean,
    val autoCreateTasks: Boolean,
    val voiceCommands: Boolean,
    val reminderEnabled: Boolean,
    val defaultPriority: String,
    val defaultCategory: String,
    val maxHistory: Int,
    val customPrompt: String,
) {
    companion object {
        fun load(context: Context) = ErrandBrainSettingsState(
            enabled = ErrandBrainPreferences.isEnabled(context),
            autoCreateTasks = ErrandBrainPreferences.isAutoCreateTasks(context),
            voiceCommands = ErrandBrainPreferences.isVoiceCommands(context),
            reminderEnabled = ErrandBrainPreferences.isReminderEnabled(context),
            defaultPriority = ErrandBrainPreferences.getDefaultPriority(context),
            defaultCategory = ErrandBrainPreferences.getDefaultCategory(context),
            maxHistory = ErrandBrainPreferences.getMaxHistory(context),
            customPrompt = ErrandBrainPreferences.getCustomPrompt(context),
        )
    }
}

interface ErrandBrainSettingsActions {
    fun onBack()
    fun onEnabledChange(enabled: Boolean)
    fun onAutoCreateTasksChange(enabled: Boolean)
    fun onVoiceCommandsChange(enabled: Boolean)
    fun onReminderEnabledChange(enabled: Boolean)
    fun onDefaultPrioritySelected(priority: String)
    fun onDefaultCategorySelected(category: String)
    fun onCustomPromptChange(prompt: String)
    fun onMaxHistoryChange(maxHistory: Int)
    fun onClearHistory()
}

class ErrandBrainSettingsActivity : AppCompatActivity(), ErrandBrainSettingsActions {

    private lateinit var uiState: MutableState<ErrandBrainSettingsState>

    internal val state: ErrandBrainSettingsState get() = uiState.value

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        uiState = mutableStateOf(ErrandBrainSettingsState.load(this))
        setThemedComposeScreen {
            ErrandBrainSettingsScreen(state = uiState.value, actions = this)
        }
    }

    private inline fun update(transform: ErrandBrainSettingsState.() -> ErrandBrainSettingsState) {
        uiState.value = uiState.value.transform()
    }

    override fun onBack() = finish()

    override fun onEnabledChange(enabled: Boolean) {
        update { copy(enabled = enabled) }
        applyNativePluginSwitch(
            pluginId = NativePluginIds.ERRAND_BRAIN,
            enabled = enabled,
            persistPluginFlag = { ErrandBrainPreferences.setEnabled(this, it) },
            onDisabled = { ErrandBrainService.stop(this) },
        )
    }

    override fun onAutoCreateTasksChange(enabled: Boolean) {
        update { copy(autoCreateTasks = enabled) }
        ErrandBrainPreferences.setAutoCreateTasks(this, enabled)
    }

    override fun onVoiceCommandsChange(enabled: Boolean) {
        update { copy(voiceCommands = enabled) }
        ErrandBrainPreferences.setVoiceCommands(this, enabled)
    }

    override fun onReminderEnabledChange(enabled: Boolean) {
        update { copy(reminderEnabled = enabled) }
        ErrandBrainPreferences.setReminderEnabled(this, enabled)
    }

    override fun onDefaultPrioritySelected(priority: String) {
        update { copy(defaultPriority = priority) }
        ErrandBrainPreferences.setDefaultPriority(this, priority)
    }

    override fun onDefaultCategorySelected(category: String) {
        update { copy(defaultCategory = category) }
        ErrandBrainPreferences.setDefaultCategory(this, category)
    }

    override fun onCustomPromptChange(prompt: String) {
        if (prompt.length > MAX_CUSTOM_PROMPT_LENGTH) return
        update { copy(customPrompt = prompt) }
        ErrandBrainPreferences.setCustomPrompt(this, prompt)
    }

    override fun onMaxHistoryChange(maxHistory: Int) {
        update { copy(maxHistory = maxHistory) }
        ErrandBrainPreferences.setMaxHistory(this, maxHistory)
    }

    override fun onClearHistory() {
        ErrandBrainStore().clear(this)
        Toast.makeText(this, getString(R.string.compose_history_cleared), Toast.LENGTH_SHORT).show()
    }

    internal companion object {
        const val MAX_CUSTOM_PROMPT_LENGTH = 1000
    }
}

private val priorityOptions = listOf("low", "medium", "high", "urgent")
private val categoryOptions = listOf("personal", "work", "shopping", "health", "finance")

@Composable
fun ErrandBrainSettingsScreen(
    state: ErrandBrainSettingsState,
    actions: ErrandBrainSettingsActions,
) {
    PluginSettingsScaffold(
        title = stringResource(R.string.compose_plugin_settings_title, stringResource(R.string.compose_plugin_name_errand_brain)),
        onBack = actions::onBack,
    ) {
        // Section: General
        PluginSettingsSectionTitle(stringResource(R.string.compose_general))
        PluginSettingsCard {
            PluginSwitchRow(
                label = stringResource(R.string.compose_errand_enabled),
                checked = state.enabled,
                onCheckedChange = actions::onEnabledChange,
            )

            Spacer(modifier = Modifier.height(12.dp))

            PluginSwitchRow(
                label = stringResource(R.string.compose_auto_create_tasks_voice),
                checked = state.autoCreateTasks,
                onCheckedChange = actions::onAutoCreateTasksChange,
            )

            Spacer(modifier = Modifier.height(8.dp))

            PluginSwitchRow(
                label = stringResource(R.string.compose_voice_commands),
                checked = state.voiceCommands,
                onCheckedChange = actions::onVoiceCommandsChange,
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
                onCheckedChange = actions::onReminderEnabledChange,
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
                        onClick = { actions.onDefaultPrioritySelected(priority) },
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
                        onClick = { actions.onDefaultCategorySelected(category) },
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
            maxLength = ErrandBrainSettingsActivity.MAX_CUSTOM_PROMPT_LENGTH,
            minLines = 2,
            maxLines = 4,
            onValueChange = actions::onCustomPromptChange,
        )

        // Section: History
        PluginSettingsSectionTitle(stringResource(R.string.compose_task_history))
        PluginHistoryCard(
            maxHistoryLabel = stringResource(R.string.compose_max_stored_tasks, state.maxHistory),
            maxHistory = state.maxHistory,
            valueRange = 50f..1000f,
            steps = 94,
            onMaxHistoryChange = actions::onMaxHistoryChange,
            onClearHistory = actions::onClearHistory,
        )

        Spacer(modifier = Modifier.height(32.dp))
    }
}
