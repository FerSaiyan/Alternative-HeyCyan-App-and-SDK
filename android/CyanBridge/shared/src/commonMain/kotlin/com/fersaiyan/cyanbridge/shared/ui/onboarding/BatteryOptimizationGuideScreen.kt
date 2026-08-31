package com.fersaiyan.cyanbridge.shared.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fersaiyan.cyanbridge.shared.generated.resources.Res
import com.fersaiyan.cyanbridge.shared.generated.resources.onboarding_battery_body
import com.fersaiyan.cyanbridge.shared.generated.resources.onboarding_battery_disabled
import com.fersaiyan.cyanbridge.shared.generated.resources.onboarding_battery_enabled
import com.fersaiyan.cyanbridge.shared.generated.resources.onboarding_battery_headline
import com.fersaiyan.cyanbridge.shared.generated.resources.onboarding_battery_list
import com.fersaiyan.cyanbridge.shared.generated.resources.onboarding_battery_title
import com.fersaiyan.cyanbridge.shared.generated.resources.onboarding_continue
import com.fersaiyan.cyanbridge.shared.generated.resources.onboarding_disable_battery
import com.fersaiyan.cyanbridge.shared.generated.resources.onboarding_dont_show_again
import com.fersaiyan.cyanbridge.shared.generated.resources.onboarding_lock_recents_body
import com.fersaiyan.cyanbridge.shared.generated.resources.onboarding_lock_recents_title
import com.fersaiyan.cyanbridge.shared.generated.resources.onboarding_not_now
import com.fersaiyan.cyanbridge.shared.generated.resources.onboarding_open_app_info
import com.fersaiyan.cyanbridge.shared.icons.AppIcon
import com.fersaiyan.cyanbridge.shared.icons.imageVector
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3Api::class, ExperimentalResourceApi::class)
@Composable
fun BatteryOptimizationGuideScreen(
    optimizationIgnored: Boolean,
    onDisableOptimization: () -> Unit,
    onOpenAppInfo: () -> Unit,
    onOpenOptimizationList: () -> Unit,
    onContinue: () -> Unit,
    onRemindLater: () -> Unit,
    onDontShowAgain: () -> Unit,
) {
    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(Res.string.onboarding_battery_title),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
                shape = MaterialTheme.shapes.extraLarge,
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Surface(
                        modifier = Modifier.size(64.dp),
                        color = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        shape = MaterialTheme.shapes.extraLarge,
                    ) {
                        Icon(
                            imageVector = AppIcon.Battery.imageVector(),
                            contentDescription = null,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                    Text(
                        text = stringResource(Res.string.onboarding_battery_headline),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = stringResource(Res.string.onboarding_battery_body),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = if (optimizationIgnored) {
                    MaterialTheme.colorScheme.secondaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                contentColor = if (optimizationIgnored) {
                    MaterialTheme.colorScheme.onSecondaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                shape = MaterialTheme.shapes.extraLarge,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 64.dp)
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        imageVector = AppIcon.Battery.imageVector(),
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                    )
                    Text(
                        text = if (optimizationIgnored) {
                            stringResource(Res.string.onboarding_battery_disabled)
                        } else {
                            stringResource(Res.string.onboarding_battery_enabled)
                        },
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            FilledTonalButton(
                onClick = onDisableOptimization,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp),
            ) {
                Text(stringResource(Res.string.onboarding_disable_battery))
            }
            OutlinedButton(
                onClick = onOpenOptimizationList,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
            ) {
                Text(stringResource(Res.string.onboarding_battery_list))
            }
            OutlinedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.extraLarge,
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Surface(
                        modifier = Modifier.size(48.dp),
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                        shape = MaterialTheme.shapes.large,
                    ) {
                        Icon(
                            imageVector = AppIcon.Settings.imageVector(),
                            contentDescription = null,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                    Text(
                        stringResource(Res.string.onboarding_lock_recents_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        stringResource(Res.string.onboarding_lock_recents_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(
                        onClick = onOpenAppInfo,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp),
                    ) {
                        Text(stringResource(Res.string.onboarding_open_app_info))
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            Button(
                onClick = onContinue,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp),
            ) {
                Text(stringResource(Res.string.onboarding_continue))
            }
            OutlinedButton(
                onClick = onRemindLater,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
            ) {
                Text(stringResource(Res.string.onboarding_not_now))
            }
            OutlinedButton(
                onClick = onDontShowAgain,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
            ) {
                Text(stringResource(Res.string.onboarding_dont_show_again))
            }
        }
    }
}
