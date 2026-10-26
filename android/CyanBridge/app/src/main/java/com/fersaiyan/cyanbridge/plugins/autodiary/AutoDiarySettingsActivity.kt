package com.fersaiyan.cyanbridge.plugins.autodiary

import com.fersaiyan.cyanbridge.ui.nav.AppRoute
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.fersaiyan.cyanbridge.R
import com.fersaiyan.cyanbridge.agent.LocalAgentPrefs
import com.fersaiyan.cyanbridge.localagent.daily.DailyFactsReminderScheduler
import com.fersaiyan.cyanbridge.localagent.dailyfacts.DailyBulletsSettings
import com.fersaiyan.cyanbridge.localagent.tasker.TaskerAgentBridge
import com.fersaiyan.cyanbridge.memoryvault.MemoryModeManager
import com.fersaiyan.cyanbridge.memoryvault.MemoryVaultService
import com.fersaiyan.cyanbridge.shared.prefs.MemoryPrefs
import com.fersaiyan.cyanbridge.shared.prefs.get
import com.fersaiyan.cyanbridge.shared.prefs.set
import com.fersaiyan.cyanbridge.ui.NativePluginShortcutPreference
import com.fersaiyan.cyanbridge.ui.localagent.DailyFactsDestination
import com.fersaiyan.cyanbridge.ui.localagent.DailySummaryDestination
import com.fersaiyan.cyanbridge.ui.localagent.ScreenCapturesDestination
import com.fersaiyan.cyanbridge.ui.setThemedComposeScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

data class AutoDiarySettingsState(
    /** Refreshed from [AutoDiaryService] on create, resume and every enable/disable. */
    val enabled: Boolean,
    /** Refreshed together with [enabled]. */
    val taskerObserverAvailable: Boolean,
    val dailyFactsReminder: Boolean,
    val autoSaveDailyFacts: Boolean,
    val extractUserFactCandidates: Boolean,
    val maxTokensPerBullet: Int,
    val screenOcrRetentionDays: Int,
    val bulletPrompt: String,
) {
    companion object {
        fun load(context: Context) = AutoDiarySettingsState(
            enabled = AutoDiaryService.isEnabled(context),
            taskerObserverAvailable = TaskerAgentBridge.isTaskerUiObserverAvailable(context),
            dailyFactsReminder = LocalAgentPrefs.isDailyFactsReminderEnabled(context),
            autoSaveDailyFacts = MemoryPrefs.ChatMemory.autoSaveDailyFacts[context],
            extractUserFactCandidates = MemoryPrefs.ChatMemory.extractUserFactCandidates[context],
            maxTokensPerBullet = DailyBulletsSettings.getMaxTokensPerBullet(context),
            screenOcrRetentionDays = MemoryModeManager.getScreenOcrRetentionDays(context),
            bulletPrompt = DailyBulletsSettings.getBulletPrompt(context),
        )
    }
}

interface AutoDiarySettingsActions {
    fun onBack()
    fun onEnabledChange(enabled: Boolean)
    fun onOpenTasker()
    fun onOpenCaptures()
    fun onScreenOcrRetentionDaysChange(days: Int)
    fun onDailyFactsReminderChange(enabled: Boolean)
    fun onAutoSaveDailyFactsChange(enabled: Boolean)
    fun onExtractUserFactCandidatesChange(enabled: Boolean)
    fun onOpenSummary()
    fun onOpenDailyFactsDraft()
    fun onOpenConfirmedDailyFacts()
    fun onBulletPromptChange(prompt: String)
    fun onRestoreDefaultBulletPrompt()
    fun onMaxTokensPerBulletChange(maxTokens: Int)
}

class AutoDiarySettingsActivity : AppCompatActivity(), AutoDiarySettingsActions {
    private lateinit var uiState: MutableState<AutoDiarySettingsState>

    internal val state: AutoDiarySettingsState get() = uiState.value

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        uiState = mutableStateOf(AutoDiarySettingsState.load(this))
        refreshUi()
        setThemedComposeScreen {
            AutoDiarySettingsScreen(state = uiState.value, actions = this)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshUi()
    }

    private inline fun update(transform: AutoDiarySettingsState.() -> AutoDiarySettingsState) {
        uiState.value = uiState.value.transform()
    }

    override fun onBack() = finish()

    override fun onEnabledChange(enabled: Boolean) {
        if (enabled) AutoDiaryService.enable(this) else AutoDiaryService.disable(this)
        refreshUi()
    }

    private fun refreshUi() {
        val taskerObserverAvailable = TaskerAgentBridge.isTaskerUiObserverAvailable(this)
        val enabled = AutoDiaryService.isEnabled(this)
        update { copy(taskerObserverAvailable = taskerObserverAvailable, enabled = enabled) }
        AutoDiaryService.startIfEnabled(this)
    }

    override fun onOpenTasker() {
        packageManager.getLaunchIntentForPackage("net.dinglisch.android.taskerm")?.let(::startActivity)
    }

    override fun onOpenCaptures() {
        startActivity(AppRoute.SCREEN_CAPTURES.intent(this))
    }

    override fun onScreenOcrRetentionDaysChange(days: Int) {
        update { copy(screenOcrRetentionDays = days) }
        MemoryModeManager.setScreenOcrRetentionDays(this, days)
        lifecycleScope.launch(Dispatchers.Main) { MemoryVaultService.enforceScreenOcrRetention(this@AutoDiarySettingsActivity) }
    }

    override fun onDailyFactsReminderChange(enabled: Boolean) {
        update { copy(dailyFactsReminder = enabled) }
        LocalAgentPrefs.setDailyFactsReminderEnabled(this, enabled)
        DailyFactsReminderScheduler.scheduleIfEnabled(this, enabled)
    }

    override fun onAutoSaveDailyFactsChange(enabled: Boolean) {
        update { copy(autoSaveDailyFacts = enabled) }
        MemoryPrefs.ChatMemory.autoSaveDailyFacts[this] = enabled
    }

    override fun onExtractUserFactCandidatesChange(enabled: Boolean) {
        update { copy(extractUserFactCandidates = enabled) }
        MemoryPrefs.ChatMemory.extractUserFactCandidates[this] = enabled
    }

    override fun onOpenSummary() {
        startActivity(AppRoute.DAILY_SUMMARY.intent(this))
    }

    override fun onOpenDailyFactsDraft() {
        startActivity(
            AppRoute.DAILY_FACTS.intent(this)
                .putExtra(DailyFactsDestination.EXTRA_MODE, DailyFactsDestination.MODE_DRAFT),
        )
    }

    override fun onOpenConfirmedDailyFacts() {
        startActivity(
            AppRoute.DAILY_FACTS.intent(this)
                .putExtra(DailyFactsDestination.EXTRA_MODE, DailyFactsDestination.MODE_CONFIRMED),
        )
    }

    override fun onBulletPromptChange(prompt: String) {
        update { copy(bulletPrompt = prompt) }
        DailyBulletsSettings.setCustomBulletPrompt(this, prompt)
    }

    override fun onRestoreDefaultBulletPrompt() {
        DailyBulletsSettings.restoreDefaultBulletPrompt(this)
        update { copy(bulletPrompt = DailyBulletsSettings.DEFAULT_BULLET_PROMPT) }
    }

    override fun onMaxTokensPerBulletChange(maxTokens: Int) {
        update { copy(maxTokensPerBullet = maxTokens) }
        DailyBulletsSettings.setMaxTokensPerBullet(this, maxTokens)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutoDiarySettingsScreen(
    state: AutoDiarySettingsState,
    actions: AutoDiarySettingsActions,
) {
    val scrollState = rememberScrollState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.compose_plugin_settings_title, stringResource(R.string.compose_plugin_name_autodiary))) },
                navigationIcon = {
                    IconButton(onClick = actions::onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.compose_back))
                    }
                },
            )
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Build a private daily memory from Android screen context and conversations. " +
                    "Tasker owns the schedule, app exclusions and AutoInput observation; CyanBridge owns the encrypted memory, indexing, facts, summaries and RAG.",
                style = MaterialTheme.typography.bodyMedium,
            )
            SwitchSetting(
                label = stringResource(R.string.compose_plugin_enabled, stringResource(R.string.compose_plugin_name_autodiary)),
                checked = state.enabled,
                onCheckedChange = actions::onEnabledChange,
            )
            NativePluginShortcutPreference(
                pluginId = com.fersaiyan.cyanbridge.shared.plugins.NativePluginIds.AUTO_DIARY,
                pluginTitle = stringResource(R.string.compose_plugin_name_autodiary),
            )
            Text(stringResource(R.string.compose_screen_capture), style = MaterialTheme.typography.titleMedium)
            Text(
                when {
                    state.taskerObserverAvailable -> "Tasker and AutoInput are installed. The imported profile captures every 10 minutes by default. Edit the Time profile in Tasker to change the schedule."
                    state.enabled -> "AutoDiary is enabled, but Tasker or AutoInput is not detected. Install/import the Tasker profile before expecting screen captures."
                    else -> "Tasker + AutoInput are required for screen observation. CyanBridge Accessibility and installed-app visibility are not required."
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (state.taskerObserverAvailable) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
            )
            Text(
                "Excluded apps are configured in Tasker with the global variable %CB_AutoDiaryExcluded. " +
                    "Use package names separated by commas, spaces, semicolons or new lines. Excluded screen text is not sent to CyanBridge.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = actions::onOpenTasker, modifier = Modifier.fillMaxWidth()) {
                Text("Open Tasker schedule / exclusions")
            }
            OutlinedButton(onClick = actions::onOpenCaptures, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.compose_view_screen_captures))
            }
            NumberSetting(
                label = "Screen OCR retention (days)",
                value = state.screenOcrRetentionDays,
                range = 1..365,
                onValueChanged = actions::onScreenOcrRetentionDaysChange,
            )
            Text(stringResource(R.string.compose_daily_processing), style = MaterialTheme.typography.titleMedium)
            SwitchSetting(stringResource(R.string.compose_daily_facts_reminder), state.dailyFactsReminder, actions::onDailyFactsReminderChange)
            SwitchSetting(stringResource(R.string.compose_auto_save_daily_facts), state.autoSaveDailyFacts, actions::onAutoSaveDailyFactsChange)
            SwitchSetting(
                stringResource(R.string.compose_extract_user_fact_candidates),
                state.extractUserFactCandidates,
                actions::onExtractUserFactCandidatesChange,
            )
            Text(
                stringResource(R.string.compose_nightly_enrichment_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = actions::onOpenSummary, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.compose_open_daily_summary))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = actions::onOpenDailyFactsDraft, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.compose_edit_daily_facts))
                }
                OutlinedButton(onClick = actions::onOpenConfirmedDailyFacts, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.compose_view_confirmed_facts))
                }
            }
            Text(stringResource(R.string.compose_bulletization), style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = state.bulletPrompt,
                onValueChange = actions::onBulletPromptChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.compose_bullet_prompt)) },
                minLines = 3,
                maxLines = 7,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = actions::onRestoreDefaultBulletPrompt) { Text(stringResource(R.string.compose_restore_default_prompt)) }
                NumberSetting(
                    label = stringResource(R.string.compose_max_tokens_per_bullet),
                    value = state.maxTokensPerBullet,
                    range = 0..100_000,
                    onValueChanged = actions::onMaxTokensPerBulletChange,
                    modifier = Modifier.weight(1f),
                )
            }
            Text(stringResource(R.string.compose_shared_memory_privacy), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.compose_shared_memory_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun SwitchSetting(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun NumberSetting(
    label: String,
    value: Int,
    range: IntRange,
    onValueChanged: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    val parsed = text.toIntOrNull()
    val valid = parsed != null && parsed in range
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(
            value = text,
            onValueChange = {
                text = it
                it.toIntOrNull()?.takeIf { number -> number in range }?.let(onValueChanged)
            },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            isError = text.isNotBlank() && !valid,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
    }
}
