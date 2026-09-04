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

data class OnboardingChoice(val id: String, val title: String, val description: String, val enabled: Boolean = true)

@OptIn(ExperimentalResourceApi::class)
@Composable
fun FeatureOnboardingScreen(
    title: String, description: String, details: String,
    showGlassesConnectionPermission: Boolean, glassesConnectionPermissionGranted: Boolean,
    showStoragePermission: Boolean, storagePermissionGranted: Boolean,
    showOpenSourceContribution: Boolean, choices: List<OnboardingChoice> = emptyList(),
    selectedChoiceId: String? = null, backLabel: String, nextLabel: String,
    stepIndex: Int = 0, stepCount: Int = 1,
    deviceCapabilitySummary: String? = null, lowMemoryWarningTitle: String? = null,
    lowMemoryWarning: String? = null,
    showBluetoothSkipWarning: Boolean = false,
    bluetoothSkipWarningTitle: String = "", bluetoothSkipWarningBody: String = "",
    bluetoothSkipConfirmLabel: String = "", bluetoothSkipCancelLabel: String = "",
    findGlassesLabel: String = "",
    onRequestGlassesConnectionPermission: () -> Unit, onRequestStoragePermission: () -> Unit,
    onFindGlasses: () -> Unit = {},
    onOpenSourceRepository: () -> Unit, onChoiceSelected: (String) -> Unit = {},
    onConfirmBluetoothSkip: () -> Unit = {}, onDismissBluetoothSkip: () -> Unit = {},
    onBack: () -> Unit, onNext: () -> Unit,
) {
    if (showBluetoothSkipWarning) {
        AlertDialog(
            onDismissRequest = onDismissBluetoothSkip,
            title = { Text(bluetoothSkipWarningTitle, fontWeight = FontWeight.Bold) },
            text = { Text(bluetoothSkipWarningBody) },
            confirmButton = { TextButton(onConfirmBluetoothSkip) { Text(bluetoothSkipConfirmLabel) } },
            dismissButton = { TextButton(onDismissBluetoothSkip) { Text(bluetoothSkipCancelLabel) } },
            shape = RoundedCornerShape(24.dp),
        )
    }
    Scaffold(contentWindowInsets = WindowInsets.safeDrawing) { insets ->
        Column(Modifier.fillMaxSize().padding(insets)) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 18.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("CYANBRIDGE", style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    Text("${stepIndex + 1} / $stepCount", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(
                    progress = { (stepIndex + 1f) / stepCount.coerceAtLeast(1) },
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
            }
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(Modifier.size(72.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                    contentAlignment = Alignment.Center) {
                    Icon(
                        (if (choices.isNotEmpty()) AppIcon.Model else AppIcon.Glasses).imageVector(), null,
                        Modifier.size(38.dp), tint = MaterialTheme.colorScheme.primary,
                    )
                }
                Spacer(Modifier.height(18.dp))
                Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text(description, style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(22.dp))
                Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Text(details, Modifier.padding(20.dp), style = MaterialTheme.typography.bodyMedium)
                }
                if (deviceCapabilitySummary != null) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        deviceCapabilitySummary,
                        Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                if (lowMemoryWarning != null) {
                    Spacer(Modifier.height(12.dp))
                    Card(
                        Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("⚠  ${lowMemoryWarningTitle.orEmpty()}", fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onTertiaryContainer)
                            Text(lowMemoryWarning, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer)
                        }
                    }
                }
                choices.forEach { choice ->
                    val selected = choice.id == selectedChoiceId
                    Spacer(Modifier.height(12.dp))
                    Card(
                        onClick = { onChoiceSelected(choice.id) }, enabled = choice.enabled,
                        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                        ),
                        border = if (selected) CardDefaults.outlinedCardBorder() else null,
                    ) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected, { onChoiceSelected(choice.id) }, enabled = choice.enabled)
                            Column(Modifier.padding(start = 8.dp)) {
                                Text(choice.title, fontWeight = FontWeight.SemiBold)
                                Text(choice.description, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                if (showGlassesConnectionPermission) {
                    Spacer(Modifier.height(16.dp))
                    PermissionCard(
                        granted = glassesConnectionPermissionGranted,
                        grantedText = stringResource(Res.string.onboarding_bluetooth_allowed),
                        pendingText = stringResource(Res.string.onboarding_bluetooth_denied),
                        buttonText = stringResource(Res.string.onboarding_allow_glasses_connection),
                        onClick = onRequestGlassesConnectionPermission,
                    )
                    if (glassesConnectionPermissionGranted) {
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = onFindGlasses,
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                            shape = RoundedCornerShape(16.dp),
                        ) {
                            Icon(AppIcon.Sync.imageVector(), null, Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(findGlassesLabel, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                if (showStoragePermission) {
                    Spacer(Modifier.height(16.dp))
                    PermissionCard(storagePermissionGranted,
                        stringResource(Res.string.onboarding_file_access_allowed),
                        stringResource(Res.string.onboarding_file_access_denied),
                        stringResource(Res.string.onboarding_allow_media_access), onRequestStoragePermission)
                }
                if (showOpenSourceContribution) {
                    Spacer(Modifier.height(16.dp))
                    OutlinedButton(onOpenSourceRepository, Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(16.dp)) {
                        Text(stringResource(Res.string.onboarding_open_github))
                    }
                    Text(
                        "github.com/FerSaiyan/Alternative-HeyCyan-App-and-SDK",
                        Modifier.padding(top = 8.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Spacer(Modifier.height(24.dp))
            }
            Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onBack, Modifier.weight(1f).height(54.dp), shape = RoundedCornerShape(16.dp)) { Text(backLabel) }
                Button(onNext, Modifier.weight(1.35f).height(54.dp), shape = RoundedCornerShape(16.dp)) {
                    Text(nextLabel, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun PermissionCard(granted: Boolean, grantedText: String, pendingText: String, buttonText: String, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = if (granted) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (granted) grantedText else pendingText, fontWeight = FontWeight.SemiBold)
            if (!granted) FilledTonalButton(onClick, Modifier.fillMaxWidth()) { Text(buttonText) }
        }
    }
}
