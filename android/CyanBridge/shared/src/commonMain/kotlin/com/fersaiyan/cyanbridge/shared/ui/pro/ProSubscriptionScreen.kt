package com.fersaiyan.cyanbridge.shared.ui.pro

import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import com.fersaiyan.cyanbridge.shared.billing.BillingProvider
import com.fersaiyan.cyanbridge.shared.billing.BillingCatalog
import com.fersaiyan.cyanbridge.shared.billing.ProviderOffer
import com.fersaiyan.cyanbridge.shared.billing.ProSubscriptionUiState
import com.fersaiyan.cyanbridge.shared.generated.resources.*
import kotlin.math.roundToInt
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalResourceApi::class)
@Composable
private fun planLabels(state: ProSubscriptionUiState) = buildList {
    add("free_trial" to stringResource(Res.string.pro_free_trial))
    addAll(
        BillingCatalog.plans.map { plan ->
            val price = state.playPriceLabels[plan.id]
            val label = price?.let { stringResource(Res.string.pro_google_play_price, it) }
                ?: if (plan.id in state.playCheckoutAvailablePlans) {
                    stringResource(Res.string.pro_google_play_checkout_price)
                } else {
                    stringResource(Res.string.pro_choose_checkout_compare)
                }
            plan.id to "${plan.name} · $label"
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalResourceApi::class)
@Composable
fun ProSubscriptionScreen(
    state: ProSubscriptionUiState,
    onPlanSelected: (String) -> Unit,
    onStartFreeTrial: () -> Unit,
    onSubscribeWithGooglePlay: () -> Unit,
    onSubscribeOnWebsite: (BillingProvider) -> Unit,
    onCheckoutUnavailable: () -> Unit,
    onDonate: () -> Unit,
    onCancelSubscription: () -> Unit,
    onBack: () -> Unit,
) {
    var showCheckoutChoices by remember { mutableStateOf(false) }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
         topBar = { TopAppBar(title = { Text(stringResource(Res.string.pro_subscription_title)) }) },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { ProHero() }
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.extraLarge,
                ) {
                    Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                         Text(stringResource(Res.string.pro_choose_plan), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        planLabels(state).forEach { (id, label) ->
                            FilterChip(
                                selected = state.selectedPlan == id,
                                onClick = { onPlanSelected(id) },
                                label = { Text(label) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
             item { BenefitCard(stringResource(Res.string.pro_benefit_support), stringResource(Res.string.pro_benefit_support_description)) }
             item { BenefitCard(stringResource(Res.string.pro_benefit_plugins), stringResource(Res.string.pro_benefit_plugins_description)) }
             item { BenefitCard(stringResource(Res.string.pro_benefit_cloud), stringResource(Res.string.pro_benefit_cloud_description)) }
             item { BenefitCard(stringResource(Res.string.pro_benefit_priority), stringResource(Res.string.pro_benefit_priority_description)) }
            if (state.webCheckoutAvailable) {
                item {
                    Card(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge) {
                        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                             Text(stringResource(Res.string.pro_checkout_choices), style = MaterialTheme.typography.titleSmall)
                            Text(
                                 stringResource(Res.string.pro_checkout_choices_description),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            item {
                OutlinedButton(onClick = onDonate, modifier = Modifier.fillMaxWidth()) {
                     Text(stringResource(Res.string.pro_donate_asaas))
                }
            }
            if (state.isSubscribed) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        ),
                        shape = MaterialTheme.shapes.extraLarge,
                    ) {
                        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                             Text(stringResource(Res.string.pro_cancel_subscription), style = MaterialTheme.typography.titleSmall)
                            Text(
                                 stringResource(Res.string.pro_cancel_description),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                            TextButton(
                                onClick = onCancelSubscription,
                                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                            ) {
                                 Text(stringResource(Res.string.pro_cancel_subscription))
                            }
                        }
                    }
                }
            }
            item {
                Text(
                    state.status,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                     OutlinedButton(onClick = onBack) { Text(stringResource(Res.string.pro_back)) }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            if (state.selectedPlan == "free_trial") {
                                onStartFreeTrial()
                            } else if (
                                state.webCheckoutAvailable ||
                                    (
                                        state.selectedPlan in state.playCheckoutAvailablePlans &&
                                            state.googlePlayCheckoutAllowed
                                    )
                            ) {
                                showCheckoutChoices = true
                            } else {
                                onCheckoutUnavailable()
                            }
                        },
                    ) {
                         Text(
                             stringResource(
                                 if (state.selectedPlan == "free_trial") Res.string.pro_start_free_trial else Res.string.pro_choose_checkout,
                             ),
                         )
                    }
                }
            }
        }
    }

    if (showCheckoutChoices) {
        CheckoutChoiceDialog(
            state = state,
            onDismiss = { showCheckoutChoices = false },
            onGooglePlaySelected = {
                showCheckoutChoices = false
                onSubscribeWithGooglePlay()
            },
            onWebProviderSelected = { provider ->
                showCheckoutChoices = false
                onSubscribeOnWebsite(provider)
            },
        )
    }
}

@OptIn(ExperimentalResourceApi::class)
@Composable
private fun CheckoutChoiceDialog(
    state: ProSubscriptionUiState,
    onDismiss: () -> Unit,
    onGooglePlaySelected: () -> Unit,
    onWebProviderSelected: (BillingProvider) -> Unit,
) {
    val plan = BillingCatalog.plan(state.selectedPlan)
    val playPrice = state.playPriceLabels[plan.id]
    val playAvailable = plan.id in state.playCheckoutAvailablePlans && state.googlePlayCheckoutAllowed
    var selectedWebProvider by remember(plan.id) {
        mutableStateOf(BillingProvider.ASAAS.wireName)
    }
    val webProvider = if (selectedWebProvider == BillingProvider.PADDLE.wireName) {
        BillingProvider.PADDLE
    } else {
        BillingProvider.ASAAS
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            DialogIconBadge {
                Icon(Icons.Outlined.AutoAwesome, contentDescription = null)
            }
        },
         title = { Text(stringResource(Res.string.pro_choose_checkout_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                     stringResource(Res.string.pro_monthly_renewal, plan.name),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (state.webCheckoutAvailable) {
                     Text(stringResource(Res.string.pro_web_checkout), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    ProviderChoice(
                        provider = BillingProvider.ASAAS,
                        offer = plan.asaasOffer,
                        selected = webProvider == BillingProvider.ASAAS,
                        onClick = { selectedWebProvider = BillingProvider.ASAAS.wireName },
                    )
                    ProviderChoice(
                        provider = BillingProvider.PADDLE,
                        offer = plan.paddleOffer,
                        selected = webProvider == BillingProvider.PADDLE,
                        onClick = { selectedWebProvider = BillingProvider.PADDLE.wireName },
                    )
                    Text(
                        webCheckoutDescription(webProvider),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        onClick = { onWebProviderSelected(webProvider) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                         Text(stringResource(Res.string.pro_continue_provider, providerName(webProvider)))
                    }
                }
                if (playAvailable || state.webCheckoutAvailable) {
                     Text(stringResource(Res.string.pro_google_play), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                }
                Text(
                    if (playPrice != null) {
                         stringResource(Res.string.pro_google_play_price_detail, playPrice)
                    } else if (!state.googlePlayCheckoutAllowed) {
                         stringResource(Res.string.pro_change_web_subscription)
                    } else {
                         stringResource(Res.string.pro_google_play_availability)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = onGooglePlaySelected,
                    enabled = playAvailable,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        when {
                             playAvailable -> stringResource(Res.string.pro_use_google_play)
                             !state.googlePlayCheckoutAllowed -> stringResource(Res.string.pro_use_web_checkout)
                             else -> stringResource(Res.string.pro_google_play_unavailable)
                        },
                    )
                }
            }
        },
        confirmButton = {
             TextButton(onClick = onDismiss) { Text(stringResource(Res.string.pro_not_now)) }
        },
    )
}

@OptIn(ExperimentalResourceApi::class)
@Composable
private fun ProviderChoice(
    provider: BillingProvider,
    offer: ProviderOffer,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(modifier = Modifier.padding(start = 8.dp)) {
            Text(providerName(provider), style = MaterialTheme.typography.bodyLarge)
            Text(
                providerPriceLabel(provider, offer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun providerName(provider: BillingProvider): String = when (provider) {
    BillingProvider.ASAAS -> "Asaas"
    BillingProvider.PADDLE -> "Paddle"
    BillingProvider.GOOGLE_PLAY -> "Google Play"
}

@OptIn(ExperimentalResourceApi::class)
@Composable
private fun providerPriceLabel(provider: BillingProvider, offer: ProviderOffer): String = when (provider) {
    BillingProvider.ASAAS ->
        stringResource(
            Res.string.pro_asaas_price,
            formatUsd(offer.referencePriceUsd),
            formatUsd(offer.adjustmentUsd),
        )
    BillingProvider.PADDLE ->
        stringResource(
            Res.string.pro_paddle_price,
            formatUsd(offer.referencePriceUsd),
            formatUsd(offer.adjustmentUsd),
        )
    BillingProvider.GOOGLE_PLAY -> stringResource(Res.string.pro_google_price)
}

@OptIn(ExperimentalResourceApi::class)
@Composable
private fun webCheckoutDescription(provider: BillingProvider): String = when (provider) {
    BillingProvider.ASAAS ->
        stringResource(Res.string.pro_asaas_description)
    BillingProvider.PADDLE ->
        stringResource(Res.string.pro_paddle_description)
    BillingProvider.GOOGLE_PLAY -> ""
}

private fun formatUsd(amount: Double): String {
    val cents = (amount * 100.0).roundToInt()
    val whole = cents / 100
    val fraction = (cents % 100).toString().padStart(2, '0')
    return "\$$whole.$fraction"
}

@Composable
private fun BenefitCard(title: String, description: String) {
    Card(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalResourceApi::class)
@Composable
private fun ProHero() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        ),
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Surface(
                modifier = Modifier.size(56.dp),
                color = MaterialTheme.colorScheme.tertiary,
                contentColor = MaterialTheme.colorScheme.onTertiary,
                shape = MaterialTheme.shapes.large,
            ) {
                Icon(
                    Icons.Outlined.AutoAwesome,
                    contentDescription = null,
                    modifier = Modifier.padding(14.dp),
                )
            }
            Text(
                stringResource(Res.string.pro_subscription_title),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(stringResource(Res.string.pro_intro), style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun DialogIconBadge(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.size(48.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.large,
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
    }
}
