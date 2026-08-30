package com.fersaiyan.cyanbridge.plugins.autodiary

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.fersaiyan.cyanbridge.R
import com.fersaiyan.cyanbridge.agent.LocalAgentPrefs
import com.fersaiyan.cyanbridge.localagent.daily.DailyFactsReminderScheduler
import com.fersaiyan.cyanbridge.localagent.dailyfacts.DailyBulletsSettings
import com.fersaiyan.cyanbridge.localagent.tasker.TaskerAgentBridge
import com.fersaiyan.cyanbridge.memoryvault.MemoryModeManager
import com.fersaiyan.cyanbridge.memoryvault.MemoryVaultService
import com.fersaiyan.cyanbridge.ui.NativePluginShortcutPreference
import com.fersaiyan.cyanbridge.ui.localagent.DailyFactsActivity
import com.fersaiyan.cyanbridge.ui.localagent.DailySummaryActivity
import com.fersaiyan.cyanbridge.ui.localagent.ScreenCapturesActivity
import com.fersaiyan.cyanbridge.ui.installComposeHostWithLegacyAdapter
import com.fersaiyan.cyanbridge.ui.setThemedComposeContent
import kotlinx.coroutines.launch

class AutoDiarySettingsActivity : AppCompatActivity() {
    private var autoDiaryEnabled by mutableStateOf(false)
    private var taskerObserverAvailable by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        refreshUi()
        val composeView = installComposeHostWithLegacyAdapter(R.layout.activity_auto_diary_settings)
        setThemedComposeContent(composeView) {
            AutoDiarySettingsScreen(
                enabled = autoDiaryEnabled,
                taskerObserverAvailable = taskerObserverAvailable,
                onBack = ::finish,
                onEnabledChanged = ::setEnabled,
                onOpenTasker = ::openTasker,
                onOpenCaptures = { startActivity(Intent(this, ScreenCapturesActivity::class.java)) },
                onOpenSummary = { startActivity(Intent(this, DailySummaryActivity::class.java)) },
                onOpenDailyFactsDraft = {
                    startActivity(
                        Intent(this, DailyFactsActivity::class.java)
                            .putExtra(DailyFactsActivity.EXTRA_MODE, DailyFactsActivity.MODE_DRAFT),
                    )
                },
                onOpenConfirmedDailyFacts = {
                    startActivity(
                        Intent(this, DailyFactsActivity::class.java)
                            .putExtra(DailyFactsActivity.EXTRA_MODE, DailyFactsActivity.MODE_CONFIRMED),
                    )
                },
            )
        }
    }

    override fun onResume() {
        super.onResume()
        refreshUi()
    }

    private fun setEnabled(enabled: Boolean) {
        if (enabled) AutoDiaryService.enable(this) else AutoDiaryService.disable(this)
        refreshUi()
    }

    private fun refreshUi() {
        taskerObserverAvailable = TaskerAgentBridge.isTaskerUiObserverAvailable(this)
        autoDiaryEnabled = AutoDiaryService.isEnabled(this)
        AutoDiaryService.startIfEnabled(this)
    }

    private fun openTasker() {
        packageManager.getLaunchIntentForPackage("net.dinglisch.android.taskerm")?.let(::startActivity)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutoDiarySettingsScreen(
    enabled: Boolean,
    taskerObserverAvailable: Boolean,
    onBack: () -> Unit,
    onEnabledChanged: (Boolean) -> Unit,
    onOpenTasker: () -> Unit,
    onOpenCaptures: () -> Unit,
    onOpenSummary: () -> Unit,
    onOpenDailyFactsDraft: () -> Unit,
    onOpenConfirmedDailyFacts: () -> Unit,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    var reminder by remember { mutableStateOf(LocalAgentPrefs.isDailyFactsReminderEnabled(context)) }
    var autoSaveFacts by remember { mutableStateOf(com.fersaiyan.cyanbridge.localagent.userfacts.ChatMemoryPrefs.isAutoSaveDailyFactsEnabled(context)) }
    var extractFacts by remember { mutableStateOf(com.fersaiyan.cyanbridge.localagent.userfacts.ChatMemoryPrefs.isExtractUserFactCandidatesEnabled(context)) }
    var maxTokens by remember { mutableIntStateOf(DailyBulletsSettings.getMaxTokensPerBullet(context)) }
    var retentionDays by remember { mutableIntStateOf(MemoryModeManager.getScreenOcrRetentionDays(context)) }
    var prompt by remember { mutableStateOf(DailyBulletsSettings.getBulletPrompt(context)) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.compose_plugin_settings_title, stringResource(R.string.compose_plugin_name_autodiary))) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
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
                checked = enabled,
                onCheckedChange = onEnabledChanged,
            )
            NativePluginShortcutPreference(
                pluginId = com.fersaiyan.cyanbridge.shared.plugins.NativePluginIds.AUTO_DIARY,
                pluginTitle = stringResource(R.string.compose_plugin_name_autodiary),
            )
            Text(stringResource(R.string.compose_screen_capture), style = MaterialTheme.typography.titleMedium)
            Text(
                when {
                    taskerObserverAvailable -> "Tasker and AutoInput are installed. The imported profile captures every 10 minutes by default. Edit the Time profile in Tasker to change the schedule."
                    enabled -> "AutoDiary is enabled, but Tasker or AutoInput is not detected. Install/import the Tasker profile before expecting screen captures."
                    else -> "Tasker + AutoInput are required for screen observation. CyanBridge Accessibility and installed-app visibility are not required."
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (taskerObserverAvailable) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
            )
            Text(
                "Excluded apps are configured in Tasker with the global variable %CB_AutoDiaryExcluded. " +
                    "Use package names separated by commas, spaces, semicolons or new lines. Excluded screen text is not sent to CyanBridge.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = onOpenTasker, modifier = Modifier.fillMaxWidth()) {
                Text("Open Tasker schedule / exclusions")
            }
            OutlinedButton(onClick = onOpenCaptures, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.compose_view_screen_captures))
            }
            NumberSetting(
                label = "Screen OCR retention (days)",
                value = retentionDays,
                range = 1..365,
                onValueChanged = { days ->
                    retentionDays = days
                    MemoryModeManager.setScreenOcrRetentionDays(context, days)
                    coroutineScope.launch { MemoryVaultService.enforceScreenOcrRetention(context) }
                },
            )
            Text(stringResource(R.string.compose_daily_processing), style = MaterialTheme.typography.titleMedium)
            SwitchSetting(stringResource(R.string.compose_daily_facts_reminder), reminder) {
                reminder = it
                LocalAgentPrefs.setDailyFactsReminderEnabled(context, it)
                DailyFactsReminderScheduler.scheduleIfEnabled(context, it)
            }
            SwitchSetting(stringResource(R.string.compose_auto_save_daily_facts), autoSaveFacts) {
                autoSaveFacts = it
                com.fersaiyan.cyanbridge.localagent.userfacts.ChatMemoryPrefs.setAutoSaveDailyFactsEnabled(context, it)
            }
            SwitchSetting(stringResource(R.string.compose_extract_user_fact_candidates), extractFacts) {
                extractFacts = it
                com.fersaiyan.cyanbridge.localagent.userfacts.ChatMemoryPrefs.setExtractUserFactCandidatesEnabled(context, it)
            }
            Text(
                stringResource(R.string.compose_nightly_enrichment_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = onOpenSummary, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.compose_open_daily_summary))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onOpenDailyFactsDraft, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.compose_edit_daily_facts))
                }
                OutlinedButton(onClick = onOpenConfirmedDailyFacts, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.compose_view_confirmed_facts))
                }
            }
            Text(stringResource(R.string.compose_bulletization), style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = prompt,
                onValueChange = {
                    prompt = it
                    DailyBulletsSettings.setCustomBulletPrompt(context, it)
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.compose_bullet_prompt)) },
                minLines = 3,
                maxLines = 7,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = {
                    DailyBulletsSettings.restoreDefaultBulletPrompt(context)
                    prompt = DailyBulletsSettings.DEFAULT_BULLET_PROMPT
                }) { Text(stringResource(R.string.compose_restore_default_prompt)) }
                NumberSetting(
                    label = stringResource(R.string.compose_max_tokens_per_bullet),
                    value = maxTokens,
                    range = 0..100_000,
                    onValueChanged = {
                        maxTokens = it
                        DailyBulletsSettings.setMaxTokensPerBullet(context, it)
                    },
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
