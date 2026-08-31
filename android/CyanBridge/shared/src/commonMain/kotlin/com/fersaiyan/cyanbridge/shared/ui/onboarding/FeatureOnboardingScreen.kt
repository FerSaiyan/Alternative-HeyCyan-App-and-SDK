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
import androidx.compose.foundation.layout.width
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
import androidx.compose.material3.RadioButton
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
import com.fersaiyan.cyanbridge.shared.icons.AppIcon
import com.fersaiyan.cyanbridge.shared.icons.imageVector
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.stringResource

data class OnboardingChoice(
    val id: String,
    val title: String,
    val description: String,
    val enabled: Boolean = true,
)

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
    choices: List<OnboardingChoice> = emptyList(),
    selectedChoiceId: String? = null,
    backLabel: String,
    nextLabel: String,
    onRequestGlassesConnectionPermission: () -> Unit,
    onRequestStoragePermission: () -> Unit,
    onOpenSourceRepository: () -> Unit,
    onChoiceSelected: (String) -> Unit = {},
    onBack: () -> Unit,
    onNext: () -> Unit,
) {
    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(Res.string.onboarding_setup),
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
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
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
                            imageVector = AppIcon.Glasses.imageVector(),
                            contentDescription = null,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                    Text(
                        text = title,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(description, style = MaterialTheme.typography.bodyLarge)
                }
            }
            OutlinedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.extraLarge,
            ) {
                Text(
                    text = details,
                    modifier = Modifier.padding(20.dp),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            choices.forEach { choice ->
                val selected = choice.id == selectedChoiceId
                Card(
                    onClick = { onChoiceSelected(choice.id) },
                    enabled = choice.enabled,
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (selected) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                    ),
                    shape = MaterialTheme.shapes.extraLarge,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 88.dp)
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = selected, onClick = null, enabled = choice.enabled)
                        Spacer(Modifier.width(8.dp))
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = when {
                                    selected -> "${choice.title} - Selected"
                                    !choice.enabled -> "${choice.title} - Not enough RAM"
                                    else -> choice.title
                                },
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = choice.description,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            if (showGlassesConnectionPermission) {
                PermissionActionCard(
                    icon = AppIcon.Glasses,
                    title = stringResource(Res.string.onboarding_connect_glasses_title),
                    body = stringResource(Res.string.onboarding_connect_glasses_body),
                    status = if (glassesConnectionPermissionGranted) {
                        stringResource(Res.string.onboarding_bluetooth_allowed)
                    } else {
                        stringResource(Res.string.onboarding_bluetooth_denied)
                    },
                    granted = glassesConnectionPermissionGranted,
                    actionLabel = if (glassesConnectionPermissionGranted) {
                        stringResource(Res.string.onboarding_permission_granted)
                    } else {
                        stringResource(Res.string.onboarding_allow_glasses_connection)
                    },
                    onClick = onRequestGlassesConnectionPermission,
                )
            }
            if (showStoragePermission) {
                PermissionActionCard(
                    icon = AppIcon.Attachment,
                    title = stringResource(Res.string.onboarding_media_files_title),
                    body = stringResource(Res.string.onboarding_media_files_body),
                    status = if (storagePermissionGranted) {
                        stringResource(Res.string.onboarding_file_access_allowed)
                    } else {
                        stringResource(Res.string.onboarding_file_access_denied)
                    },
                    granted = storagePermissionGranted,
                    actionLabel = if (storagePermissionGranted) {
                        stringResource(Res.string.onboarding_permission_granted)
                    } else {
                        stringResource(Res.string.onboarding_allow_media_access)
                    },
                    onClick = onRequestStoragePermission,
                )
            }
            if (showOpenSourceContribution) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    ),
                    shape = MaterialTheme.shapes.extraLarge,
                ) {
                    Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Surface(
                            modifier = Modifier.size(48.dp),
                            color = MaterialTheme.colorScheme.tertiary,
                            contentColor = MaterialTheme.colorScheme.onTertiary,
                            shape = MaterialTheme.shapes.large,
                        ) {
                            Icon(
                                imageVector = AppIcon.Plugins.imageVector(),
                                contentDescription = null,
                                modifier = Modifier.padding(12.dp),
                            )
                        }
                        Text(
                            stringResource(Res.string.onboarding_open_source_title),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            stringResource(Res.string.onboarding_open_source_body),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        OutlinedButton(
                            onClick = onOpenSourceRepository,
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Text(stringResource(Res.string.onboarding_open_github))
                        }
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = onBack,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 52.dp),
                ) {
                    Text(backLabel)
                }
                Button(
                    onClick = onNext,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 52.dp),
                ) {
                    Text(nextLabel)
                }
            }
        }
    }
}

@Composable
private fun PermissionActionCard(
    icon: AppIcon,
    title: String,
    body: String,
    status: String,
    granted: Boolean,
    actionLabel: String,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Surface(
                    modifier = Modifier.size(48.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    shape = MaterialTheme.shapes.large,
                ) {
                    Icon(
                        imageVector = icon.imageVector(),
                        contentDescription = null,
                        modifier = Modifier.padding(12.dp),
                    )
                }
                Text(
                    text = title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Surface(
                color = if (granted) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.errorContainer
                },
                contentColor = if (granted) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onErrorContainer
                },
                shape = MaterialTheme.shapes.large,
            ) {
                Text(
                    text = status,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            FilledTonalButton(
                onClick = onClick,
                enabled = !granted,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
            ) {
                Text(actionLabel)
            }
        }
    }
}
