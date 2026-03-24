package com.fersaiyan.cyanbridge.ui.settings

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fersaiyan.cyanbridge.agent.AgentProviderType
import com.fersaiyan.cyanbridge.ai.router.AiProviderType
import com.fersaiyan.cyanbridge.ai.router.CliRelayBackend
import com.fersaiyan.cyanbridge.localagent.accessibility.LocalAgentAccessibilityService
import com.fersaiyan.cyanbridge.localagent.daily.DailyFactsReminderScheduler
import com.fersaiyan.cyanbridge.localagent.memory.LocalAgentMemoryStore
import com.fersaiyan.cyanbridge.memoryvault.MemoryModeManager
import com.fersaiyan.cyanbridge.memoryvault.MemoryPrivacyMode
import com.fersaiyan.cyanbridge.memoryvault.MemorySourceType
import com.fersaiyan.cyanbridge.memoryvault.MemoryVaultBootstrap
import com.fersaiyan.cyanbridge.memoryvault.VaultLockStateManager
import com.fersaiyan.cyanbridge.media.autocapture.AutoAudioCaptureService
import com.fersaiyan.cyanbridge.ui.localagent.AppBlacklistActivity
import com.fersaiyan.cyanbridge.ui.localagent.DailyFactsActivity
import com.fersaiyan.cyanbridge.ui.localagent.DailySummaryActivity
import com.fersaiyan.cyanbridge.ui.localagent.ScreenCapturesActivity
import com.fersaiyan.cyanbridge.ui.navigation.Routes
import com.fersaiyan.cyanbridge.ui.theme.COLOR_PRESETS
import com.fersaiyan.cyanbridge.localagent.LocalAgentPrefs as AgentRuntimePrefs
import com.hjq.permissions.XXPermissions
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigate: (String) -> Unit,
    viewModel: SettingsViewModel = viewModel(
        factory = SettingsViewModel.Factory(LocalContext.current),
    ),
) {
    val state by viewModel.uiState.collectAsState()
    val scrollState = rememberScrollState()
    val context = LocalContext.current

    DisposableEffect(Unit) {
        viewModel.refreshAccessibilityStatus()
        viewModel.refreshVaultState()
        viewModel.refreshAutoAudioDebug()
        onDispose {}
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Settings",
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp),
        ) {
            ProSubscriptionSection(
                context = context,
                isSubscribed = state.isProSubscribed,
                plan = state.proPlan,
                expiresAt = state.proExpiresAt,
                onConfigure = { isSubscribed ->
                    if (isSubscribed) {
                        onNavigate(Routes.PRO_SETTINGS)
                    } else {
                        onNavigate(Routes.PRO)
                    }
                },
            )

            Spacer(modifier = Modifier.height(16.dp))

            ThemeSection(
                isDarkTheme = state.isDarkTheme,
                onToggle = { viewModel.setDarkTheme(it) },
                accentColorIndex = state.accentColorIndex,
                onSelectPreset = { viewModel.setAccentColorIndex(it) },
            )

            Spacer(modifier = Modifier.height(16.dp))

            AiProviderSection(
                providerType = state.providerType,
                aiProvider = state.aiProvider,
                relayBaseUrl = state.relayBaseUrl,
                relayBackend = state.relayBackend,
                onProviderTypeChange = { viewModel.setProviderType(it) },
                onRelayUrlChange = { viewModel.setRelayBaseUrl(it) },
                onRelayBackendChange = { viewModel.setRelayBackend(it) },
                onNavigate = onNavigate,
            )

            Spacer(modifier = Modifier.height(16.dp))

            LocalAgentSection(
                context = context,
                accessibilityEnabled = state.accessibilityEnabled,
                autoCaptureEnabled = state.autoCaptureEnabled,
                captureIntervalMin = state.captureIntervalMin,
                dailyFactsReminderEnabled = state.dailyFactsReminderEnabled,
                autoSaveDailyFactsEnabled = state.autoSaveDailyFactsEnabled,
                extractUserFactCandidatesEnabled = state.extractUserFactCandidatesEnabled,
                lastContextInjectionDebug = state.lastContextInjectionDebug,
                onRefreshAccessibility = { viewModel.refreshAccessibilityStatus() },
                onAutoCaptureChange = { viewModel.setAutoCaptureEnabled(it) },
                onCaptureIntervalChange = { viewModel.setCaptureInterval(it) },
                onDailyFactsReminderChange = { viewModel.setDailyFactsReminderEnabled(it) },
                onAutoSaveDailyFactsChange = { viewModel.setAutoSaveDailyFacts(it) },
                onExtractUserFactCandidatesChange = { viewModel.setExtractUserFactCandidates(it) },
                onSaveAgentPersona = { viewModel.saveAgentPersona(it) },
                onSaveUserFacts = { viewModel.saveUserFacts(it) },
                onReadAgentPersona = { viewModel.readAgentPersona() },
                onReadUserFacts = { viewModel.readUserFacts() },
            )

            Spacer(modifier = Modifier.height(16.dp))

            MemoryPrivacySection(
                context = context,
                memoryMode = state.memoryMode,
                syncExplicit = state.syncExplicit,
                syncDaily = state.syncDaily,
                syncOcr = state.syncOcr,
                syncDerived = state.syncDerived,
                ocrRetentionDays = state.ocrRetentionDays,
                vaultLocked = state.vaultLocked,
                vaultRequiresPassphrase = state.vaultRequiresPassphrase,
                onMemoryModeChange = { viewModel.setMemoryMode(it) },
                onSyncExplicitChange = { viewModel.setSyncExplicit(it) },
                onSyncDailyChange = { viewModel.setSyncDaily(it) },
                onSyncOcrChange = { viewModel.setSyncOcr(it) },
                onSyncDerivedChange = { viewModel.setSyncDerived(it) },
                onOcrRetentionChange = { viewModel.setOcrRetentionDays(it) },
                onDeletePassiveCapture = { viewModel.deletePassiveCapture { } },
                onLockVault = { viewModel.lockVault() },
                onUnlockVault = { viewModel.unlockVault(it) },
                onSetPassphrase = { viewModel.setVaultPassphrase(it) },
                onClearPassphrase = { viewModel.clearVaultPassphrase() },
                onResetVault = { viewModel.resetVault { viewModel.refreshVaultState() } },
            )

            Spacer(modifier = Modifier.height(16.dp))

            TranscriptsSection(
                transcriptStorageEnabled = state.transcriptStorageEnabled,
                includeFullTranscriptionInExports = state.includeFullTranscriptionInExports,
                autoAudioCaptureEnabled = state.autoAudioCaptureEnabled,
                autoAudioDebugText = state.autoAudioDebugText,
                onTranscriptStorageChange = { viewModel.setTranscriptStorage(it) },
                onIncludeFullTranscriptionChange = { viewModel.setIncludeFullTranscriptionInExports(it) },
                onAutoAudioCaptureChange = { enabled ->
                    if (enabled) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                            !XXPermissions.isGranted(context, Manifest.permission.POST_NOTIFICATIONS)
                        ) {
                            XXPermissions.with(context)
                                .permission(Manifest.permission.POST_NOTIFICATIONS)
                                .request { _, allGranted ->
                                    if (allGranted) {
                                        viewModel.setAutoAudioCapture(true)
                                    }
                                }
                        } else {
                            viewModel.setAutoAudioCapture(true)
                        }
                    } else {
                        viewModel.setAutoAudioCapture(false)
                    }
                },
            )

            Spacer(modifier = Modifier.height(16.dp))

            RedactionSection(
                redactNamesEnabled = state.redactNamesEnabled,
                onRedactNamesChange = { viewModel.setRedactNames(it) },
            )

            Spacer(modifier = Modifier.height(16.dp))

            DataSection(
                context = context,
                viewModel = viewModel,
            )

            Spacer(modifier = Modifier.height(16.dp))

            AboutSection(
                onNavigate = onNavigate,
            )

            Spacer(modifier = Modifier.height(16.dp))

            FaqSection()

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun ProSubscriptionSection(
    context: Context,
    isSubscribed: Boolean,
    plan: String,
    expiresAt: Long,
    onConfigure: (Boolean) -> Unit,
) {
    SettingsCard(title = "Pro Subscription") {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onConfigure(isSubscribed) }
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.Star,
                    contentDescription = null,
                    tint = if (isSubscribed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (isSubscribed) "Pro Subscription Active" else "Pro Subscription",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (isSubscribed) {
                        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
                        val expiryText = if (expiresAt > 0L) "Expires: ${dateFormat.format(Date(expiresAt))}" else ""
                        Text(
                            text = "Plan: ${plan.replaceFirstChar { it.uppercase() }} $expiryText",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    } else {
                        Text(
                            text = "Unlock premium features",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = "Configure",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ThemeSection(
    isDarkTheme: Boolean,
    onToggle: (Boolean) -> Unit,
    accentColorIndex: Int,
    onSelectPreset: (Int) -> Unit,
) {
    SettingsCard(title = "Appearance") {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.Star,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = if (isDarkTheme) "Dark Mode (On)" else "Dark Mode (Off)",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = isDarkTheme,
                    onCheckedChange = onToggle,
                )
            }

            HorizontalDivider()

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Accent Color",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                COLOR_PRESETS.forEachIndexed { index, preset ->
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(preset.accent)
                            .border(
                                width = if (index == accentColorIndex) 3.dp else 0.dp,
                                color = Color.White,
                                shape = CircleShape,
                            )
                            .clickable { onSelectPreset(index) },
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = COLOR_PRESETS.getOrElse(accentColorIndex) { COLOR_PRESETS[0] }.name,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )

            Spacer(modifier = Modifier.height(4.dp))
        }
    }
}

@Composable
private fun AiProviderSection(
    providerType: AgentProviderType,
    aiProvider: AiProviderType,
    relayBaseUrl: String,
    relayBackend: CliRelayBackend,
    onProviderTypeChange: (AgentProviderType) -> Unit,
    onRelayUrlChange: (String) -> Unit,
    onRelayBackendChange: (CliRelayBackend) -> Unit,
    onNavigate: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    SettingsCard(
        title = "AI / Automation",
        onExpandToggle = { expanded = !expanded },
        expanded = expanded,
    ) {
        Column {
            AgentProviderType.entries.forEach { type ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onProviderTypeChange(type) }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = providerType == type,
                        onClick = { onProviderTypeChange(type) },
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = when (type) {
                                AgentProviderType.PRO_SUBSCRIPTION -> "Pro Subscription (Relay)"
                                AgentProviderType.LOCAL_AGENT -> "Local Agent (On-Device)"
                                AgentProviderType.TASKER -> "Tasker (Automation)"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = when (type) {
                                AgentProviderType.PRO_SUBSCRIPTION -> "Uses: CLI Relay → ${aiProvider.label}"
                                AgentProviderType.LOCAL_AGENT -> "Uses: Local Models"
                                AgentProviderType.TASKER -> "Uses: Tasker integration"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            TextButton(onClick = { onNavigate(Routes.LOCAL_MODELS) }) {
                Text("Configure Local Models", color = MaterialTheme.colorScheme.primary)
            }

            if (providerType == AgentProviderType.PRO_SUBSCRIPTION) {
                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Relay Backend",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CliRelayBackend.entries.forEach { backend ->
                        SelectableChip(
                            label = backend.label,
                            selected = relayBackend == backend,
                            onClick = { onRelayBackendChange(backend) },
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Relay Server URL",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(4.dp))
                OutlinedTextField(
                    value = relayBaseUrl,
                    onValueChange = onRelayUrlChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("http://177.95.92.150:48787") },
                    shape = RoundedCornerShape(8.dp),
                    textStyle = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun LocalAgentSection(
    context: Context,
    accessibilityEnabled: Boolean,
    autoCaptureEnabled: Boolean,
    captureIntervalMin: Int,
    dailyFactsReminderEnabled: Boolean,
    autoSaveDailyFactsEnabled: Boolean,
    extractUserFactCandidatesEnabled: Boolean,
    lastContextInjectionDebug: String,
    onRefreshAccessibility: () -> Unit,
    onAutoCaptureChange: (Boolean) -> Unit,
    onCaptureIntervalChange: (Int) -> Unit,
    onDailyFactsReminderChange: (Boolean) -> Unit,
    onAutoSaveDailyFactsChange: (Boolean) -> Unit,
    onExtractUserFactCandidatesChange: (Boolean) -> Unit,
    onSaveAgentPersona: (String) -> Unit,
    onSaveUserFacts: (String) -> Unit,
    onReadAgentPersona: () -> String,
    onReadUserFacts: () -> String,
) {
    var expanded by remember { mutableStateOf(false) }
    var showPersonaDialog by remember { mutableStateOf(false) }
    var showFactsDialog by remember { mutableStateOf(false) }
    var showContextDebugDialog by remember { mutableStateOf(false) }

    SettingsCard(
        title = "Local Agent",
        onExpandToggle = { expanded = !expanded },
        expanded = expanded,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SettingsRow(label = "Accessibility Status", subtitle = if (accessibilityEnabled) "Enabled" else "Disabled") {
                TextButton(onClick = {
                    onRefreshAccessibility()
                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }) {
                    Text("Open Settings", color = MaterialTheme.colorScheme.primary)
                }
            }

            HorizontalDivider()

            SettingsToggleRow(
                label = "Auto capture toggle",
                checked = autoCaptureEnabled,
                onCheckedChange = onAutoCaptureChange,
            )

            CaptureIntervalRow(
                value = captureIntervalMin,
                onValueChange = onCaptureIntervalChange,
            )

            SettingsRow(label = "Blacklist apps") {
                TextButton(onClick = { context.startActivity(Intent(context, AppBlacklistActivity::class.java)) }) {
                    Text("Open", color = MaterialTheme.colorScheme.primary)
                }
            }

            SettingsRow(label = "View screen captures") {
                TextButton(onClick = { context.startActivity(Intent(context, ScreenCapturesActivity::class.java)) }) {
                    Text("Open", color = MaterialTheme.colorScheme.primary)
                }
            }

            HorizontalDivider()

            SettingsToggleRow(
                label = "Daily facts reminder",
                checked = dailyFactsReminderEnabled,
                onCheckedChange = onDailyFactsReminderChange,
            )

            SettingsRow(label = "Edit daily facts") {
                TextButton(onClick = {
                    context.startActivity(Intent(context, DailyFactsActivity::class.java).apply {
                        putExtra(DailyFactsActivity.EXTRA_MODE, DailyFactsActivity.MODE_DRAFT)
                    })
                }) {
                    Text("Open", color = MaterialTheme.colorScheme.primary)
                }
            }

            SettingsRow(label = "View confirmed daily facts") {
                TextButton(onClick = {
                    context.startActivity(Intent(context, DailyFactsActivity::class.java).apply {
                        putExtra(DailyFactsActivity.EXTRA_MODE, DailyFactsActivity.MODE_CONFIRMED)
                    })
                }) {
                    Text("Open", color = MaterialTheme.colorScheme.primary)
                }
            }

            SettingsRow(label = "View daily summary") {
                TextButton(onClick = { context.startActivity(Intent(context, DailySummaryActivity::class.java)) }) {
                    Text("Open", color = MaterialTheme.colorScheme.primary)
                }
            }

            HorizontalDivider()

            SettingsToggleRow(
                label = "Auto-save daily facts",
                checked = autoSaveDailyFactsEnabled,
                onCheckedChange = onAutoSaveDailyFactsChange,
            )

            SettingsToggleRow(
                label = "Extract user fact candidates",
                checked = extractUserFactCandidatesEnabled,
                onCheckedChange = onExtractUserFactCandidatesChange,
            )

            HorizontalDivider()

            SettingsRow(label = "Edit agent persona") {
                TextButton(onClick = { showPersonaDialog = true }) {
                    Text("Edit", color = MaterialTheme.colorScheme.primary)
                }
            }

            SettingsRow(label = "Edit user facts") {
                TextButton(onClick = { showFactsDialog = true }) {
                    Text("Edit", color = MaterialTheme.colorScheme.primary)
                }
            }

            SettingsRow(label = "View context debug") {
                TextButton(onClick = { showContextDebugDialog = true }) {
                    Text("View", color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }

    if (showPersonaDialog) {
        TextEditorDialog(
            title = "Agent personality",
            initial = onReadAgentPersona(),
            onDismiss = { showPersonaDialog = false },
            onSave = { text ->
                onSaveAgentPersona(text)
                showPersonaDialog = false
            },
        )
    }

    if (showFactsDialog) {
        TextEditorDialog(
            title = "User facts",
            initial = onReadUserFacts(),
            onDismiss = { showFactsDialog = false },
            onSave = { text ->
                onSaveUserFacts(text)
                showFactsDialog = false
            },
        )
    }

    if (showContextDebugDialog) {
        AlertDialog(
            onDismissRequest = { showContextDebugDialog = false },
            title = { Text("Context Injection Debug") },
            text = {
                Text(
                    if (lastContextInjectionDebug.isBlank()) "No context injection recorded yet."
                    else lastContextInjectionDebug,
                )
            },
            confirmButton = {
                TextButton(onClick = { showContextDebugDialog = false }) {
                    Text("Close")
                }
            },
        )
    }
}

@Composable
private fun MemoryPrivacySection(
    context: Context,
    memoryMode: MemoryPrivacyMode,
    syncExplicit: Boolean,
    syncDaily: Boolean,
    syncOcr: Boolean,
    syncDerived: Boolean,
    ocrRetentionDays: Int,
    vaultLocked: Boolean,
    vaultRequiresPassphrase: Boolean,
    onMemoryModeChange: (MemoryPrivacyMode) -> Unit,
    onSyncExplicitChange: (Boolean) -> Unit,
    onSyncDailyChange: (Boolean) -> Unit,
    onSyncOcrChange: (Boolean) -> Unit,
    onSyncDerivedChange: (Boolean) -> Unit,
    onOcrRetentionChange: (Int) -> Unit,
    onDeletePassiveCapture: () -> Unit,
    onLockVault: () -> Unit,
    onUnlockVault: (String) -> Boolean,
    onSetPassphrase: (String) -> Boolean,
    onClearPassphrase: () -> Unit,
    onResetVault: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showUnlockDialog by remember { mutableStateOf(false) }
    var showPassphraseDialog by remember { mutableStateOf(false) }
    var showResetConfirm by remember { mutableStateOf(false) }
    var passphraseDialogMode by remember { mutableStateOf("set") }

    SettingsCard(
        title = "Memory & Privacy",
        onExpandToggle = { expanded = !expanded },
        expanded = expanded,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "Current mode: ${memoryMode.title}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = MemoryModeManager.modeAvailabilityText(memoryMode),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(4.dp))

            MemoryPrivacyMode.entries.forEach { mode ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onMemoryModeChange(mode) }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = memoryMode == mode,
                        onClick = { onMemoryModeChange(mode) },
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = mode.title,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = mode.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            HorizontalDivider()

            Text(
                text = "Sync Settings",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )

            SettingsToggleRow(
                label = "Explicit facts",
                checked = syncExplicit,
                onCheckedChange = onSyncExplicitChange,
            )
            SettingsToggleRow(
                label = "Daily facts",
                checked = syncDaily,
                onCheckedChange = onSyncDailyChange,
            )
            SettingsToggleRow(
                label = "Screen OCR",
                checked = syncOcr,
                onCheckedChange = onSyncOcrChange,
            )
            SettingsToggleRow(
                label = "Derived summary",
                checked = syncDerived,
                onCheckedChange = onSyncDerivedChange,
            )

            HorizontalDivider()

            CaptureIntervalRow(
                value = ocrRetentionDays,
                onValueChange = onOcrRetentionChange,
                label = "OCR retention (days)",
            )

            SettingsRow(label = "Delete passive capture") {
                TextButton(onClick = { showDeleteConfirm = true }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            }

            HorizontalDivider()

            Text(
                text = buildString {
                    append("Vault is ")
                    append(if (vaultLocked) "LOCKED" else "UNLOCKED")
                    append(".")
                    if (vaultRequiresPassphrase) append(" Passphrase required for unlock.")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(4.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!vaultLocked) {
                    OutlinedButton(onClick = onLockVault) { Text("Lock") }
                } else {
                    OutlinedButton(onClick = { showUnlockDialog = true }) { Text("Unlock") }
                }
                OutlinedButton(onClick = {
                    passphraseDialogMode = "set"
                    showPassphraseDialog = true
                }) { Text("Set Passphrase") }
                OutlinedButton(onClick = onClearPassphrase) { Text("Clear Passphrase") }
            }

            SettingsRow(label = "Reset vault") {
                TextButton(onClick = { showResetConfirm = true }) {
                    Text("Reset", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete passive OCR capture?") },
            text = { Text("This deletes local OCR snapshots and their search index artifacts. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    onDeletePassiveCapture()
                    showDeleteConfirm = false
                }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("Cancel")
                }
            },
        )
    }

    if (showUnlockDialog) {
        PassphraseDialog(
            title = "Unlock vault",
            onDismiss = { showUnlockDialog = false },
            onSubmit = { passphrase ->
                val ok = onUnlockVault(passphrase)
                showUnlockDialog = false
            },
        )
    }

    if (showPassphraseDialog) {
        PassphraseDialog(
            title = if (passphraseDialogMode == "set") "Set vault passphrase" else "Unlock vault",
            onDismiss = { showPassphraseDialog = false },
            onSubmit = { passphrase ->
                val ok = if (passphraseDialogMode == "set") {
                    onSetPassphrase(passphrase)
                } else {
                    onUnlockVault(passphrase)
                }
                showPassphraseDialog = false
            },
        )
    }

    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            title = { Text("Reset memory vault?") },
            text = { Text("This removes encrypted memory payloads, policy metadata, sync queue state, and lock keys. Existing plain files remain. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    onResetVault()
                    showResetConfirm = false
                }) {
                    Text("Reset", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirm = false }) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
private fun TranscriptsSection(
    transcriptStorageEnabled: Boolean,
    includeFullTranscriptionInExports: Boolean,
    autoAudioCaptureEnabled: Boolean,
    autoAudioDebugText: String,
    onTranscriptStorageChange: (Boolean) -> Unit,
    onIncludeFullTranscriptionChange: (Boolean) -> Unit,
    onAutoAudioCaptureChange: (Boolean) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    SettingsCard(
        title = "Transcripts",
        onExpandToggle = { expanded = !expanded },
        expanded = expanded,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SettingsToggleRow(
                label = "Transcript storage",
                checked = transcriptStorageEnabled,
                onCheckedChange = onTranscriptStorageChange,
            )
            SettingsToggleRow(
                label = "Include full transcription in exports",
                checked = includeFullTranscriptionInExports,
                onCheckedChange = onIncludeFullTranscriptionChange,
            )
            HorizontalDivider()
            SettingsToggleRow(
                label = "Auto audio capture",
                checked = autoAudioCaptureEnabled,
                onCheckedChange = onAutoAudioCaptureChange,
            )
            Text(
                text = autoAudioDebugText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RedactionSection(
    redactNamesEnabled: Boolean,
    onRedactNamesChange: (Boolean) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    SettingsCard(
        title = "Redaction",
        onExpandToggle = { expanded = !expanded },
        expanded = expanded,
    ) {
        SettingsToggleRow(
            label = "Redact names",
            checked = redactNamesEnabled,
            onCheckedChange = onRedactNamesChange,
        )
    }
}

@Composable
private fun DataSection(
    context: Context,
    viewModel: SettingsViewModel,
) {
    var expanded by remember { mutableStateOf(false) }
    var showImportConfirm by remember { mutableStateOf(false) }
    var showClearConfirm by remember { mutableStateOf(false) }
    var toastMessage by remember { mutableStateOf("") }
    var showToast by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        uri?.let {
            val fileName = "cyanbridge_backup_${SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())}.zip"
            viewModel.exportData(it) { msg ->
                toastMessage = msg
                showToast = true
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            viewModel.importData(it) { msg ->
                toastMessage = msg
                showToast = true
            }
        }
    }

    SettingsCard(
        title = "Data",
        onExpandToggle = { expanded = !expanded },
        expanded = expanded,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    val fileName = "cyanbridge_backup_${SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())}.zip"
                    exportLauncher.launch(fileName)
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Export")
            }
            OutlinedButton(
                onClick = { showImportConfirm = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Import")
            }
            OutlinedButton(
                onClick = { showClearConfirm = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Clear local data", color = MaterialTheme.colorScheme.error)
            }
        }
    }

    if (showImportConfirm) {
        AlertDialog(
            onDismissRequest = { showImportConfirm = false },
            title = { Text("Import local data?") },
            text = { Text("This will overwrite current local chats, memory files, recordings, and settings from the selected backup ZIP.") },
            confirmButton = {
                TextButton(onClick = {
                    showImportConfirm = false
                    importLauncher.launch(arrayOf("application/zip", "application/octet-stream"))
                }) {
                    Text("Import")
                }
            },
            dismissButton = {
                TextButton(onClick = { showImportConfirm = false }) {
                    Text("Cancel")
                }
            },
        )
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("Clear local data?") },
            text = { Text("This will delete all chats, notes, capture sessions, and audio recordings stored on this device. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clearAllData { msg ->
                        toastMessage = msg
                        showToast = true
                    }
                    showClearConfirm = false
                }) {
                    Text("Clear", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
private fun AboutSection(
    onNavigate: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    SettingsCard(
        title = "About",
        onExpandToggle = { expanded = !expanded },
        expanded = expanded,
    ) {
        QuickLinkItem(
            label = "About",
            subtitle = "App version and credits",
            onClick = { onNavigate("about") },
        )
    }
}

@Composable
private fun FaqSection() {
    var expanded by remember { mutableStateOf(false) }

    SettingsCard(
        title = "FAQ",
        onExpandToggle = { expanded = !expanded },
        expanded = expanded,
    ) {
        val faqItems = listOf(
            "How do I set Local Models?" to "Select Local Models in AI / Automation, then tap Configure Local Models and follow the setup steps on device.",
            "Do I need a subscription to use the app?" to "No. You can use Tasker or Local Models without subscribing. Pro is optional and adds premium managed features.",
            "How is my data handled?" to "By default data is stored locally on your phone. You can export/import your local data and clear it any time from the Data section.",
            "Can I review the source code?" to "Yes. The app is open source and you can manually review the full source code on GitHub.",
        )

        Column(modifier = Modifier.padding(vertical = 8.dp)) {
            faqItems.forEach { (question, answer) ->
                Text(
                    text = question,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = answer,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun SettingsRow(
    label: String,
    subtitle: String? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        trailing?.invoke()
    }
}

@Composable
private fun SettingsToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
    }
}

@Composable
private fun CaptureIntervalRow(
    value: Int,
    onValueChange: (Int) -> Unit,
    label: String = "Capture interval (minutes)",
) {
    var textValue by remember { mutableStateOf(value.toString()) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        OutlinedTextField(
            value = textValue,
            onValueChange = { newVal ->
                textValue = newVal
                newVal.toIntOrNull()?.let { onValueChange(it) }
            },
            modifier = Modifier.width(80.dp),
            singleLine = true,
            shape = RoundedCornerShape(8.dp),
            textStyle = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun TextEditorDialog(
    title: String,
    initial: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp),
                minLines = 8,
                shape = RoundedCornerShape(8.dp),
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(text) }) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun PassphraseDialog(
    title: String,
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit,
) {
    var passphrase by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = passphrase,
                onValueChange = { passphrase = it },
                label = { Text("Passphrase") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onSubmit(passphrase) },
                enabled = passphrase.isNotBlank(),
            ) {
                Text("OK")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun OutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    androidx.compose.material3.OutlinedButton(
        onClick = onClick,
        modifier = modifier,
    ) {
        content()
    }
}

@Composable
private fun SettingsCard(
    title: String,
    expanded: Boolean = true,
    onExpandToggle: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (onExpandToggle != null) Modifier.clickable { onExpandToggle() }
                        else Modifier,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                if (onExpandToggle != null) {
                    Icon(
                        imageVector = if (expanded) Icons.Filled.Check else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = if (expanded) "Collapse" else "Expand",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(),
                exit = shrinkVertically(),
            ) {
                Column {
                    Spacer(modifier = Modifier.height(8.dp))
                    content()
                }
            }
        }
    }
}

@Composable
private fun SelectableChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                else MaterialTheme.colorScheme.surface,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (selected) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(4.dp))
            }
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun QuickLinkItem(
    label: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            imageVector = Icons.Filled.ArrowForward,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
