package com.fersaiyan.cyanbridge.ui.pro

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fersaiyan.cyanbridge.agent.ProSubscriptionRelayClient
import com.fersaiyan.cyanbridge.ui.theme.CyanAccent
import com.fersaiyan.cyanbridge.ui.theme.Danger

data class PlanOption(val id: String, val label: String, val price: String)

private val PLANS = listOf(
    PlanOption("free_trial", "Free Trial", "30 days free"),
    PlanOption("cheap", "Cheap", "$1/month"),
    PlanOption("standard", "Standard", "$5/month"),
    PlanOption("max", "Max", "$20/month"),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProScreen(
    onNavigateToSettings: () -> Unit = {},
    viewModel: ProViewModel = viewModel(
        factory = ProViewModel.Factory(
            context = LocalContext.current,
            onNavigateToSettings = onNavigateToSettings,
        ),
    ),
) {
    val state by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    LaunchedEffect(state.error) {
        state.error?.let { error ->
            snackbarHostState.showSnackbar(error)
            viewModel.clearError()
        }
    }

    LaunchedEffect(state.successMessage) {
        state.successMessage?.let { msg ->
            if (msg.startsWith("OPEN_CHECKOUT:")) {
                val uri = msg.removePrefix("OPEN_CHECKOUT:")
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)))
                }.onFailure {
                    snackbarHostState.showSnackbar("Could not open checkout URL")
                }
            } else {
                snackbarHostState.showSnackbar(msg)
            }
            viewModel.clearSuccess()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Pro Subscription") },
                actions = {
                    IconButton(onClick = { viewModel.loadState() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        snackbarHost = {
            SnackbarHost(snackbarHostState) { data ->
                Snackbar(
                    snackbarData = data,
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        if (state.isSubscribed) {
            ProDashboard(
                state = state,
                viewModel = viewModel,
                modifier = Modifier.padding(padding),
            )
        } else {
            ProSubscribe(
                state = state,
                viewModel = viewModel,
                modifier = Modifier.padding(padding),
            )
        }
    }
}

@Composable
private fun ProSubscribe(
    state: ProScreenState,
    viewModel: ProViewModel,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(80.dp)
                .background(
                    color = CyanAccent.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(20.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Star,
                contentDescription = null,
                tint = CyanAccent,
                modifier = Modifier.size(48.dp),
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "CyanBridge Pro",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = CyanAccent,
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Unlock the full power of your smartglasses",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(24.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Choose your plan",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )

                Spacer(modifier = Modifier.height(12.dp))

                PLANS.forEach { plan ->
                    val isSelected = state.selectedPlan == plan.id
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.setSelectedPlan(plan.id) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = isSelected,
                            onClick = { viewModel.setSelectedPlan(plan.id) },
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = plan.label,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                text = plan.price,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (isSelected) {
                            Icon(
                                imageVector = Icons.Filled.Check,
                                contentDescription = null,
                                tint = CyanAccent,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = { viewModel.subscribe() },
            modifier = Modifier.fillMaxWidth(),
            enabled = !state.isLoading,
            colors = ButtonDefaults.buttonColors(containerColor = CyanAccent),
        ) {
            if (state.isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = MaterialTheme.colorScheme.background,
                    strokeWidth = 2.dp,
                )
            } else {
                Text(
                    text = if (state.selectedPlan == "free_trial") "Activate Free Trial"
                           else "Subscribe — ${PLANS.find { it.id == state.selectedPlan }?.price ?: ""}",
                    color = MaterialTheme.colorScheme.background,
                )
            }
        }

        if (state.webCheckoutEnabled && state.selectedPlan != "free_trial") {
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = { viewModel.subscribe() },
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.isLoading,
            ) {
                Text("Continue to payment")
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "All plans include: Priority support, cloud sync, beta features access",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ProDashboard(
    state: ProScreenState,
    viewModel: ProViewModel,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        StatusBanner(
            plan = state.planDetails,
            quota = state.quota,
            onVerify = { viewModel.verifySubscription() },
            onRefreshQuota = { viewModel.refreshQuota() },
            isLoading = state.isLoading,
        )

        Spacer(modifier = Modifier.height(16.dp))

        PlanDetailsCard(
            plan = state.planDetails,
            onChangePlan = { viewModel.changePlan("standard") },
        )

        Spacer(modifier = Modifier.height(16.dp))

        AiModelsCard(
            state = state,
            viewModel = viewModel,
        )

        Spacer(modifier = Modifier.height(16.dp))

        BetaCloudCard(
            message = state.betaCloudMessage,
            isLoading = state.betaCloudLoading,
            onJoin = { viewModel.joinBetaCloud() },
        )

        Spacer(modifier = Modifier.height(16.dp))

        AccountCard(
            account = state.account,
            onRefresh = { viewModel.loadState() },
        )
    }
}

@Composable
private fun StatusBanner(
    plan: PlanDetails?,
    quota: QuotaDetails,
    onVerify: () -> Unit,
    onRefreshQuota: () -> Unit,
    isLoading: Boolean,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = CyanAccent.copy(alpha = 0.1f),
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.Star,
                        contentDescription = null,
                        tint = CyanAccent,
                        modifier = Modifier.size(24.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Pro Active",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = CyanAccent,
                    )
                }
                if (plan != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Plan: ${plan.plan} · Source: ${plan.provider}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Expires: ${plan.expiresAt}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = quota.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                IconButton(onClick = onVerify, enabled = !isLoading) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = "Verify subscription",
                            tint = CyanAccent,
                        )
                    }
                }
                IconButton(onClick = onRefreshQuota) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = "Refresh quota",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun PlanDetailsCard(
    plan: PlanDetails?,
    onChangePlan: () -> Unit,
) {
    SectionCard(title = "Plan Details") {
        if (plan != null) {
            InfoRow("Status", plan.status)
            InfoRow("Plan", plan.plan)
            InfoRow("Expires", plan.expiresAt)
            InfoRow("Last verified", plan.lastVerified)
            InfoRow("Provider", plan.provider)
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = onChangePlan,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Change Plan")
            }
        } else {
            Text(
                text = "Loading plan details...",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AiModelsCard(
    state: ProScreenState,
    viewModel: ProViewModel,
) {
    SectionCard(title = "AI Model Preferences") {
        ModelDropdown(
            label = "Requests model",
            selectedModel = state.requestsModel,
            availableModels = state.availableModels,
            onModelSelected = { viewModel.setRequestsModel(it) },
        )
        Spacer(modifier = Modifier.height(8.dp))
        ModelDropdown(
            label = "Questions model",
            selectedModel = state.questionsModel,
            availableModels = state.availableModels,
            onModelSelected = { viewModel.setQuestionsModel(it) },
        )
        Spacer(modifier = Modifier.height(8.dp))
        ModelDropdown(
            label = "Tasks model",
            selectedModel = state.tasksModel,
            availableModels = state.availableModels,
            onModelSelected = { viewModel.setTasksModel(it) },
        )
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedButton(
            onClick = { viewModel.refreshModels() },
            modifier = Modifier.fillMaxWidth(),
            enabled = !state.isLoading,
        ) {
            Text("Refresh models from server")
        }
    }
}

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
private fun BetaCloudCard(
    message: String?,
    isLoading: Boolean,
    onJoin: () -> Unit,
) {
    SectionCard(title = "Beta: Cloud Features") {
        Text(
            text = "Get early access to cloud-powered AI features and sync across devices.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (message != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = CyanAccent,
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        Button(
            onClick = onJoin,
            modifier = Modifier.fillMaxWidth(),
            enabled = !isLoading,
            colors = ButtonDefaults.buttonColors(containerColor = CyanAccent),
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = MaterialTheme.colorScheme.background,
                    strokeWidth = 2.dp,
                )
            } else {
                Text("Sign up for beta", color = MaterialTheme.colorScheme.background)
            }
        }
    }
}

@Composable
private fun AccountCard(
    account: AccountDetails,
    onRefresh: () -> Unit,
) {
    SectionCard(title = "Account") {
        InfoRow("Email", account.email, isLoading = account.isLoading && account.email == "loading...")
        InfoRow("API token", account.token, isLoading = account.isLoading && account.token == "loading...")
        InfoRow("Subscription", account.subscription, isLoading = account.isLoading && account.subscription == "loading...")
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(
            onClick = onRefresh,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Refresh account")
        }
    }
}

@Composable
private fun SectionCard(
    title: String,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun InfoRow(
    label: String,
    value: String,
    isLoading: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                strokeWidth = 2.dp,
            )
        } else {
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
