package com.fersaiyan.cyanbridge.shared.ui.appearance

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.fersaiyan.cyanbridge.shared.icons.AppIcon
import com.fersaiyan.cyanbridge.shared.icons.imageVector
import com.fersaiyan.cyanbridge.shared.appearance.AccentProfile
import com.fersaiyan.cyanbridge.shared.appearance.AccentProfiles
import com.fersaiyan.cyanbridge.shared.appearance.AppearanceSettings
import com.fersaiyan.cyanbridge.shared.appearance.ThemeMode
import com.fersaiyan.cyanbridge.shared.generated.resources.Res
import com.fersaiyan.cyanbridge.shared.generated.resources.action_back
import com.fersaiyan.cyanbridge.shared.generated.resources.appearance_accessibility
import com.fersaiyan.cyanbridge.shared.generated.resources.appearance_accent_profile
import com.fersaiyan.cyanbridge.shared.generated.resources.appearance_dynamic_color
import com.fersaiyan.cyanbridge.shared.generated.resources.appearance_dynamic_color_description
import com.fersaiyan.cyanbridge.shared.generated.resources.appearance_dynamic_color_requires
import com.fersaiyan.cyanbridge.shared.generated.resources.appearance_high_contrast
import com.fersaiyan.cyanbridge.shared.generated.resources.appearance_high_contrast_description
import com.fersaiyan.cyanbridge.shared.generated.resources.appearance_live_preview
import com.fersaiyan.cyanbridge.shared.generated.resources.appearance_palette_description
import com.fersaiyan.cyanbridge.shared.generated.resources.appearance_preview_description
import com.fersaiyan.cyanbridge.shared.generated.resources.appearance_primary
import com.fersaiyan.cyanbridge.shared.generated.resources.appearance_reset
import com.fersaiyan.cyanbridge.shared.generated.resources.appearance_secondary
import com.fersaiyan.cyanbridge.shared.generated.resources.appearance_tertiary
import com.fersaiyan.cyanbridge.shared.generated.resources.appearance_theme
import com.fersaiyan.cyanbridge.shared.generated.resources.appearance_title
import com.fersaiyan.cyanbridge.shared.ui.localizedAccentProfile
import com.fersaiyan.cyanbridge.shared.ui.localizedThemeMode
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3Api::class, ExperimentalResourceApi::class)
@Composable
fun AppearanceScreen(
    settings: AppearanceSettings,
    dynamicColorAvailable: Boolean,
    onSettingsChange: (AppearanceSettings) -> Unit,
    onReset: () -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.appearance_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = AppIcon.Back.imageVector(),
                            contentDescription = stringResource(Res.string.action_back),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                SettingsSection(title = stringResource(Res.string.appearance_theme)) {
                    ThemeMode.entries.forEach { mode ->
                        SelectionRow(
                            label = localizedThemeMode(mode),
                            selected = settings.themeMode == mode,
                            onClick = { onSettingsChange(settings.copy(themeMode = mode)) },
                        )
                    }
                }
            }

            item {
                SettingsSection(title = stringResource(Res.string.appearance_accent_profile)) {
                    Text(
                        text = stringResource(Res.string.appearance_palette_description),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    AccentProfiles.all.forEach { profile ->
                        AccentRow(
                            profile = profile,
                            selected = settings.accentProfileId == profile.id,
                            enabled = !settings.useDynamicColor,
                            onClick = {
                                onSettingsChange(
                                    settings.copy(
                                        accentProfileId = profile.id,
                                        useDynamicColor = false,
                                    ),
                                )
                            },
                        )
                    }
                }
            }

            item {
                SettingsSection(title = stringResource(Res.string.appearance_accessibility)) {
                    SwitchRow(
                        title = stringResource(Res.string.appearance_high_contrast),
                        description = stringResource(Res.string.appearance_high_contrast_description),
                        checked = settings.highContrast,
                        onCheckedChange = { onSettingsChange(settings.copy(highContrast = it)) },
                    )
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    SwitchRow(
                        title = stringResource(Res.string.appearance_dynamic_color),
                        description = if (dynamicColorAvailable) {
                            stringResource(Res.string.appearance_dynamic_color_description)
                        } else {
                            stringResource(Res.string.appearance_dynamic_color_requires)
                        },
                        checked = settings.useDynamicColor && dynamicColorAvailable,
                        enabled = dynamicColorAvailable,
                        onCheckedChange = { onSettingsChange(settings.copy(useDynamicColor = it)) },
                    )
                }
            }

            item {
                PreviewCard()
            }

            item {
                OutlinedButton(
                    onClick = onReset,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                ) {
                    Text(stringResource(Res.string.appearance_reset))
                }
            }
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.extraLarge,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        ) {
            Column(
                modifier = Modifier.padding(8.dp),
                content = content,
            )
        }
    }
}

@Composable
private fun SelectionRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { this.selected = selected }
            .clip(MaterialTheme.shapes.large)
            .background(
                if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
            )
            .clickable(role = Role.RadioButton, onClick = onClick)
            .heightIn(min = 56.dp)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(
            text = label,
            modifier = Modifier.padding(start = 8.dp),
            style = MaterialTheme.typography.bodyLarge,
            color = if (selected) {
                MaterialTheme.colorScheme.onSecondaryContainer
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
    }
}

@OptIn(ExperimentalResourceApi::class)
@Composable
private fun AccentRow(
    profile: AccentProfile,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { this.selected = selected }
            .clip(MaterialTheme.shapes.large)
            .background(
                if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
            )
            .clickable(enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .heightIn(min = 56.dp)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(Color(profile.lightPrimaryArgb)),
        )
        Text(
            text = localizedAccentProfile(profile),
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp),
            color = when {
                !enabled -> MaterialTheme.colorScheme.onSurfaceVariant
                selected -> MaterialTheme.colorScheme.onSecondaryContainer
                else -> MaterialTheme.colorScheme.onSurface
            },
            style = MaterialTheme.typography.bodyLarge,
        )
        RadioButton(
            selected = selected,
            enabled = enabled,
            onClick = null,
        )
    }
}

@Composable
private fun SwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .clickable(enabled = enabled, role = Role.Switch) { onCheckedChange(!checked) }
            .heightIn(min = 64.dp)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = null,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}

@OptIn(ExperimentalResourceApi::class)
@Composable
private fun PreviewCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(Res.string.appearance_live_preview), style = MaterialTheme.typography.titleLarge)
            Text(
                stringResource(Res.string.appearance_preview_description),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Surface(
                    modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    shape = MaterialTheme.shapes.large,
                ) {
                    Text(
                        stringResource(Res.string.appearance_primary),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 14.dp),
                        textAlign = TextAlign.Center,
                    )
                }
                Surface(
                    modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    shape = MaterialTheme.shapes.large,
                ) {
                    Text(
                        stringResource(Res.string.appearance_secondary),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 14.dp),
                        textAlign = TextAlign.Center,
                    )
                }
                Surface(
                    modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    shape = MaterialTheme.shapes.large,
                ) {
                    Text(
                        stringResource(Res.string.appearance_tertiary),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 14.dp),
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}
