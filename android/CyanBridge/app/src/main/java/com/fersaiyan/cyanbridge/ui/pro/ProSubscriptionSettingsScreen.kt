package com.fersaiyan.cyanbridge.ui.pro

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fersaiyan.cyanbridge.agent.ProSubscriptionRelayClient
import com.fersaiyan.cyanbridge.ui.theme.CyanAccent

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProSubscriptionSettingsScreen(
    onNavigateBack: () -> Unit = {},
    viewModel: ProSubscriptionSettingsViewModel = viewModel(
        factory = ProSubscriptionSettingsViewModel.Factory(LocalContext.current),
    ),
) {
    val state by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    LaunchedEffect(state.successMessage) {
        state.successMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.clearSuccessMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Pro Settings") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        snackbarHost = {
            SnackbarHost(snackbarHostState) { data ->
                Snackbar(snackbarData = data)
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            CollapsibleSection(
                title = "Plan Details",
                expanded = state.expandedSections["plan"] ?: true,
                onToggle = { viewModel.toggleSection("plan") },
            ) {
                PlanDetailsSection(
                    state = state,
                    onVerify = { viewModel.verifyNow() },
                    onChangePlan = { viewModel.showChangePlanDialog { viewModel.launchWebCheckoutWithEmail() } },
                    onRefreshAccount = { viewModel.refreshAccount() },
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            CollapsibleSection(
                title = "Beta Features",
                expanded = state.expandedSections["beta"] ?: false,
                onToggle = { viewModel.toggleSection("beta") },
            ) {
                BetaFeaturesSection(
                    status = state.betaCloudStatus,
                    onJoin = { viewModel.joinBetaCloud() },
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            CollapsibleSection(
                title = "AI Models",
                expanded = state.expandedSections["ai"] ?: true,
                onToggle = { viewModel.toggleSection("ai") },
            ) {
                AiModelsSection(
                    state = state,
                    onRefreshModels = { viewModel.refreshModels() },
                    onRequestsModelChange = { viewModel.setRequestsModel(it) },
                    onQuestionsModelChange = { viewModel.setQuestionsModel(it) },
                    onTasksModelChange = { viewModel.setTasksModel(it) },
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            CollapsibleSection(
                title = "Cloud Settings",
                expanded = state.expandedSections["cloud"] ?: false,
                onToggle = { viewModel.toggleSection("cloud") },
            ) {
                CloudSettingsSection(
                    cloudSync = state.cloudSync,
                    backupFrequencyIdx = state.backupFrequencyIdx,
                    prioritySupport = state.prioritySupport,
                    onCloudSyncChange = { viewModel.setCloudSync(it) },
                    onBackupFrequencyChange = { viewModel.setBackupFrequency(it) },
                    onPrioritySupportChange = { viewModel.setPrioritySupport(it) },
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            CollapsibleSection(
                title = "Ecosystem",
                expanded = state.expandedSections["ecosystem"] ?: false,
                onToggle = { viewModel.toggleSection("ecosystem") },
            ) {
                EcosystemSection(
                    pluginRewards = state.pluginRewards,
                    earlyAccessDevices = state.earlyAccessDevices,
                    supportChannelIdx = state.supportChannelIdx,
                    onPluginRewardsChange = { viewModel.setPluginRewards(it) },
                    onEarlyAccessDevicesChange = { viewModel.setEarlyAccessDevices(it) },
                    onSupportChannelChange = { viewModel.setSupportChannel(it) },
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = { viewModel.saveSettings() },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = CyanAccent),
            ) {
                Text("Save Settings", color = MaterialTheme.colorScheme.background)
            }

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedButton(
                onClick = onNavigateBack,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Back")
            }
        }
    }
}

@Composable
private fun CollapsibleSection(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggle() },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = if (expanded) Icons.AutoMirrored.Filled.ArrowForward else Icons.AutoMirrored.Filled.List,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (expanded) "▾ $title" else "▸ $title",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(top = 12.dp)) {
                    content()
                }
            }
        }
    }
}

@Composable
private fun PlanDetailsSection(
    state: ProSubscriptionSettingsState,
    onVerify: () -> Unit,
    onChangePlan: () -> Unit,
    onRefreshAccount: () -> Unit,
) {
    Column {
        Text(
            text = state.statusText,
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = state.planText,
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = state.expiresText,
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = state.verifiedText,
            style = MaterialTheme.typography.bodyMedium,
        )

        Spacer(modifier = Modifier.height(12.dp))

        Row(modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = onVerify,
                modifier = Modifier.weight(1f),
                enabled = !state.isLoading,
            ) {
                if (state.isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text("Verify Plan")
            }
            Spacer(modifier = Modifier.width(8.dp))
            OutlinedButton(
                onClick = onChangePlan,
                modifier = Modifier.weight(1f),
            ) {
                Text("Change Plan")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Account",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
        )
        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = state.emailText,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = state.tokenText,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = state.subscriptionText,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedButton(
            onClick = onRefreshAccount,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Refresh Account")
        }
    }
}

@Composable
private fun BetaFeaturesSection(
    status: String,
    onJoin: () -> Unit,
) {
    Column {
        Text(
            text = "Sign up for beta cloud features to get early access to cloud-powered AI and sync across devices.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (status.isNotBlank()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = status,
                style = MaterialTheme.typography.bodySmall,
                color = CyanAccent,
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        Button(
            onClick = onJoin,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = CyanAccent),
        ) {
            Text("Sign up for beta", color = MaterialTheme.colorScheme.background)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AiModelsSection(
    state: ProSubscriptionSettingsState,
    onRefreshModels: () -> Unit,
    onRequestsModelChange: (String) -> Unit,
    onQuestionsModelChange: (String) -> Unit,
    onTasksModelChange: (String) -> Unit,
) {
    Column {
        ModelDropdown(
            label = "Requests model",
            selectedModel = state.requestsModel,
            availableModels = state.availableModels,
            onModelSelected = onRequestsModelChange,
        )
        Spacer(modifier = Modifier.height(8.dp))
        ModelDropdown(
            label = "Questions model",
            selectedModel = state.questionsModel,
            availableModels = state.availableModels,
            onModelSelected = onQuestionsModelChange,
        )
        Spacer(modifier = Modifier.height(8.dp))
        ModelDropdown(
            label = "Tasks model",
            selectedModel = state.tasksModel,
            availableModels = state.availableModels,
            onModelSelected = onTasksModelChange,
        )
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedButton(
            onClick = onRefreshModels,
            modifier = Modifier.fillMaxWidth(),
            enabled = !state.modelsLoading,
        ) {
            if (state.modelsLoading) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            } else {
                Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text("Refresh models")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelDropdown(
    label: String,
    selectedModel: String,
    availableModels: List<ProSubscriptionRelayClient.ModelOption>,
    onModelSelected: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val displayModels = if (availableModels.isEmpty()) {
        listOf(ProSubscriptionRelayClient.ModelOption("auto", "auto", 1))
    } else {
        availableModels
    }

    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Box {
            OutlinedTextField(
                value = selectedModel,
                onValueChange = {},
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = true },
                readOnly = true,
                enabled = false,
                singleLine = true,
                colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                    disabledTextColor = MaterialTheme.colorScheme.onSurface,
                    disabledBorderColor = MaterialTheme.colorScheme.outline,
                    disabledPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            )
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clickable { expanded = true },
            )
            androidx.compose.material3.DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                displayModels.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option.label) },
                        onClick = {
                            onModelSelected(option.id)
                            expanded = false
                        },
                        leadingIcon = if (option.id == selectedModel) {
                            {
                                Icon(
                                    imageVector = Icons.Filled.Check,
                                    contentDescription = null,
                                    tint = CyanAccent,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        } else null,
                    )
                }
            }
        }
    }
}

@Composable
private fun CloudSettingsSection(
    cloudSync: Boolean,
    backupFrequencyIdx: Int,
    prioritySupport: Boolean,
    onCloudSyncChange: (Boolean) -> Unit,
    onBackupFrequencyChange: (Int) -> Unit,
    onPrioritySupportChange: (Boolean) -> Unit,
) {
    val backupOptions = listOf("Every 1 hour", "Every 6 hours", "Daily")

    Column {
        SwitchRow(
            label = "Cloud Sync",
            checked = cloudSync,
            onCheckedChange = onCloudSyncChange,
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Backup frequency",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(4.dp))
        SimpleDropdown(
            selectedIndex = backupFrequencyIdx,
            options = backupOptions,
            onOptionSelected = onBackupFrequencyChange,
        )

        Spacer(modifier = Modifier.height(8.dp))

        SwitchRow(
            label = "Priority Support",
            checked = prioritySupport,
            onCheckedChange = onPrioritySupportChange,
        )
    }
}

@Composable
private fun EcosystemSection(
    pluginRewards: Boolean,
    earlyAccessDevices: Boolean,
    supportChannelIdx: Int,
    onPluginRewardsChange: (Boolean) -> Unit,
    onEarlyAccessDevicesChange: (Boolean) -> Unit,
    onSupportChannelChange: (Int) -> Unit,
) {
    val supportOptions = listOf("In-app priority queue", "Email", "Discord")

    Column {
        SwitchRow(
            label = "Plugin Rewards",
            checked = pluginRewards,
            onCheckedChange = onPluginRewardsChange,
        )

        Spacer(modifier = Modifier.height(8.dp))

        SwitchRow(
            label = "Early Access Devices",
            checked = earlyAccessDevices,
            onCheckedChange = onEarlyAccessDevicesChange,
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Support Channel",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(4.dp))
        SimpleDropdown(
            selectedIndex = supportChannelIdx,
            options = supportOptions,
            onOptionSelected = onSupportChannelChange,
        )
    }
}

@Composable
private fun SwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SimpleDropdown(
    selectedIndex: Int,
    options: List<String>,
    onOptionSelected: (Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedOption = options.getOrElse(selectedIndex) { options.firstOrNull() ?: "" }

    Box {
        OutlinedTextField(
            value = selectedOption,
            onValueChange = {},
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = true },
            readOnly = true,
            enabled = false,
            singleLine = true,
            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                disabledTextColor = MaterialTheme.colorScheme.onSurface,
                disabledBorderColor = MaterialTheme.colorScheme.outline,
                disabledPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        )
        Box(
            modifier = Modifier
                .matchParentSize()
                .clickable { expanded = true },
        )
        androidx.compose.material3.DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            options.forEachIndexed { index, option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        onOptionSelected(index)
                        expanded = false
                    },
                    leadingIcon = if (index == selectedIndex) {
                        {
                            Icon(
                                imageVector = Icons.Filled.Check,
                                contentDescription = null,
                                tint = CyanAccent,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    } else null,
                )
            }
        }
    }
}