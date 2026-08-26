package com.fersaiyan.cyanbridge.shared.ui.pro

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fersaiyan.cyanbridge.shared.billing.BillingCatalog
import com.fersaiyan.cyanbridge.shared.billing.BillingPlan
import com.fersaiyan.cyanbridge.shared.billing.ProSubscriptionSettingsUiState
import com.fersaiyan.cyanbridge.shared.generated.resources.*
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3Api::class, ExperimentalResourceApi::class)
@Composable
fun ProSubscriptionSettingsScreen(
    state: ProSubscriptionSettingsUiState,
    onRefreshPlan: () -> Unit,
    onChangePlan: (String) -> Unit,
    onCancelSubscription: () -> Unit,
    onRefreshAccount: () -> Unit,
    onRefreshQuota: () -> Unit,
    onRefreshModels: () -> Unit,
    onJoinBeta: () -> Unit,
    onStartGeminiLive: () -> Unit,
    onCloudSyncChange: (Boolean) -> Unit,
    onPrioritySupportChange: (Boolean) -> Unit,
    onPluginRewardsChange: (Boolean) -> Unit,
    onEarlyAccessDevicesChange: (Boolean) -> Unit,
    onBackupFrequencyChange: (Int) -> Unit,
    onSupportChannelChange: (Int) -> Unit,
    onRequestsModelChange: (String) -> Unit,
    onQuestionsModelChange: (String) -> Unit,
    onTasksModelChange: (String) -> Unit,
    onSystemPromptChange: (String) -> Unit,
    onResetSystemPrompt: () -> Unit,
    onBack: () -> Unit,
) {
    var showChangePlanDialog by remember { mutableStateOf(false) }
    var showCancelDialog by remember { mutableStateOf(false) }
    var selectedPlanId by remember(state.plan) {
        mutableStateOf(
            BillingCatalog.plans.firstOrNull { it.id == state.plan.removePrefix("Plan: ").trim() }?.id
                ?: BillingCatalog.plans.getOrNull(1)?.id
                ?: BillingCatalog.plans.firstOrNull()?.id.orEmpty(),
        )
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
         topBar = { TopAppBar(title = { Text(stringResource(Res.string.pro_settings_title)) }) },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                 ProSettingsCard(stringResource(Res.string.pro_plan)) {
                    Text(state.planStatus)
                    Text(state.plan, style = MaterialTheme.typography.bodySmall)
                    Text(state.expires, style = MaterialTheme.typography.bodySmall)
                    Text(state.verified, style = MaterialTheme.typography.bodySmall)
                    ActionButtons(
                         primaryLabel = stringResource(Res.string.pro_refresh),
                        onPrimary = onRefreshPlan,
                         secondaryLabel = stringResource(Res.string.pro_change_plan),
                        onSecondary = { showChangePlanDialog = true },
                    )
                    OutlinedButton(
                        onClick = { showCancelDialog = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                         Text(stringResource(Res.string.pro_cancel_subscription))
                    }
                }
            }
            item {
                 ProSettingsCard(stringResource(Res.string.pro_account)) {
                    Text(state.accountEmail, style = MaterialTheme.typography.bodySmall)
                    Text(state.accountToken, style = MaterialTheme.typography.bodySmall)
                    Text(state.accountSubscription, style = MaterialTheme.typography.bodySmall)
                     OutlinedButton(onClick = onRefreshAccount, modifier = Modifier.fillMaxWidth()) { Text(stringResource(Res.string.pro_refresh_account)) }
                }
            }
            item {
                 ProSettingsCard(stringResource(Res.string.pro_model_routing)) {
                     ModelChoice(stringResource(Res.string.pro_requests), state.requestsModel, state.modelOptions, onRequestsModelChange)
                     ModelChoice(stringResource(Res.string.pro_questions), state.questionsModel, state.modelOptions, onQuestionsModelChange)
                     ModelChoice(stringResource(Res.string.pro_tasks), state.tasksModel, state.modelOptions, onTasksModelChange)
                    OutlinedTextField(
                        value = state.systemPrompt,
                        onValueChange = onSystemPromptChange,
                        label = { Text(stringResource(Res.string.pro_system_prompt)) },
                        minLines = 4,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TextButton(onClick = onResetSystemPrompt) {
                        Text(stringResource(Res.string.pro_reset_system_prompt))
                    }
                    ActionButtons(
                         primaryLabel = stringResource(Res.string.pro_refresh_models),
                        onPrimary = onRefreshModels,
                         secondaryLabel = stringResource(Res.string.pro_refresh_quota),
                        onSecondary = onRefreshQuota,
                    )
                    Text(state.quotaStatus, style = MaterialTheme.typography.bodySmall)
                    if (state.quotaBreakdown.isNotBlank()) {
                        Text(state.quotaBreakdown, style = MaterialTheme.typography.bodySmall)
                    }
                    state.quotaProgress?.let { percent ->
                        LinearProgressIndicator(
                            progress = { percent.coerceIn(0, 100) / 100f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
            item {
                 ProSettingsCard(stringResource(Res.string.pro_preferences)) {
                     ToggleSetting(stringResource(Res.string.pro_cloud_sync), state.cloudSync, onCloudSyncChange)
                     ToggleSetting(stringResource(Res.string.pro_priority_support), state.prioritySupport, onPrioritySupportChange)
                     ToggleSetting(stringResource(Res.string.pro_plugin_rewards), state.pluginRewards, onPluginRewardsChange)
                     ToggleSetting(stringResource(Res.string.pro_early_device_access), state.earlyAccessDevices, onEarlyAccessDevicesChange)
                    ChoiceChips(
                         title = stringResource(Res.string.pro_backup_frequency),
                         labels = listOf(
                             stringResource(Res.string.pro_one_hour),
                             stringResource(Res.string.pro_six_hours),
                             stringResource(Res.string.pro_daily),
                         ),
                        selectedIndex = state.backupFrequencyIndex,
                        onSelected = onBackupFrequencyChange,
                    )
                    ChoiceChips(
                         title = stringResource(Res.string.pro_support_channel),
                         labels = listOf(
                             stringResource(Res.string.pro_in_app),
                             stringResource(Res.string.pro_email),
                             stringResource(Res.string.pro_discord),
                         ),
                        selectedIndex = state.supportChannelIndex,
                        onSelected = onSupportChannelChange,
                    )
                }
            }
            item {
                 ProSettingsCard(stringResource(Res.string.pro_beta_cloud)) {
                    Text(
                         state.betaStatus.ifBlank { stringResource(Res.string.pro_beta_interest) },
                        style = MaterialTheme.typography.bodySmall,
                    )
                     OutlinedButton(onClick = onJoinBeta, modifier = Modifier.fillMaxWidth()) { Text(stringResource(Res.string.pro_signup_beta)) }
                }
            }
            item {
                 ProSettingsCard(stringResource(Res.string.pro_gemini_live_title)) {
                    Text(
                         stringResource(Res.string.pro_gemini_live_description),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedButton(onClick = onStartGeminiLive, modifier = Modifier.fillMaxWidth()) {
                         Text(stringResource(Res.string.pro_open_gemini_live))
                    }
                }
            }
            item {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    OutlinedButton(onClick = onBack) { Text(stringResource(Res.string.pro_back)) }
                }
            }
        }
    }

    if (showChangePlanDialog) {
        AlertDialog(
            onDismissRequest = { showChangePlanDialog = false },
             title = { Text(stringResource(Res.string.pro_change_plan)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    BillingCatalog.plans.forEach { plan ->
                        PlanChoice(
                            plan = plan,
                            selected = selectedPlanId == plan.id,
                            onClick = { selectedPlanId = plan.id },
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showChangePlanDialog = false
                        onChangePlan(selectedPlanId)
                    },
                 ) { Text(stringResource(Res.string.onboarding_continue)) }
            },
            dismissButton = {
                 TextButton(onClick = { showChangePlanDialog = false }) { Text(stringResource(Res.string.action_cancel)) }
            },
        )
    }

    if (showCancelDialog) {
        val isFreeTrial = state.plan.removePrefix("Plan: ").trim() == "free_trial"
        AlertDialog(
            onDismissRequest = { showCancelDialog = false },
             title = { Text(stringResource(Res.string.pro_cancel_question)) },
            text = {
                Text(
                    if (isFreeTrial) {
                         stringResource(Res.string.pro_cancel_trial_question)
                    } else {
                        stringResource(Res.string.pro_cancel_period)
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showCancelDialog = false
                        onCancelSubscription()
                    },
                 ) { Text(stringResource(Res.string.pro_yes_cancel)) }
            },
            dismissButton = {
                 TextButton(onClick = { showCancelDialog = false }) { Text(stringResource(Res.string.pro_keep_subscription)) }
            },
        )
    }
}

@OptIn(ExperimentalResourceApi::class)
@Composable
private fun PlanChoice(
    plan: BillingPlan,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(modifier = Modifier.padding(start = 8.dp)) {
            Text(plan.name, style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(Res.string.pro_plan_localized_terms),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ProSettingsCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun ToggleSetting(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun ActionButtons(
    primaryLabel: String,
    onPrimary: () -> Unit,
    secondaryLabel: String,
    onSecondary: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilledTonalButton(onClick = onPrimary, modifier = Modifier.weight(1f)) { Text(primaryLabel) }
        OutlinedButton(onClick = onSecondary, modifier = Modifier.weight(1f)) { Text(secondaryLabel) }
    }
}

@Composable
private fun ChoiceChips(
    title: String,
    labels: List<String>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
) {
    Text(title, style = MaterialTheme.typography.labelLarge)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        labels.forEachIndexed { index, label ->
            FilterChip(selected = index == selectedIndex, onClick = { onSelected(index) }, label = { Text(label) })
        }
    }
}

@OptIn(ExperimentalResourceApi::class)
@Composable
private fun ModelChoice(
    label: String,
    value: String,
    options: List<String>,
    onSelected: (String) -> Unit,
) {
    var showChoices by remember { mutableStateOf(false) }
    TextButton(onClick = { showChoices = true }, modifier = Modifier.fillMaxWidth()) {
         Text(stringResource(Res.string.pro_model_title, label) + ": " + value.ifBlank { stringResource(Res.string.pro_select_model) })
    }
    if (showChoices) {
        AlertDialog(
            onDismissRequest = { showChoices = false },
             title = { Text(stringResource(Res.string.pro_model_title, label)) },
            text = {
                LazyColumn {
                    options.forEach { option ->
                        item {
                            TextButton(
                                onClick = {
                                    onSelected(option)
                                    showChoices = false
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text(option) }
                        }
                    }
                }
            },
             confirmButton = { TextButton(onClick = { showChoices = false }) { Text(stringResource(Res.string.pro_close)) } },
        )
    }
}
