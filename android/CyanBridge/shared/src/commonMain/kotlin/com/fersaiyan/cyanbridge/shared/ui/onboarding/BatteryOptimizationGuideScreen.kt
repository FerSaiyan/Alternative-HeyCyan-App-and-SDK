package com.fersaiyan.cyanbridge.shared.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fersaiyan.cyanbridge.shared.generated.resources.*
import com.fersaiyan.cyanbridge.shared.icons.AppIcon
import com.fersaiyan.cyanbridge.shared.icons.imageVector
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalResourceApi::class)
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
    Scaffold(contentWindowInsets = WindowInsets.safeDrawing) { insets ->
        Column(Modifier.fillMaxSize().padding(insets)) {
            Column(Modifier.padding(horizontal = 24.dp, vertical = 18.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("CYANBRIDGE", style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    Text("Setup", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(progress = { 0.2f }, Modifier.fillMaxWidth().height(6.dp),
                    trackColor = MaterialTheme.colorScheme.surfaceVariant)
            }
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(10.dp))
                Box(Modifier.size(78.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                    contentAlignment = Alignment.Center) {
                    Icon(AppIcon.Battery.imageVector(), null, Modifier.size(42.dp), tint = MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.height(20.dp))
                Text(stringResource(Res.string.onboarding_battery_headline),
                    style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text(stringResource(Res.string.onboarding_battery_body), style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(22.dp))
                Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (optimizationIgnored) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                    )) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text(
                            if (optimizationIgnored) stringResource(Res.string.onboarding_battery_disabled)
                            else stringResource(Res.string.onboarding_battery_enabled),
                            fontWeight = FontWeight.SemiBold,
                            color = if (optimizationIgnored) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        )
                        if (!optimizationIgnored) {
                            Button(onDisableOptimization, Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(15.dp)) {
                                Text(stringResource(Res.string.onboarding_disable_battery))
                            }
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))
                Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(stringResource(Res.string.onboarding_lock_recents_title), fontWeight = FontWeight.SemiBold)
                        Text(stringResource(Res.string.onboarding_lock_recents_body), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onOpenAppInfo) { Text(stringResource(Res.string.onboarding_open_app_info)) }
                    }
                }
                TextButton(onOpenOptimizationList, Modifier.padding(top = 8.dp)) {
                    Text(stringResource(Res.string.onboarding_battery_list))
                }
            }
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onContinue, Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(16.dp)) {
                    Text(stringResource(Res.string.onboarding_continue), fontWeight = FontWeight.Bold)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    TextButton(onRemindLater) { Text(stringResource(Res.string.onboarding_not_now)) }
                    TextButton(onDontShowAgain) { Text(stringResource(Res.string.onboarding_dont_show_again)) }
                }
            }
        }
    }
}
