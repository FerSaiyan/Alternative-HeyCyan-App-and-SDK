package com.fersaiyan.cyanbridge.plugins.localagent

import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.provider.Settings
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.fersaiyan.cyanbridge.R
import com.fersaiyan.cyanbridge.agent.LocalAgentPrefs as AutomationPrefs
import com.fersaiyan.cyanbridge.bridge.notifications.NotificationForwarderService
import com.fersaiyan.cyanbridge.localagent.LocalAgentController
import com.fersaiyan.cyanbridge.localagent.LocalAgentIntents
import com.fersaiyan.cyanbridge.agent.LocalAgentPrefs as RuntimePrefs
import com.fersaiyan.cyanbridge.localagent.LocalAgentShizukuFallback
import com.fersaiyan.cyanbridge.localagent.LocalAgentTaskHistory
import com.fersaiyan.cyanbridge.localagent.memory.LocalAgentMemoryStore
import com.fersaiyan.cyanbridge.localmodels.session.LocalChatSessionManager
import com.fersaiyan.cyanbridge.plugins.autodiary.AutoDiarySettingsActivity
import com.fersaiyan.cyanbridge.shared.plugins.NativePluginIds
import com.fersaiyan.cyanbridge.shared.settings.AgentProviderType
import com.fersaiyan.cyanbridge.ui.NativePluginShortcutPreference
import com.fersaiyan.cyanbridge.ui.hasAccessibilityServicePermission
import com.fersaiyan.cyanbridge.ui.localagent.PendingActionsActivity
import com.fersaiyan.cyanbridge.ui.setThemedComposeScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LocalAgentSettingsActivity : AppCompatActivity(), LocalAgentSettingsActions {

    private var uiState by mutableStateOf(LocalAgentSettingsUiState())

    internal val state: LocalAgentSettingsUiState get() = uiState
    private var receiverRegistered = false

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) {
            if (intent?.action != LocalAgentIntents.ACTION_STATUS_CHANGED) return
            intent.getStringExtra(LocalAgentIntents.EXTRA_STATUS)?.let {
                RuntimePrefs.setStatus(this@LocalAgentSettingsActivity, it)
            }
            intent.getStringExtra(LocalAgentIntents.EXTRA_LAST_ERROR)?.let {
                RuntimePrefs.setLastError(this@LocalAgentSettingsActivity, it)
            }
            refreshUi()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        refreshUi()
        setThemedComposeScreen { LocalAgentSettingsScreen(state = uiState, actions = this) }
    }

    override fun onStart() {
        super.onStart()
        if (!receiverRegistered) {
            LocalBroadcastManager.getInstance(this).registerReceiver(
                statusReceiver,
                IntentFilter(LocalAgentIntents.ACTION_STATUS_CHANGED),
            )
            receiverRegistered = true
        }
        LocalAgentController.requestStatus(this)
        refreshUi()
    }

    override fun onResume() {
        super.onResume()
        refreshUi()
    }

    override fun onStop() {
        if (receiverRegistered) {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(statusReceiver)
            receiverRegistered = false
        }
        super.onStop()
    }

    private fun refreshUi() {
        LocalAgentPlugin.syncNativePluginState(this)
        uiState = LocalAgentSettingsUiState(
            enabled = LocalAgentPlugin.isEnabled(this),
            providerType = AutomationPrefs.getProviderType(this),
            accessibilityEnabled = hasAccessibilityServicePermission(this),
            maxSteps = AutomationPrefs.getMaxSteps(this),
            requireActionConfirmation = RuntimePrefs.isRequireActionConfirmationEnabled(this),
            autoExecuteLowRisk = RuntimePrefs.isAutoExecuteLowRiskEnabled(this),
            notificationListenerEnabled = NotificationForwarderService.isListenerBound(this),
            whatsappNotificationReadAloud = RuntimePrefs.isWhatsAppNotificationReadAloudEnabled(this),
            screenshotStatus = RuntimePrefs.getScreenshotStatus(this),
            shizukuFallbackEnabled = RuntimePrefs.isShizukuFallbackEnabled(this),
            shizukuAvailability = LocalAgentShizukuFallback.availability().statusText,
            shizukuStatus = RuntimePrefs.getShizukuStatus(this),
            status = RuntimePrefs.getStatus(this),
            lastError = RuntimePrefs.getLastError(this),
        )
    }

    override fun onBack() = finish()

    override fun onEnabledChange(enabled: Boolean) {
        LocalAgentPlugin.setEnabled(this, enabled)
        refreshUi()
    }

    private fun setPlanningProvider(type: AgentProviderType) {
        LocalAgentPlugin.setPlanningProvider(this, type)
        if (type != AgentProviderType.LOCAL_AGENT) {
            lifecycleScope.launch(Dispatchers.IO) {
                runCatching { LocalChatSessionManager.unload() }
            }
        }
        refreshUi()
    }

    override fun onOpenSettings() {
        startActivity(Intent(this, com.fersaiyan.cyanbridge.ui.SettingsActivity::class.java))
    }

    override fun onMaxStepsChange(maxSteps: Int) {
        AutomationPrefs.setMaxSteps(this, maxSteps)
        refreshUi()
    }

    override fun onRequireActionConfirmationChange(enabled: Boolean) {
        // Keep the old automation preference aligned with the runtime policy.
        AutomationPrefs.setRequireConfirmationEnabled(this, enabled)
        RuntimePrefs.setRequireActionConfirmationEnabled(this, enabled)
        refreshUi()
    }

    override fun onAutoExecuteLowRiskChange(enabled: Boolean) {
        RuntimePrefs.setAutoExecuteLowRiskEnabled(this, enabled)
        refreshUi()
    }

    override fun onOpenPendingActions() {
        startActivity(Intent(this, PendingActionsActivity::class.java))
    }

    override fun onShizukuFallbackChange(enabled: Boolean) {
        RuntimePrefs.setShizukuFallbackEnabled(this, enabled)
        if (enabled) {
            RuntimePrefs.setShizukuStatus(this, LocalAgentShizukuFallback.availability().statusText)
        } else {
            LocalAgentShizukuFallback.disconnect(this)
            RuntimePrefs.setShizukuStatus(this, getString(R.string.compose_disabled))
        }
        refreshUi()
    }

    override fun onRequestShizukuPermission() {
        Toast.makeText(
            this,
            LocalAgentShizukuFallback.requestPermission(this),
            Toast.LENGTH_SHORT,
        ).show()
        refreshUi()
    }

    override fun onWhatsAppReadAloudChange(enabled: Boolean) {
        RuntimePrefs.setWhatsAppNotificationReadAloudEnabled(this, enabled)
        refreshUi()
    }

    override fun onAgentStatus() {
        Toast.makeText(this, R.string.compose_local_agent_no_goal, Toast.LENGTH_SHORT).show()
        LocalAgentController.requestStatus(this)
    }

    override fun onAgentStop() {
        runAgentCommand(getString(R.string.compose_local_agent_stopping)) {
            LocalAgentController.stop(this)
        }
    }

    override fun onAgentDemo() {
        Toast.makeText(
            this,
            R.string.compose_demo_message,
            Toast.LENGTH_LONG,
        ).show()
        runAgentCommand(getString(R.string.compose_local_agent_starting)) {
            LocalAgentController.demo(this)
        }
    }

    override fun onOpenAutoDiarySettings() {
        startActivity(Intent(this, AutoDiarySettingsActivity::class.java))
    }

    override fun onEditAgentPersonality() {
        LocalAgentMemoryStore.ensureSeedFiles(this)
        editMemoryFile(
            title = getString(R.string.compose_agent_personality),
            file = LocalAgentMemoryStore.agentPersonaFile(this),
        )
    }

    override fun onEditUserFacts() {
        LocalAgentMemoryStore.ensureSeedFiles(this)
        editMemoryFile(
            title = getString(R.string.compose_user_facts),
            file = LocalAgentMemoryStore.userFactsFile(this),
        )
    }

    override fun onOpenNotificationListenerSettings() {
        runCatching { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
            .onFailure {
                Toast.makeText(this, R.string.compose_local_agent_unable_open_notifications, Toast.LENGTH_SHORT).show()
            }
    }

    private fun runAgentCommand(
        optimisticStatus: String,
        command: () -> LocalAgentController.CommandResult,
    ) {
        val result = command()
        if (result.ok) {
            RuntimePrefs.setStatus(this, optimisticStatus)
            RuntimePrefs.clearLastError(this)
        } else {
            RuntimePrefs.setStatus(this, getString(R.string.compose_local_agent_error))
            RuntimePrefs.setLastError(this, result.error ?: result.userMessage)
        }
        Toast.makeText(this, result.userMessage, Toast.LENGTH_SHORT).show()
        LocalAgentController.requestStatus(this)
        refreshUi()
    }

    override fun onOpenAccessibilitySettings() {
        AlertDialog.Builder(this)
            .setTitle(R.string.onboarding_accessibility_disclosure_title)
            .setMessage(R.string.onboarding_accessibility_disclosure_body)
            .setNegativeButton(R.string.compose_not_now, null)
            .setPositiveButton(R.string.compose_continue) { _, _ ->
                    runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                    .onFailure {
                        Toast.makeText(this, R.string.compose_local_agent_unable_open_accessibility, Toast.LENGTH_SHORT).show()
                    }
            }
            .show()
    }

    private fun editMemoryFile(title: String, file: java.io.File) {
        LocalAgentMemoryStore.ensureSeedFiles(this)
        val input = EditText(this).apply {
            setText(LocalAgentMemoryStore.readText(file))
            setSelection(text?.length ?: 0)
            minLines = 8
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(input)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_save) { _, _ ->
                LocalAgentMemoryStore.writeText(file, input.text?.toString().orEmpty())
                Toast.makeText(this, getString(R.string.compose_local_agent_saved_suffix, title), Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    override fun onViewInjectedContext() {
        val debug = RuntimePrefs.getLastContextInjectionDebug(this)
        val atMs = RuntimePrefs.getLastContextInjectionAtMs(this)
        val message = if (debug.isBlank()) {
            getString(R.string.compose_local_agent_no_context)
        } else {
            getString(
                R.string.compose_local_agent_last_injected,
                SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(atMs)),
            ) + "\n\n$debug"
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.compose_local_agent_context_debug)
            .setMessage(message)
            .setPositiveButton(R.string.compose_close, null)
            .show()
    }

    override fun onShowTaskHistory() {
        val entries = LocalAgentTaskHistory.recent(this, limit = 8)
        val message = if (entries.isEmpty()) {
            getString(R.string.compose_local_agent_no_history)
        } else {
            entries.joinToString("\n\n") { entry ->
                val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
                    .format(Date(entry.createdAtMs))
                buildString {
                    appendLine(timestamp)
                    appendLine("${entry.status}, ${entry.stepCount} step(s)")
                    if (entry.usedSavedSkill) appendLine(getString(R.string.compose_local_agent_used_path))
                    append(entry.goal)
                }
            }
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.compose_local_agent_activity)
            .setMessage(message)
            .setNegativeButton(R.string.compose_local_agent_clear_history) { _, _ ->
                LocalAgentTaskHistory.clear(this)
                Toast.makeText(this, R.string.compose_local_agent_history_cleared, Toast.LENGTH_SHORT).show()
            }
            .setPositiveButton(R.string.compose_close, null)
            .show()
    }
}

data class LocalAgentSettingsUiState(
    val enabled: Boolean = false,
    val providerType: AgentProviderType = AgentProviderType.TASKER,
    val accessibilityEnabled: Boolean = false,
    val maxSteps: Int = 8,
    val requireActionConfirmation: Boolean = true,
    val autoExecuteLowRisk: Boolean = true,
    val notificationListenerEnabled: Boolean = false,
    val whatsappNotificationReadAloud: Boolean = false,
    val screenshotStatus: String = "Text-only planning",
    val shizukuFallbackEnabled: Boolean = false,
    val shizukuAvailability: String = "Shizuku is unavailable",
    val shizukuStatus: String = "Disabled",
    val status: String = "Unknown",
    val lastError: String = "",
)

/** Every effect the Local Agent settings screen can trigger; implemented by [LocalAgentSettingsActivity]. */
interface LocalAgentSettingsActions {
    fun onBack()
    fun onEnabledChange(enabled: Boolean)
    fun onOpenSettings()
    fun onOpenAccessibilitySettings()
    fun onMaxStepsChange(maxSteps: Int)
    fun onRequireActionConfirmationChange(enabled: Boolean)
    fun onAutoExecuteLowRiskChange(enabled: Boolean)
    fun onOpenPendingActions()
    fun onShowTaskHistory()
    fun onShizukuFallbackChange(enabled: Boolean)
    fun onRequestShizukuPermission()
    fun onOpenNotificationListenerSettings()
    fun onWhatsAppReadAloudChange(enabled: Boolean)
    fun onAgentStatus()
    fun onAgentStop()
    fun onAgentDemo()
    fun onOpenAutoDiarySettings()
    fun onEditAgentPersonality()
    fun onEditUserFacts()
    fun onViewInjectedContext()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalAgentSettingsScreen(
    state: LocalAgentSettingsUiState,
    actions: LocalAgentSettingsActions,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.compose_local_agent_settings_title)) },
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
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.compose_local_agent_intro),
                style = MaterialTheme.typography.bodyMedium,
            )
            SwitchSetting(
                label = stringResource(R.string.compose_local_agent_enable),
                checked = state.enabled,
                onCheckedChange = actions::onEnabledChange,
            )
            NativePluginShortcutPreference(
                pluginId = NativePluginIds.LOCAL_AGENT,
                pluginTitle = stringResource(R.string.compose_local_agent_title),
            )

            SectionTitle(stringResource(R.string.compose_local_agent_planning))
            Text(
                stringResource(R.string.compose_local_agent_provider_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = actions::onOpenSettings,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.compose_open_settings))
            }

            SectionTitle(stringResource(R.string.compose_local_agent_screenshot_planning))
            Text(
                stringResource(R.string.compose_local_agent_screenshot_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.compose_local_agent_screenshot_status, state.screenshotStatus),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SectionTitle(stringResource(R.string.compose_local_agent_runtime_safety))
            StatusCard(state)
            Text(
                stringResource(R.string.compose_local_agent_runtime_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SettingAction(
                label = stringResource(R.string.compose_local_agent_accessibility_service),
                detail = stringResource(
                    if (state.accessibilityEnabled) R.string.compose_enabled else R.string.compose_disabled,
                ),
                actionLabel = stringResource(R.string.compose_open_settings_lower),
                onClick = actions::onOpenAccessibilitySettings,
            )
            NumberSetting(
                label = stringResource(R.string.compose_max_steps_per_request),
                value = state.maxSteps,
                range = 1..200,
                onValueChanged = actions::onMaxStepsChange,
            )
            SwitchSetting(
                label = stringResource(R.string.compose_require_approval),
                checked = state.requireActionConfirmation,
                onCheckedChange = actions::onRequireActionConfirmationChange,
            )
            SwitchSetting(
                label = stringResource(R.string.compose_auto_execute_low_risk),
                checked = state.autoExecuteLowRisk,
                enabled = state.requireActionConfirmation,
                onCheckedChange = actions::onAutoExecuteLowRiskChange,
            )
            OutlinedButton(
                onClick = actions::onOpenPendingActions,
                enabled = state.requireActionConfirmation,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.compose_view_pending_actions))
            }
            Text(
                stringResource(R.string.compose_local_agent_reuse_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SettingAction(
                label = stringResource(R.string.compose_recent_automation_activity),
                detail = stringResource(R.string.compose_local_metadata_only),
                actionLabel = stringResource(R.string.compose_view),
                onClick = actions::onShowTaskHistory,
            )

            SectionTitle(stringResource(R.string.compose_local_agent_shizuku))
            Text(
                stringResource(R.string.compose_local_agent_shizuku_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SwitchSetting(
                label = stringResource(R.string.compose_use_shizuku),
                checked = state.shizukuFallbackEnabled,
                onCheckedChange = actions::onShizukuFallbackChange,
            )
            SettingAction(
                label = stringResource(R.string.compose_shizuku_access),
                detail = state.shizukuAvailability,
                actionLabel = stringResource(R.string.compose_grant_access),
                onClick = actions::onRequestShizukuPermission,
            )
            Text(
                stringResource(R.string.compose_shizuku_status, state.shizukuStatus),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SectionTitle(stringResource(R.string.compose_local_agent_notification))
            Text(
                stringResource(R.string.compose_local_agent_notification_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SettingAction(
                label = stringResource(R.string.compose_notification_access),
                detail = stringResource(
                    if (state.notificationListenerEnabled) R.string.compose_enabled else R.string.compose_disabled,
                ),
                actionLabel = stringResource(R.string.compose_open_settings_lower),
                onClick = actions::onOpenNotificationListenerSettings,
            )
            SwitchSetting(
                label = stringResource(R.string.compose_read_whatsapp_aloud),
                checked = state.whatsappNotificationReadAloud,
                enabled = state.notificationListenerEnabled,
                onCheckedChange = actions::onWhatsAppReadAloudChange,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = actions::onAgentStatus,
                    enabled = state.enabled,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.compose_action_status))
                }
                OutlinedButton(
                    onClick = actions::onAgentStop,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.compose_action_stop))
                }
                OutlinedButton(
                    onClick = actions::onAgentDemo,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.compose_action_demo))
                }
            }

            SectionTitle(stringResource(R.string.compose_local_agent_shared_memory))
            Text(
                stringResource(R.string.compose_local_agent_shared_memory_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = actions::onOpenAutoDiarySettings,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.compose_open_autodiary_settings))
            }

            SectionTitle(stringResource(R.string.compose_local_agent_personalization))
            OutlinedButton(
                onClick = actions::onEditAgentPersonality,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.compose_edit_agent_personality))
            }
            OutlinedButton(
                onClick = actions::onEditUserFacts,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.compose_edit_user_facts))
            }
            OutlinedButton(
                onClick = actions::onViewInjectedContext,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.compose_view_injected_context))
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium)
}

@Composable
private fun StatusCard(state: LocalAgentSettingsUiState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(stringResource(R.string.compose_status_value, state.status), style = MaterialTheme.typography.bodyMedium)
            if (state.lastError.isNotBlank() && state.lastError != "(none)") {
                Text(
                    stringResource(R.string.compose_last_error_value, state.lastError),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun SettingAction(
    label: String,
    detail: String,
    actionLabel: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        OutlinedButton(onClick = onClick) { Text(actionLabel) }
    }
}

@Composable
private fun SwitchSetting(
    label: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
        )
    }
}

@Composable
private fun NumberSetting(
    label: String,
    value: Int,
    range: IntRange,
    onValueChanged: (Int) -> Unit,
) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    val parsed = text.toIntOrNull()
    val valid = parsed != null && parsed in range
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
        if (text.isNotBlank() && !valid) {
            Text(
                stringResource(R.string.compose_range_error, range.first, range.last),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}
