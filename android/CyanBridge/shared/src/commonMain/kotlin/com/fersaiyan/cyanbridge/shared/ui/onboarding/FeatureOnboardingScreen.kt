package com.fersaiyan.cyanbridge.shared.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fersaiyan.cyanbridge.shared.generated.resources.Res
import com.fersaiyan.cyanbridge.shared.generated.resources.onboarding_allow_glasses_connection
import com.fersaiyan.cyanbridge.shared.generated.resources.onboarding_allow_media_access
import com.fersaiyan.cyanbridge.shared.generated.resources.onboarding_bluetooth_allowed
import com.fersaiyan.cyanbridge.shared.generated.resources.onboarding_bluetooth_denied
import com.fersaiyan.cyanbridge.shared.generated.resources.onboarding_connect_glasses_body
import com.fersaiyan.cyanbridge.shared.generated.resources.onboarding_connect_glasses_title
import com.fersaiyan.cyanbridge.shared.generated.resources.onboarding_file_access_allowed
import com.fersaiyan.cyanbridge.shared.generated.resources.onboarding_file_access_denied
import com.fersaiyan.cyanbridge.shared.generated.resources.onboarding_media_files_body
import com.fersaiyan.cyanbridge.shared.generated.resources.onboarding_media_files_title
import com.fersaiyan.cyanbridge.shared.generated.resources.onboarding_open_github
import com.fersaiyan.cyanbridge.shared.generated.resources.onboarding_open_source_body
import com.fersaiyan.cyanbridge.shared.generated.resources.onboarding_open_source_title
import com.fersaiyan.cyanbridge.shared.generated.resources.onboarding_permission_granted
import com.fersaiyan.cyanbridge.shared.generated.resources.onboarding_setup
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3Api::class, ExperimentalResourceApi::class)
@Composable
fun FeatureOnboardingScreen(
    title: String,
    description: String,
    details: String,
    showGlassesConnectionPermission: Boolean,
    glassesConnectionPermissionGranted: Boolean,
    showStoragePermission: Boolean,
    storagePermissionGranted: Boolean,
    showOpenSourceContribution: Boolean,
    backLabel: String,
    nextLabel: String,
    onRequestGlassesConnectionPermission: () -> Unit,
    onRequestStoragePermission: () -> Unit,
    onOpenSourceRepository: () -> Unit,
    onBack: () -> Unit,
    onNext: () -> Unit,
) {
    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = { TopAppBar(title = { Text(stringResource(Res.string.onboarding_setup)) }) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(description, style = MaterialTheme.typography.bodyLarge)
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = details,
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (showGlassesConnectionPermission) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(stringResource(Res.string.onboarding_connect_glasses_title), style = MaterialTheme.typography.titleSmall)
                        Text(
                            stringResource(Res.string.onboarding_connect_glasses_body),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            if (glassesConnectionPermissionGranted) {
                                stringResource(Res.string.onboarding_bluetooth_allowed)
                            } else {
                                stringResource(Res.string.onboarding_bluetooth_denied)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (glassesConnectionPermissionGranted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedButton(
                            onClick = onRequestGlassesConnectionPermission,
                            enabled = !glassesConnectionPermissionGranted,
                        ) {
                            Text(
                                if (glassesConnectionPermissionGranted) {
                                    stringResource(Res.string.onboarding_permission_granted)
                                } else {
                                    stringResource(Res.string.onboarding_allow_glasses_connection)
                                },
                            )
                        }
                    }
                }
            }
            if (showStoragePermission) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(stringResource(Res.string.onboarding_media_files_title), style = MaterialTheme.typography.titleSmall)
                        Text(
                            stringResource(Res.string.onboarding_media_files_body),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            if (storagePermissionGranted) {
                                stringResource(Res.string.onboarding_file_access_allowed)
                            } else {
                                stringResource(Res.string.onboarding_file_access_denied)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (storagePermissionGranted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedButton(
                            onClick = onRequestStoragePermission,
                            enabled = !storagePermissionGranted,
                        ) {
                            Text(
                                if (storagePermissionGranted) {
                                    stringResource(Res.string.onboarding_permission_granted)
                                } else {
                                    stringResource(Res.string.onboarding_allow_media_access)
                                },
                            )
                        }
                    }
                }
            }
            if (showOpenSourceContribution) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(stringResource(Res.string.onboarding_open_source_title), style = MaterialTheme.typography.titleSmall)
                        Text(
                            stringResource(Res.string.onboarding_open_source_body),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedButton(onClick = onOpenSourceRepository) {
                            Text(stringResource(Res.string.onboarding_open_github))
                        }
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                OutlinedButton(onClick = onBack) { Text(backLabel) }
                Spacer(Modifier.width(8.dp))
                FilledTonalButton(onClick = onNext) { Text(nextLabel) }
            }
        }
    }
}
