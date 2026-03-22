package com.fersaiyan.cyanbridge.ui.settings

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fersaiyan.cyanbridge.agent.AgentProviderType
import com.fersaiyan.cyanbridge.agent.ProSubscriptionActivity
import com.fersaiyan.cyanbridge.agent.ProSubscriptionSettingsActivity
import com.fersaiyan.cyanbridge.ai.router.AiProviderType
import com.fersaiyan.cyanbridge.ai.router.CliRelayBackend
import com.fersaiyan.cyanbridge.ui.theme.CyanAccent
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
                    val intent = if (isSubscribed) {
                        Intent(context, ProSubscriptionSettingsActivity::class.java)
                    } else {
                        Intent(context, ProSubscriptionActivity::class.java)
                    }
                    context.startActivity(intent)
                },
            )

            Spacer(modifier = Modifier.height(16.dp))

            ThemeSection(
                isDarkTheme = state.isDarkTheme,
                onToggle = { viewModel.setDarkTheme(it) },
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
            )

            Spacer(modifier = Modifier.height(16.dp))

            ModelSection(
                requestsModel = state.requestsModel,
                questionsModel = state.questionsModel,
                tasksModel = state.tasksModel,
                onRequestsModelChange = { viewModel.setRequestsModel(it) },
                onQuestionsModelChange = { viewModel.setQuestionsModel(it) },
                onTasksModelChange = { viewModel.setTasksModel(it) },
            )

            Spacer(modifier = Modifier.height(16.dp))

            QuickLinksSection(
                onNavigate = onNavigate,
            )

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun ProSubscriptionSection(
    context: android.content.Context,
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
                    tint = if (isSubscribed) CyanAccent else MaterialTheme.colorScheme.onSurfaceVariant,
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
                            color = CyanAccent,
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
) {
    SettingsCard(title = "Appearance") {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (isDarkTheme) Icons.Filled.Star else Icons.Filled.Star,
                contentDescription = null,
                tint = CyanAccent,
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
private fun ModelSection(
    requestsModel: String,
    questionsModel: String,
    tasksModel: String,
    onRequestsModelChange: (String) -> Unit,
    onQuestionsModelChange: (String) -> Unit,
    onTasksModelChange: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(true) }

    SettingsCard(
        title = "AI Model Selection",
        onExpandToggle = { expanded = !expanded },
        expanded = expanded,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ModelDropdown(
                label = "Requests Model",
                value = requestsModel,
                onValueChange = onRequestsModelChange,
            )
            ModelDropdown(
                label = "Questions Model",
                value = questionsModel,
                onValueChange = onQuestionsModelChange,
            )
            ModelDropdown(
                label = "Tasks Model",
                value = tasksModel,
                onValueChange = onTasksModelChange,
            )
        }
    }
}

@Composable
private fun ModelDropdown(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
) {
    val models = listOf("auto", "gpt-5.4", "minimax/minimax-m2.5", "z-ai/glm-5", "google/gemini-3-flash-preview")
    var expanded by remember { mutableStateOf(false) }

    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Box {
            OutlinedTextField(
                value = value,
                onValueChange = {},
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = true },
                readOnly = true,
                singleLine = true,
                trailingIcon = {
                    Icon(
                        imageVector = Icons.Filled.ArrowForward,
                        contentDescription = null,
                        modifier = Modifier
                            .size(20.dp)
                            .clickable { expanded = true },
                    )
                },
                shape = RoundedCornerShape(8.dp),
                textStyle = MaterialTheme.typography.bodyMedium,
            )
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.background(MaterialTheme.colorScheme.surface),
            ) {
                models.forEach { model ->
                    DropdownMenuItem(
                        text = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    text = model,
                                    modifier = Modifier.weight(1f),
                                )
                                if (model == value) {
                                    Icon(
                                        imageVector = Icons.Filled.Check,
                                        contentDescription = "Selected",
                                        tint = CyanAccent,
                                    )
                                }
                            }
                        },
                        onClick = {
                            onValueChange(model)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun QuickLinksSection(
    onNavigate: (String) -> Unit,
) {
    SettingsCard(title = "Advanced") {
        Column {
            QuickLinkItem(
                label = "Local Agent Settings",
                subtitle = "Memory, accessibility, auto-capture",
                onClick = { onNavigate("local_agent") },
            )
            QuickLinkItem(
                label = "Privacy & Memory",
                subtitle = "Data management, local backup",
                onClick = { onNavigate("privacy") },
            )
            QuickLinkItem(
                label = "Data Export / Import",
                subtitle = "Backup and restore app data",
                onClick = { onNavigate("data") },
            )
            QuickLinkItem(
                label = "About",
                subtitle = "App version and credits",
                onClick = { onNavigate("about") },
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
                    color = CyanAccent,
                    modifier = Modifier.weight(1f),
                )
                if (onExpandToggle != null) {
                    Icon(
                        imageVector = if (expanded) Icons.Filled.Check else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = if (expanded) "Collapse" else "Expand",
                        tint = CyanAccent,
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
                if (selected) CyanAccent.copy(alpha = 0.15f)
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
                    tint = CyanAccent,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(4.dp))
            }
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = if (selected) CyanAccent else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
