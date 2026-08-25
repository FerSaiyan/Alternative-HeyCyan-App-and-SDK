package com.fersaiyan.cyanbridge.shared.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import com.fersaiyan.cyanbridge.shared.generated.resources.*
import com.fersaiyan.cyanbridge.shared.navigation.AppDestination
import com.fersaiyan.cyanbridge.shared.navigation.icon
import com.fersaiyan.cyanbridge.shared.icons.imageVector
import com.fersaiyan.cyanbridge.shared.settings.AgentProviderType
import com.fersaiyan.cyanbridge.shared.settings.CaptureSource
import com.fersaiyan.cyanbridge.shared.settings.MemoryPrivacyMode
import com.fersaiyan.cyanbridge.shared.settings.MemorySourceType
import com.fersaiyan.cyanbridge.shared.settings.SettingsSection
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.stringResource
import com.fersaiyan.cyanbridge.shared.ui.localizedDestinationLabel
import com.fersaiyan.cyanbridge.shared.ui.localizedMemoryModeDescription
import com.fersaiyan.cyanbridge.shared.ui.localizedMemoryModeTitle
import com.fersaiyan.cyanbridge.shared.ui.localizedProviderLabel

data class SettingsUiState(
    val isProSubscribed: Boolean = false,
    val proPlan: String = "Pro",
    val appLanguageLabel: String = "System default",
    val providerType: AgentProviderType = AgentProviderType.PRO_SUBSCRIPTION,
    val taskerIntegrationsAvailable: Boolean = false,
    val defaultImageQuestion: String = "Give me a concise description of the image",
    val memoryMode: MemoryPrivacyMode = MemoryPrivacyMode.PRIVATE_LOCAL,
    val memoryModeAvailability: String = "",
    val memorySyncStatus: String = "",
    val memoryCloudStatus: String = "",
    val syncExplicit: Boolean = true,
    val syncDaily: Boolean = true,
    val syncOcr: Boolean = false,
    val syncDerived: Boolean = false,
    val ocrRetentionDays: Int = 7,
    val vaultLocked: Boolean = false,
    val vaultRequiresPassphrase: Boolean = false,
    val transcriptStorageEnabled: Boolean = false,
    val redactNamesEnabled: Boolean = true,
    val includeFullTranscriptionInExports: Boolean = false,
    val meetingRecording: Boolean = false,
    val meetingCaptureSource: CaptureSource? = null,
)

/** Platform-owned effects stay in the platform layer; this composable only renders state and dispatches intent. */
interface SettingsScreenActions {
    fun onDestinationSelected(destination: AppDestination)
    fun openAppearance()
    fun openAppLanguageSelection()
    fun openSubscription()
    fun setProviderType(type: AgentProviderType)
    fun openLocalModels()
    fun openTaskerIntegrations() = Unit
    fun setDefaultImageQuestion(question: String)
    fun resetDefaultImageQuestion()
    fun setMemoryMode(mode: MemoryPrivacyMode)
    fun setMemorySync(source: MemorySourceType, enabled: Boolean)
    fun setOcrRetentionDays(value: Int)
    fun deletePassiveCapture()
    fun lockVault()
    fun unlockVault()
    fun setVaultPassphrase()
    fun clearVaultPassphrase()
    fun resetVault()
    fun setTranscriptStorageEnabled(enabled: Boolean)
    fun setRedactNamesEnabled(enabled: Boolean)
    fun setIncludeFullTranscriptionEnabled(enabled: Boolean)
    fun exportLocalData()
    fun importLocalData()
    fun clearLocalData()
    fun sendDebugLogs()
    fun stopMeetingCapture()
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalResourceApi::class)
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    expandedSections: Set<SettingsSection>,
    onToggleSection: (SettingsSection) -> Unit,
    actions: SettingsScreenActions,
) {
    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(title = { Text(stringResource(Res.string.settings_title)) })
        },
        bottomBar = {
            NavigationBar {
                AppDestination.entries.forEach { destination ->
                    NavigationBarItem(
                        selected = destination == AppDestination.SETTINGS,
                        onClick = { actions.onDestinationSelected(destination) },
                        icon = {
                            Icon(
                                imageVector = destination.icon.imageVector(),
                                contentDescription = null,
                            )
                        },
                        label = { Text(localizedDestinationLabel(destination)) },
                    )
                }
            }
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding),
            contentPadding = PaddingValues(
                start = 16.dp,
                top = 16.dp,
                end = 16.dp,
                bottom = 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                ProSubscriptionCard(
                    isSubscribed = state.isProSubscribed,
                    proPlan = state.proPlan,
                    onClick = actions::openSubscription,
                )
            }
            if (state.meetingRecording) {
                item {
                    MeetingRecordingBanner(
                        source = state.meetingCaptureSource,
                        onStop = actions::stopMeetingCapture,
                    )
                }
            }
            item {
                Text(
                    text = stringResource(Res.string.settings_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                QuickActionCard(
                    title = stringResource(Res.string.settings_appearance),
                    subtitle = stringResource(Res.string.settings_appearance_description),
                    actionLabel = stringResource(Res.string.action_open),
                    onClick = actions::openAppearance,
                    testTag = "settings_appearance",
                )
            }
            item {
                QuickActionCard(
                    title = stringResource(Res.string.settings_language),
                    subtitle = stringResource(
                        Res.string.settings_language_description,
                        state.appLanguageLabel,
                    ),
                    actionLabel = stringResource(Res.string.action_change),
                    onClick = actions::openAppLanguageSelection,
                    testTag = "settings_language",
                )
            }
            item {
                SettingsSectionCard(
                    title = stringResource(Res.string.settings_custom_ai_provider),
                    expanded = SettingsSection.AI_AUTOMATION in expandedSections,
                    onToggle = { onToggleSection(SettingsSection.AI_AUTOMATION) },
                ) {
                    AiAutomationContent(state, actions)
                }
            }
            item {
                SettingsSectionCard(
                    title = stringResource(Res.string.settings_memory_privacy),
                    expanded = SettingsSection.MEMORY_PRIVACY in expandedSections,
                    onToggle = { onToggleSection(SettingsSection.MEMORY_PRIVACY) },
                ) {
                    MemoryPrivacyContent(state, actions)
                }
            }
            item {
                SettingsSectionCard(
                    title = stringResource(Res.string.settings_transcripts),
                    expanded = SettingsSection.TRANSCRIPTS in expandedSections,
                    onToggle = { onToggleSection(SettingsSection.TRANSCRIPTS) },
                ) {
                    TranscriptsContent(state, actions)
                }
            }
            item {
                SettingsSectionCard(
                    title = stringResource(Res.string.settings_data),
                    expanded = SettingsSection.DATA in expandedSections,
                    onToggle = { onToggleSection(SettingsSection.DATA) },
                ) {
                    DataContent(actions)
                }
            }
            item {
                SettingsSectionCard(
                    title = stringResource(Res.string.settings_support),
                    expanded = SettingsSection.SUPPORT in expandedSections,
                    onToggle = { onToggleSection(SettingsSection.SUPPORT) },
                ) {
                    SupportContent(actions)
                }
            }
            item {
                SettingsSectionCard(
                    title = stringResource(Res.string.settings_faq),
                    expanded = SettingsSection.FAQ in expandedSections,
                    onToggle = { onToggleSection(SettingsSection.FAQ) },
                ) {
                    FaqContent()
                }
            }
        }
    }
}

@Composable
private fun MeetingRecordingBanner(
    source: CaptureSource?,
    onStop: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(Res.string.settings_recording_active), style = MaterialTheme.typography.titleSmall)
                Text(
                    text = when (source) {
                        CaptureSource.BLUETOOTH_MIC -> stringResource(Res.string.settings_bluetooth_mic)
                        CaptureSource.PHONE_MIC -> stringResource(Res.string.settings_phone_mic)
                        null -> stringResource(Res.string.settings_detecting_audio_source)
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            TextButton(onClick = onStop) { Text(stringResource(Res.string.action_stop)) }
        }
    }
}

@Composable
private fun ProSubscriptionCard(
    isSubscribed: Boolean,
    proPlan: String,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("settings_subscription")
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    modifier = Modifier.size(40.dp),
                    color = MaterialTheme.colorScheme.tertiary,
                    contentColor = MaterialTheme.colorScheme.onTertiary,
                    shape = MaterialTheme.shapes.small,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.AutoAwesome,
                        contentDescription = null,
                        modifier = Modifier.padding(8.dp),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = stringResource(
                                if (isSubscribed) {
                                    Res.string.settings_pro_subscription_settings
                                } else {
                                    Res.string.settings_pro_subscription
                                },
                            ),
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Surface(
                            color = MaterialTheme.colorScheme.tertiary,
                            contentColor = MaterialTheme.colorScheme.onTertiary,
                            shape = MaterialTheme.shapes.small,
                        ) {
                            Text(
                                text = stringResource(
                                    if (isSubscribed) Res.string.settings_pro_active else Res.string.settings_pro_badge,
                                ),
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                    Text(
                        text = if (isSubscribed) {
                            stringResource(Res.string.settings_current_plan, proPlan)
                        } else {
                            stringResource(Res.string.settings_unlock_premium)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
            }
            Button(
                onClick = onClick,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.tertiary,
                    contentColor = MaterialTheme.colorScheme.onTertiary,
                ),
            ) {
                Text(
                    stringResource(
                        if (isSubscribed) Res.string.settings_manage_subscription else Res.string.settings_view_plans,
                    ),
                )
            }
        }
    }
}

@Composable
private fun QuickActionCard(
    title: String,
    subtitle: String,
    actionLabel: String,
    onClick: () -> Unit,
    testTag: String,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(testTag)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                actionLabel,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun SettingsSectionCard(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("settings_section_$title")
                    .clickable(onClick = onToggle),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Icon(
                    imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = if (expanded) {
                        stringResource(Res.string.local_models_collapse, title)
                    } else {
                        stringResource(Res.string.local_models_expand, title)
                    },
                )
            }
            if (expanded) {
                Spacer(Modifier.height(12.dp))
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    content()
                }
            }
        }
    }
}

@OptIn(ExperimentalResourceApi::class)
@Composable
private fun AiAutomationContent(state: SettingsUiState, actions: SettingsScreenActions) {
    Text(
        stringResource(Res.string.settings_provider_description),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    AgentProviderType.entries.forEach { type ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { actions.setProviderType(type) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(
                selected = state.providerType == type,
                onClick = { actions.setProviderType(type) },
            )
            Text(localizedProviderLabel(type), style = MaterialTheme.typography.bodyMedium)
        }
    }
    if (state.taskerIntegrationsAvailable) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = actions::openLocalModels,
                modifier = Modifier
                    .weight(1f)
                    .testTag("settings_local_models"),
            ) {
                Text(stringResource(Res.string.settings_configure_local_models))
            }
            OutlinedButton(
                onClick = actions::openTaskerIntegrations,
                modifier = Modifier
                    .weight(1f)
                    .testTag("settings_tasker_integrations"),
            ) {
                Text("Tasker integrations")
            }
        }
    } else {
        OutlinedButton(
            onClick = actions::openLocalModels,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(Res.string.settings_configure_local_models))
        }
    }
    Text(
        text = stringResource(Res.string.image_questions_title),
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
    )
    OutlinedTextField(
        value = state.defaultImageQuestion,
        onValueChange = actions::setDefaultImageQuestion,
        label = { Text(stringResource(Res.string.default_image_question)) },
        modifier = Modifier
            .fillMaxWidth()
            .testTag("default_image_question"),
        minLines = 3,
        maxLines = 6,
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        TextButton(onClick = actions::resetDefaultImageQuestion) {
            Text(stringResource(Res.string.reset_default_image_question))
        }
    }
}

@Composable
private fun MemoryPrivacyContent(state: SettingsUiState, actions: SettingsScreenActions) {
    Text(
        text = stringResource(Res.string.settings_current_mode, localizedMemoryModeTitle(state.memoryMode)),
        style = MaterialTheme.typography.bodyMedium,
    )
    Text(
        text = state.memoryModeAvailability.ifBlank { localizedMemoryModeDescription(state.memoryMode) },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (state.memorySyncStatus.isNotBlank()) {
        Text(
            text = state.memorySyncStatus,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (state.memoryCloudStatus.isNotBlank()) {
        Text(
            text = state.memoryCloudStatus,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    MemoryPrivacyMode.entries.forEach { mode ->
        val requiresPro = mode != MemoryPrivacyMode.PRIVATE_LOCAL
        val enabled = state.isProSubscribed || !requiresPro
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .alpha(if (enabled) 1f else 0.5f)
                .clickable(enabled = enabled) { actions.setMemoryMode(mode) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(
                selected = state.memoryMode == mode,
                onClick = { if (enabled) actions.setMemoryMode(mode) },
                enabled = enabled,
            )
            Column {
                 Text(localizedMemoryModeTitle(mode), style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = if (enabled) {
                        localizedMemoryModeDescription(mode)
                    } else {
                        stringResource(Res.string.settings_requires_pro)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    HorizontalDivider()
    Text(stringResource(Res.string.settings_sync_eligibility), style = MaterialTheme.typography.labelLarge)
    SwitchRow(stringResource(Res.string.settings_explicit_facts), state.syncExplicit) {
        actions.setMemorySync(MemorySourceType.EXPLICIT_USER_FACT, it)
    }
    SwitchRow(stringResource(Res.string.settings_daily_facts), state.syncDaily) {
        actions.setMemorySync(MemorySourceType.AUTO_DAILY_FACT, it)
    }
    SwitchRow(stringResource(Res.string.settings_screen_ocr), state.syncOcr) {
        actions.setMemorySync(MemorySourceType.SCREEN_OCR, it)
    }
    SwitchRow(stringResource(Res.string.settings_derived_summaries), state.syncDerived) {
        actions.setMemorySync(MemorySourceType.DERIVED_SUMMARY, it)
    }
    NumberSettingRow(
        label = stringResource(Res.string.settings_screen_ocr_retention),
        value = state.ocrRetentionDays,
        onValueChanged = actions::setOcrRetentionDays,
        validRange = 1..365,
    )
    ActionButton(stringResource(Res.string.settings_delete_passive_ocr), actions::deletePassiveCapture, destructive = true)
    HorizontalDivider()
    Text(
        text = buildString {
            append(
                stringResource(
                    Res.string.settings_vault_status,
                    stringResource(
                        if (state.vaultLocked) Res.string.settings_vault_locked else Res.string.settings_vault_unlocked,
                    ),
                ),
            )
            if (state.vaultRequiresPassphrase) {
                append(' ')
                append(stringResource(Res.string.settings_passphrase_required))
            }
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (state.vaultLocked) {
        ActionButton(stringResource(Res.string.settings_unlock_vault), actions::unlockVault)
    } else {
        ActionButton(stringResource(Res.string.settings_lock_vault), actions::lockVault)
    }
    ActionButton(stringResource(Res.string.settings_set_vault_passphrase), actions::setVaultPassphrase)
    ActionButton(stringResource(Res.string.settings_clear_vault_passphrase), actions::clearVaultPassphrase)
    ActionButton(stringResource(Res.string.settings_reset_memory_vault), actions::resetVault, destructive = true)
}

@Composable
private fun TranscriptsContent(state: SettingsUiState, actions: SettingsScreenActions) {
    SwitchRow(
        label = stringResource(Res.string.settings_store_transcripts),
        checked = state.transcriptStorageEnabled,
        onCheckedChange = actions::setTranscriptStorageEnabled,
    )
    SwitchRow(
        label = stringResource(Res.string.settings_redact_names),
        checked = state.redactNamesEnabled,
        onCheckedChange = actions::setRedactNamesEnabled,
    )
    SwitchRow(
        stringResource(Res.string.settings_full_transcription_exports),
        state.includeFullTranscriptionInExports,
        onCheckedChange = actions::setIncludeFullTranscriptionEnabled,
    )
}

@Composable
private fun DataContent(actions: SettingsScreenActions) {
    Text(
        text = stringResource(Res.string.settings_data_description),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    ActionButton(stringResource(Res.string.settings_export_local_data), actions::exportLocalData)
    ActionButton(stringResource(Res.string.settings_import_local_data), actions::importLocalData)
    ActionButton(stringResource(Res.string.settings_clear_local_data), actions::clearLocalData, destructive = true)
}

@Composable
private fun SupportContent(actions: SettingsScreenActions) {
    Text(
        text = stringResource(Res.string.settings_support_description),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    ActionButton(stringResource(Res.string.settings_send_debug_logs), actions::sendDebugLogs)
}

@Composable
private fun FaqContent() {
    val items = listOf(
        stringResource(Res.string.settings_faq_local_models_question) to stringResource(Res.string.settings_faq_local_models_answer),
        stringResource(Res.string.settings_faq_subscription_question) to stringResource(Res.string.settings_faq_subscription_answer),
        stringResource(Res.string.settings_faq_data_question) to stringResource(Res.string.settings_faq_data_answer),
        stringResource(Res.string.settings_faq_source_question) to stringResource(Res.string.settings_faq_source_answer),
    )
    items.forEach { (question, answer) ->
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(question, style = MaterialTheme.typography.titleSmall)
            Text(
                answer,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SettingRow(
    label: String,
    subtitle: String? = null,
    trailing: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            subtitle?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        trailing()
    }
}

@Composable
private fun SwitchRow(
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
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
        )
    }
}

@Composable
private fun NumberSettingRow(
    label: String,
    value: Int,
    onValueChanged: (Int) -> Unit,
    validRange: IntRange,
    enabled: Boolean = true,
) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    val parsed = text.toIntOrNull()
    val valid = parsed != null && parsed in validRange
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(
            value = text,
            onValueChange = { next ->
                text = next
                next.toIntOrNull()?.takeIf { it in validRange }?.let(onValueChanged)
            },
            modifier = Modifier.width(132.dp),
            enabled = enabled,
            singleLine = true,
            isError = text.isNotBlank() && !valid,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
        if (text.isNotBlank() && !valid) {
            Text(
                text = stringResource(Res.string.settings_range_error, validRange.first, validRange.last),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun ActionButton(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    destructive: Boolean = false,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = label,
            color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        )
    }
}
