package com.fersaiyan.cyanbridge.shared.ui.glasses

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Card
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.fersaiyan.cyanbridge.shared.glasses.GlassesAssistantMode
import com.fersaiyan.cyanbridge.shared.glasses.AiWakeWordRoute
import com.fersaiyan.cyanbridge.shared.glasses.GlassesDashboardAction
import com.fersaiyan.cyanbridge.shared.glasses.GlassesDashboardUiState
import com.fersaiyan.cyanbridge.shared.glasses.FirmwarePatchRequestUiState
import com.fersaiyan.cyanbridge.shared.plugins.NativePluginShortcutAction
import com.fersaiyan.cyanbridge.shared.plugins.NativePluginShortcutUiState
import com.fersaiyan.cyanbridge.shared.glasses.MetaRaybanUiState
import com.fersaiyan.cyanbridge.shared.glasses.MetaPairingIssueAction
import com.fersaiyan.cyanbridge.shared.glasses.resolveMetaPairingIssue
import com.fersaiyan.cyanbridge.shared.glasses.MeizuMyvuUiState
import com.fersaiyan.cyanbridge.shared.glasses.OtaFirmwareSource
import com.fersaiyan.cyanbridge.shared.glasses.OtaSectionUiState
import com.fersaiyan.cyanbridge.shared.glasses.WifiAdbDebugUiState
import com.fersaiyan.cyanbridge.shared.generated.resources.*
import com.fersaiyan.cyanbridge.shared.ui.localizedOtaSourceDescription
import com.fersaiyan.cyanbridge.shared.ui.localizedOtaSourceLabel
import com.fersaiyan.cyanbridge.shared.ui.localizedOtaTargetLabel
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3Api::class, ExperimentalResourceApi::class)
@Composable
fun GlassesDashboardScreen(
    state: GlassesDashboardUiState,
    onAction: (GlassesDashboardAction) -> Unit,
) {
    var showWifiAdbConfirmation by remember { mutableStateOf(false) }
    var wifiAdbRiskAcknowledged by remember { mutableStateOf(false) }
    var showOtaFirmwareSourcePicker by remember { mutableStateOf(false) }
    var otaFirmwareRiskAcknowledged by remember { mutableStateOf(false) }

    if (showWifiAdbConfirmation && state.wifiAdbDebug.isAvailable) {
        AlertDialog(
            onDismissRequest = {
                showWifiAdbConfirmation = false
                wifiAdbRiskAcknowledged = false
            },
            icon = { WarningDialogIcon() },
            title = { Text(stringResource(Res.string.dashboard_privileged_adb_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(Res.string.dashboard_privileged_adb_body))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = wifiAdbRiskAcknowledged,
                            onCheckedChange = { wifiAdbRiskAcknowledged = it },
                            modifier = Modifier.testTag("wifi_adb_risk_acknowledgement"),
                        )
                        Text(stringResource(Res.string.dashboard_privileged_adb_acknowledge))
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = wifiAdbRiskAcknowledged,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("wifi_adb_confirm_start"),
                    onClick = {
                        showWifiAdbConfirmation = false
                        wifiAdbRiskAcknowledged = false
                        onAction(GlassesDashboardAction.RequestStartWifiAdbDebug)
                    },
                ) { Text(stringResource(Res.string.dashboard_start_privileged_adb)) }
            },
            dismissButton = {
                TextButton(
                    modifier = Modifier.heightIn(min = 48.dp),
                    onClick = {
                        showWifiAdbConfirmation = false
                        wifiAdbRiskAcknowledged = false
                    },
                ) { Text(stringResource(Res.string.action_cancel)) }
            },
        )
    }

    if (showOtaFirmwareSourcePicker && state.showAdvancedOta) {
        OtaFirmwareSourcePickerDialog(
            riskAcknowledged = otaFirmwareRiskAcknowledged,
            onRiskAcknowledgedChange = { otaFirmwareRiskAcknowledged = it },
            onDismissRequest = {
                showOtaFirmwareSourcePicker = false
                otaFirmwareRiskAcknowledged = false
            },
            onSourceSelected = { source ->
                showOtaFirmwareSourcePicker = false
                otaFirmwareRiskAcknowledged = false
                onAction(GlassesDashboardAction.RequestOtaFirmware(source))
            },
        )
    }

    state.firmwarePatchRequest?.takeIf { state.showAdvancedOta }?.let { request ->
        FirmwarePatchRequestDialog(
            request = request,
            onDismissRequest = {
                if (!request.isSubmitting) {
                    onAction(GlassesDashboardAction.DismissFirmwarePatchRequest)
                }
            },
            onSubmit = { contactEmail ->
                onAction(GlassesDashboardAction.SubmitFirmwarePatchRequest(contactEmail))
            },
        )
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = { TopAppBar(title = { Text(stringResource(Res.string.dashboard_title)) }) },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
                .testTag("glasses_dashboard"),
            contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.meeting.isRecording) {
                item {
                    MeetingBanner(
                        label = state.meeting.bannerLabel.ifBlank { stringResource(Res.string.dashboard_recording_active) },
                        onStop = { onAction(GlassesDashboardAction.StopMeetingCapture) },
                    )
                }
            }
            item { StatusCard(state) }
            if (state.transfer.isVisible) {
                item {
                    TransferCard(
                        state = state,
                        onStop = { onAction(GlassesDashboardAction.StopSync) },
                    )
                }
            }
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.extraLarge,
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        SectionTitle(stringResource(Res.string.dashboard_connection))
                        ActionRow(
                            primaryLabel = stringResource(Res.string.dashboard_scan),
                            onPrimary = { onAction(GlassesDashboardAction.Scan) },
                            primaryStyle = ActionButtonStyle.Primary,
                            secondaryLabel = stringResource(Res.string.dashboard_reconnect),
                            onSecondary = { onAction(GlassesDashboardAction.Reconnect) },
                        )
                        ActionButton(
                            label = stringResource(Res.string.dashboard_disconnect),
                            onClick = { onAction(GlassesDashboardAction.Disconnect) },
                            style = ActionButtonStyle.Destructive,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
            if (state.nativePluginShortcut != null) {
                item {
                    NativePluginShortcutSection(
                        shortcut = state.nativePluginShortcut,
                        onAction = onAction,
                    )
                }
            }
            if (state.showHeyCyanControls || state.showEyevueControls || state.showTuneBudsControls) {
                item { CoreGlassesControls(state, onAction) }
            }
            if (state.showMetaRaybanControls) {
                item { MetaRaybanControls(state.metaRayban, onAction) }
                item { GlassesAssistantControls(state, onAction) }
            }
            if (state.showMeizuMyvuControls) {
                item { MeizuMyvuControls(state.meizuMyvu, onAction) }
                item { GlassesAssistantControls(state, onAction) }
            }
            if (state.wifiAdbDebug.isAvailable) {
                item {
                    WifiAdbDebugSection(
                        state = state.wifiAdbDebug,
                        onRequestStart = { showWifiAdbConfirmation = true },
                        onStop = { onAction(GlassesDashboardAction.StopWifiAdbDebug) },
                    )
                }
            }
            if (state.showAdvancedControls) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.extraLarge,
                        colors = CardDefaults.cardColors(
                            containerColor = if (state.advancedExpanded) {
                                MaterialTheme.colorScheme.surfaceVariant
                            } else {
                                MaterialTheme.colorScheme.surface
                            },
                        ),
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                            TextButton(
                                onClick = { onAction(GlassesDashboardAction.ToggleAdvanced) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 56.dp)
                                    .testTag("advanced_controls_toggle"),
                            ) {
                                Text(
                                    if (state.advancedExpanded) {
                                        stringResource(Res.string.dashboard_hide_advanced)
                                    } else {
                                        stringResource(Res.string.dashboard_show_advanced)
                                    },
                                    modifier = Modifier.weight(1f),
                                )
                                Spacer(Modifier.width(8.dp))
                                Surface(
                                    modifier = Modifier.size(40.dp),
                                    color = if (state.advancedExpanded) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.secondaryContainer
                                    },
                                    contentColor = if (state.advancedExpanded) {
                                        MaterialTheme.colorScheme.onPrimary
                                    } else {
                                        MaterialTheme.colorScheme.onSecondaryContainer
                                    },
                                    shape = MaterialTheme.shapes.large,
                                ) {
                                    Icon(
                                        imageVector = if (state.advancedExpanded) {
                                            Icons.Outlined.ExpandLess
                                        } else {
                                            Icons.Outlined.ExpandMore
                                        },
                                        contentDescription = null,
                                        modifier = Modifier.padding(8.dp),
                                    )
                                }
                            }
                            AnimatedVisibility(
                                visible = state.advancedExpanded,
                                enter = fadeIn(animationSpec = spring()) + expandVertically(
                                    animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                                ),
                                exit = fadeOut(animationSpec = spring()) + shrinkVertically(
                                    animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                                ),
                            ) {
                                Column {
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                    Column(modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)) {
                                        AdvancedControls(
                                            state = state,
                                            onAction = onAction,
                                            onRequestOtaFirmware = { showOtaFirmwareSourcePicker = true },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WifiAdbDebugSection(
    state: WifiAdbDebugUiState,
    onRequestStart: () -> Unit,
    onStop: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("wifi_adb_debug_section"),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SectionTitle(stringResource(Res.string.dashboard_developer_tools), accented = true)
            Text(stringResource(Res.string.dashboard_adb_wifi), style = MaterialTheme.typography.titleMedium)
            StatusPill(stringResource(Res.string.dashboard_status, state.stateLabel))
            if (state.detail.isNotBlank()) {
                Text(
                    state.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            state.glassesIp?.let { Text(stringResource(Res.string.dashboard_glasses_ip, it), style = MaterialTheme.typography.bodySmall) }
            if (state.relayEndpoints.isNotEmpty()) {
                Text(
                    stringResource(Res.string.dashboard_relay_endpoints, state.relayEndpoints.joinToString()),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (state.preferredCommand.isNotBlank()) {
                Text(state.preferredCommand, style = MaterialTheme.typography.bodySmall)
            }
            Text(
                stringResource(Res.string.dashboard_usb_warning),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            ActionRow(
                primaryLabel = stringResource(Res.string.dashboard_start_adb_relay),
                onPrimary = onRequestStart,
                primaryEnabled = state.canStart,
                primaryStyle = ActionButtonStyle.Primary,
                secondaryLabel = stringResource(Res.string.dashboard_stop),
                onSecondary = onStop,
                secondaryEnabled = state.canStop,
                secondaryStyle = ActionButtonStyle.Destructive,
            )
        }
    }
}

@Composable
private fun NativePluginShortcutSection(
    shortcut: NativePluginShortcutUiState,
    onAction: (GlassesDashboardAction) -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("native_plugin_shortcut_${shortcut.id}"),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(Res.string.dashboard_shortcuts, shortcut.title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = shortcut.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Surface(
                    color = if (shortcut.isEnabled) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                    contentColor = if (shortcut.isEnabled) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    shape = MaterialTheme.shapes.large,
                ) {
                    Text(
                        text = stringResource(
                            if (shortcut.isEnabled) Res.string.dashboard_enabled else Res.string.dashboard_stopped,
                        ),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            shortcut.buttons.chunked(2).forEach { rowButtons ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    rowButtons.forEach { button ->
                        val buttonModifier = Modifier.weight(1f)
                        ActionButton(
                            label = button.label,
                            onClick = {
                                onAction(GlassesDashboardAction.RunNativePluginShortcut(button.action))
                            },
                            style = when (button.action) {
                                NativePluginShortcutAction.START -> ActionButtonStyle.Primary
                                NativePluginShortcutAction.STOP -> ActionButtonStyle.Destructive
                                else -> ActionButtonStyle.Neutral
                            },
                            modifier = buttonModifier,
                        )
                    }
                    if (rowButtons.size == 1) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun MeetingBanner(label: String, onStop: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Row(
            modifier = Modifier.padding(start = 20.dp, top = 12.dp, end = 8.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            TextButton(
                onClick = onStop,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(stringResource(Res.string.dashboard_stop))
            }
        }
    }
}

@Composable
private fun StatusCard(state: GlassesDashboardUiState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(Res.string.dashboard_glasses_status),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                fontWeight = FontWeight.Bold,
            )
            BoxWithConstraints {
                val hasMetrics = state.showBattery || state.showStorage
                if (hasMetrics && maxWidth < 300.dp) {
                    Column {
                        StatusIdentity(state)
                        Spacer(Modifier.height(8.dp))
                        StatusMetrics(state, Alignment.Start)
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusIdentity(state, Modifier.weight(1f))
                        if (hasMetrics) StatusMetrics(state, Alignment.End)
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusIdentity(
    state: GlassesDashboardUiState,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.testTag("dashboard_status_identity"),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            state.connectionLabel,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Surface(
            color = MaterialTheme.colorScheme.secondary,
            contentColor = MaterialTheme.colorScheme.onSecondary,
            shape = MaterialTheme.shapes.large,
        ) {
            Text(
                text = stringResource(Res.string.dashboard_class, state.deviceClassLabel),
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

@Composable
private fun StatusMetrics(
    state: GlassesDashboardUiState,
    horizontalAlignment: Alignment.Horizontal,
) {
    Surface(
        modifier = Modifier.testTag("dashboard_status_metrics"),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = MaterialTheme.shapes.large,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalAlignment = horizontalAlignment,
        ) {
            if (state.showBattery) {
                Text(
                    text = state.batteryPercent?.let { stringResource(Res.string.dashboard_battery, it) }
                        ?: stringResource(Res.string.dashboard_battery_unknown),
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            if (state.showStorage) {
                Text(
                    text = stringResource(Res.string.dashboard_storage, state.storageLabel),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun TransferCard(
    state: GlassesDashboardUiState,
    onStop: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                stringResource(Res.string.dashboard_sync_progress),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            StatusPill(stringResource(Res.string.dashboard_flow, state.transfer.flowLabel), tertiary = true)
            Text(state.transfer.countsLabel, style = MaterialTheme.typography.bodySmall)
            state.transfer.progress?.let { progress ->
                LinearProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(percent = 50)),
                )
            } ?: LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(percent = 50)),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = state.transfer.detail,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                TextButton(
                    onClick = onStop,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(
                        stringResource(Res.string.dashboard_stop_sync),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun CoreGlassesControls(
    state: GlassesDashboardUiState,
    onAction: (GlassesDashboardAction) -> Unit,
) {
    Column(
        modifier = Modifier.testTag("glasses_core_controls"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        GlassesAssistantControls(state, onAction)
        Spacer(Modifier.height(8.dp))
        if (state.showCaptureSettings) {
            WearingDetectionControl(state, onAction)
            Spacer(Modifier.height(8.dp))
        }
        SectionTitle(stringResource(Res.string.dashboard_media_controls))
        ActionRow(
            primaryLabel = stringResource(Res.string.dashboard_photo),
            onPrimary = { onAction(GlassesDashboardAction.CapturePhoto) },
            secondaryLabel = if (state.showTuneBudsControls && state.isVideoRecording) {
                stringResource(Res.string.dashboard_stop)
            } else {
                stringResource(Res.string.dashboard_video)
            },
            onSecondary = { onAction(GlassesDashboardAction.ToggleVideo) },
        )
        ActionRow(
            primaryLabel = if (state.showTuneBudsControls && state.isAudioRecording) {
                stringResource(Res.string.dashboard_stop)
            } else {
                stringResource(Res.string.dashboard_audio)
            },
            onPrimary = { onAction(GlassesDashboardAction.StartAudioRecording) },
            secondaryLabel = stringResource(Res.string.dashboard_count),
            onSecondary = { onAction(GlassesDashboardAction.RequestMediaCount) },
        )
        if (state.showMediaSync) {
            ActionButton(
                label = stringResource(Res.string.dashboard_sync_wifi),
                onClick = { onAction(GlassesDashboardAction.StartSync) },
                style = ActionButtonStyle.Primary,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (state.livePreview.isAvailable) {
            Spacer(Modifier.height(8.dp))
            SectionTitle(
                if (state.showEyevueControls) {
                    stringResource(Res.string.dashboard_eye_vue_live_preview)
                } else {
                    stringResource(Res.string.dashboard_passive_rtsp_probe)
                },
            )
            Text(
                text = if (state.showEyevueControls) {
                    stringResource(Res.string.dashboard_eye_vue_description)
                } else {
                    stringResource(Res.string.dashboard_passive_probe_description)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.livePreview.stateLabel != "Idle") {
                Text(
                    text = stringResource(Res.string.dashboard_status, state.livePreview.stateLabel),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (state.livePreview.detail.isNotBlank()) {
                    Text(
                        text = state.livePreview.detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            ActionRow(
                primaryLabel = if (state.livePreview.isScanning) {
                    stringResource(Res.string.dashboard_connecting)
                } else if (state.showEyevueControls) {
                    stringResource(Res.string.dashboard_start_live_preview)
                } else {
                    stringResource(Res.string.dashboard_arm_passive_probe)
                },
                onPrimary = { onAction(GlassesDashboardAction.StartLivePreview) },
                primaryEnabled = state.livePreview.canStart && !state.livePreview.isScanning,
                primaryStyle = ActionButtonStyle.Primary,
                secondaryLabel = stringResource(Res.string.dashboard_stop),
                onSecondary = { onAction(GlassesDashboardAction.StopLivePreview) },
                secondaryEnabled = state.livePreview.canStop,
                secondaryStyle = ActionButtonStyle.Destructive,
            )
        }
    }
}

@Composable
private fun WearingDetectionControl(
    state: GlassesDashboardUiState,
    onAction: (GlassesDashboardAction) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("wearing_detection_control"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(Res.string.dashboard_wearing_detection), style = MaterialTheme.typography.titleSmall)
            Text(
                text = when (state.wearingDetectionEnabled) {
                    true -> stringResource(Res.string.dashboard_enabled_on_glasses)
                    false -> stringResource(Res.string.dashboard_disabled_on_glasses)
                    null -> stringResource(Res.string.dashboard_load_settings_state)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = state.wearingDetectionEnabled == true,
            enabled = state.wearingDetectionEnabled != null,
            onCheckedChange = { enabled ->
                onAction(GlassesDashboardAction.SetWearingDetection(enabled))
            },
            modifier = Modifier.testTag("wearing_detection_switch"),
        )
    }
}

@Composable
private fun GlassesAssistantControls(
    state: GlassesDashboardUiState,
    onAction: (GlassesDashboardAction) -> Unit,
) {
    Column(
        modifier = Modifier.testTag("glasses_assistant_controls"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SectionTitle(stringResource(Res.string.dashboard_ai_assistant), accented = true)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AssistantModeChip(
                label = stringResource(Res.string.dashboard_phone_assistant),
                mode = GlassesAssistantMode.PHONE_ASSISTANT,
                selectedMode = state.assistantMode,
                onAction = onAction,
                modifier = Modifier.weight(1f),
            )
            AssistantModeChip(
                label = stringResource(Res.string.dashboard_custom_provider),
                mode = GlassesAssistantMode.CUSTOM_AI_PROVIDER,
                selectedMode = state.assistantMode,
                onAction = onAction,
                modifier = Modifier.weight(1f),
            )
        }
        ActionRow(
            primaryLabel = stringResource(Res.string.dashboard_test_voice),
            onPrimary = { onAction(GlassesDashboardAction.TestVoiceQuestion) },
            secondaryLabel = stringResource(
                if (state.imageQueryEnabled) {
                    Res.string.dashboard_test_image
                } else {
                    Res.string.dashboard_image_query_unavailable
                },
            ),
            onSecondary = { onAction(GlassesDashboardAction.TestImageQuestion) },
            secondaryEnabled = state.imageQueryEnabled,
        )
        if (state.showAiWakeWordRouting) {
            AiWakeWordRouteControls(state, onAction)
        }
        OutlinedButton(
            onClick = { onAction(GlassesDashboardAction.OpenExternalImageAutomationDiagnostics) },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
        ) {
            Text(stringResource(Res.string.dashboard_gemini_chatgpt_setup))
        }
    }
}

@Composable
private fun AiWakeWordRouteControls(
    state: GlassesDashboardUiState,
    onAction: (GlassesDashboardAction) -> Unit,
) {
    var showHeyCyanImageWarning by remember { mutableStateOf(false) }

    if (showHeyCyanImageWarning) {
        AlertDialog(
            onDismissRequest = { showHeyCyanImageWarning = false },
            modifier = Modifier.testTag("ai_wake_word_image_warning"),
            icon = { WarningDialogIcon() },
            title = {
                Text(stringResource(Res.string.dashboard_ai_wake_word_image_warning_title))
            },
            text = {
                Text(stringResource(Res.string.dashboard_ai_wake_word_image_warning_body))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showHeyCyanImageWarning = false
                        onAction(
                            GlassesDashboardAction.SetAiWakeWordRoute(
                                AiWakeWordRoute.IMAGE_QUESTION,
                            ),
                        )
                    },
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("ai_wake_word_image_warning_confirm"),
                ) {
                    Text(stringResource(Res.string.dashboard_ai_wake_word_image_warning_confirm))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showHeyCyanImageWarning = false },
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("ai_wake_word_image_warning_cancel"),
                ) {
                    Text(stringResource(Res.string.action_cancel))
                }
            },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("ai_wake_word_route_controls"),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = stringResource(Res.string.dashboard_ai_wake_word_route),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = stringResource(Res.string.dashboard_ai_wake_word_route_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = state.aiWakeWordRoute == AiWakeWordRoute.VOICE_QUESTION,
                onClick = {
                    onAction(GlassesDashboardAction.SetAiWakeWordRoute(AiWakeWordRoute.VOICE_QUESTION))
                },
                label = { Text(stringResource(Res.string.dashboard_ai_wake_word_voice)) },
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .testTag("ai_wake_word_route_voice_question"),
            )
            FilterChip(
                selected = state.aiWakeWordRoute == AiWakeWordRoute.IMAGE_QUESTION,
                onClick = {
                    if (
                        state.showHeyCyanControls &&
                        state.aiWakeWordRoute != AiWakeWordRoute.IMAGE_QUESTION
                    ) {
                        showHeyCyanImageWarning = true
                    } else {
                        onAction(
                            GlassesDashboardAction.SetAiWakeWordRoute(
                                AiWakeWordRoute.IMAGE_QUESTION,
                            ),
                        )
                    }
                },
                label = { Text(stringResource(Res.string.dashboard_ai_wake_word_image)) },
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .testTag("ai_wake_word_route_image_question"),
            )
        }
    }
}

@Composable
private fun AssistantModeChip(
    label: String,
    mode: GlassesAssistantMode,
    selectedMode: GlassesAssistantMode,
    onAction: (GlassesDashboardAction) -> Unit,
    modifier: Modifier,
) {
    FilterChip(
        selected = selectedMode == mode,
        onClick = { onAction(GlassesDashboardAction.SelectAssistantMode(mode)) },
        label = { Text(label) },
        modifier = modifier.heightIn(min = 48.dp),
    )
}

@Composable
private fun MetaRaybanControls(
    state: MetaRaybanUiState,
    onAction: (GlassesDashboardAction) -> Unit,
) {
    val pairingIssue = resolveMetaPairingIssue(
        metaAiInstalled = state.metaAiInstalled,
        lastError = state.lastError,
        setupGuidance = state.setupGuidance,
    )
    var showPairingIssue by remember(state.lastError) { mutableStateOf(pairingIssue != null) }
    if (pairingIssue != null && showPairingIssue) {
        AlertDialog(
            onDismissRequest = { showPairingIssue = false },
            icon = { WarningDialogIcon() },
            title = { Text(pairingIssue.title) },
            text = { Text(pairingIssue.message) },
            confirmButton = {
                TextButton(
                    modifier = Modifier.heightIn(min = 48.dp),
                    onClick = {
                        showPairingIssue = false
                        onAction(
                            when (pairingIssue.action) {
                                MetaPairingIssueAction.INSTALL_META_AI -> GlassesDashboardAction.MetaOpenMetaAi
                                MetaPairingIssueAction.OPEN_PAIRING -> GlassesDashboardAction.MetaOpenPairing
                                MetaPairingIssueAction.REQUEST_ACCESS -> GlassesDashboardAction.MetaOpenPairing
                            },
                        )
                    },
                ) {
                    Text(pairingIssue.primaryLabel)
                }
            },
            dismissButton = {
                TextButton(
                    modifier = Modifier.heightIn(min = 48.dp),
                    onClick = {
                        showPairingIssue = false
                        onAction(GlassesDashboardAction.MetaSendDiagnostics)
                    },
                ) {
                    Text("Details")
                }
            },
        )
    }

    Column(
        modifier = Modifier.testTag("meta_rayban_controls"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SectionTitle(stringResource(Res.string.meta_rayban_title), accented = true)
        Text(
            text = stringResource(
                Res.string.meta_rayban_device_summary,
                state.selectedDeviceName ?: stringResource(Res.string.meta_rayban_no_device),
                state.availableDeviceCount,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("meta_rayban_device_summary"),
        )
        state.setupGuidance?.takeIf { it.isNotBlank() }?.let { guidance ->
            Text(
                text = guidance,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.testTag("meta_rayban_setup_guidance"),
            )
        }
        // All pairing flows live in DeviceBindScreen (Scan → Pair Meta Glasses) → MetaPairingActivity.
        // Keep dashboard read-only: errors surface via the AlertDialog above (with Details → Send diagnostics).
        // Camera is consumed via Test image AI / Test voice in GlassesAssistantControls (capturePhotoOnce),
        // not via manual session/stream controls (stream is only for future Gemini Live, not yet implemented).
        Text(
            text = stringResource(Res.string.meta_rayban_registration_status, state.registrationLabel),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.testTag("meta_rayban_registration_status"),
        )
        Text(
            text = stringResource(Res.string.dashboard_meta_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MeizuMyvuControls(
    state: MeizuMyvuUiState,
    onAction: (GlassesDashboardAction) -> Unit,
) {
    Column(
        modifier = Modifier.testTag("meizu_myvu_controls"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SectionTitle(stringResource(Res.string.dashboard_meizu_title), accented = true)
        Text(
            text = state.connectionLabel,
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(Res.string.dashboard_protocol, state.protocolState) +
                state.deviceName?.let { "  ${stringResource(Res.string.dashboard_device, it)}" }.orEmpty(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        state.batteryPercent?.let { battery ->
            Text(stringResource(Res.string.dashboard_battery, battery), style = MaterialTheme.typography.bodySmall)
        }
        state.lastError?.takeIf { it.isNotBlank() }?.let { error ->
            Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        Text(
            stringResource(Res.string.dashboard_meizu_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ActionRow(
            primaryLabel = stringResource(Res.string.dashboard_connect),
            onPrimary = { onAction(GlassesDashboardAction.MeizuConnect) },
            primaryEnabled = state.canConnect,
            primaryStyle = ActionButtonStyle.Primary,
            secondaryLabel = stringResource(Res.string.dashboard_disconnect),
            onSecondary = { onAction(GlassesDashboardAction.MeizuDisconnect) },
            secondaryEnabled = state.canDisconnect,
            secondaryStyle = ActionButtonStyle.Destructive,
        )
        ActionRow(
            primaryLabel = stringResource(Res.string.dashboard_test_notification),
            onPrimary = { onAction(GlassesDashboardAction.MeizuSendTestNotification) },
            primaryEnabled = state.canSend,
            secondaryLabel = stringResource(Res.string.dashboard_show_text),
            onSecondary = { onAction(GlassesDashboardAction.MeizuShowTestTeleprompter) },
            secondaryEnabled = state.canSend,
        )
        ActionRow(
            primaryLabel = stringResource(Res.string.dashboard_sync_clock),
            onPrimary = { onAction(GlassesDashboardAction.MeizuSyncClock) },
            primaryEnabled = state.canSend,
            secondaryLabel = stringResource(Res.string.dashboard_comfort_brightness),
            onSecondary = { onAction(GlassesDashboardAction.MeizuSetComfortBrightness) },
            secondaryEnabled = state.canSend,
        )
        Text(
            stringResource(Res.string.dashboard_meizu_unsupported),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MetaControlRow(
    status: String,
    startLabel: String,
    onStart: () -> Unit,
    startEnabled: Boolean,
    stopLabel: String,
    onStop: () -> Unit,
    stopEnabled: Boolean,
) {
    Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    ActionRow(
        primaryLabel = startLabel,
        onPrimary = onStart,
        primaryEnabled = startEnabled,
        primaryStyle = ActionButtonStyle.Primary,
        secondaryLabel = stopLabel,
        onSecondary = onStop,
        secondaryEnabled = stopEnabled,
        secondaryStyle = ActionButtonStyle.Destructive,
    )
}

@Composable
private fun AdvancedControls(
    state: GlassesDashboardUiState,
    onAction: (GlassesDashboardAction) -> Unit,
    onRequestOtaFirmware: () -> Unit,
) {
    var pendingDetailedQuality by remember { mutableStateOf<Int?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.showAdvancedDeviceInfo) {
            Column(modifier = Modifier.testTag("advanced_device_info")) {
                SectionTitle(stringResource(Res.string.dashboard_device_info))
                state.deviceInfoLabel?.let { info ->
                    Text(
                        text = info,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                ActionRow(
                    primaryLabel = stringResource(Res.string.dashboard_battery_action),
                    onPrimary = { onAction(GlassesDashboardAction.RequestBattery) },
                    secondaryLabel = stringResource(Res.string.dashboard_version),
                    onSecondary = { onAction(GlassesDashboardAction.RequestVersion) },
                )
                if (state.showAdvancedDeviceVolume) {
                    ActionRow(
                        primaryLabel = stringResource(Res.string.dashboard_sync_time),
                        onPrimary = { onAction(GlassesDashboardAction.SyncTime) },
                        secondaryLabel = stringResource(Res.string.dashboard_volume),
                        onSecondary = { onAction(GlassesDashboardAction.RequestVolume) },
                    )
                } else {
                    ActionButton(
                        label = stringResource(Res.string.dashboard_sync_time),
                        onClick = { onAction(GlassesDashboardAction.SyncTime) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        if (state.showAdvancedImageQuality) {
            HorizontalDivider()
            Column(modifier = Modifier.testTag("advanced_image_quality")) {
                SectionTitle(stringResource(Res.string.dashboard_image_quality))
                val thumbnailQualityLabel = stringResource(
                    when (state.imageThumbnailQualitySdkValue) {
                        0 -> Res.string.dashboard_quality_instant
                        1 -> Res.string.dashboard_quality_quick
                        2 -> Res.string.dashboard_quality_smooth
                        3 -> Res.string.dashboard_quality_fine
                        4 -> Res.string.dashboard_quality_clearer
                        else -> Res.string.dashboard_quality_detailed
                    },
                )
                Text(
                    text = stringResource(Res.string.dashboard_thumbnail_quality, thumbnailQualityLabel),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                listOf(
                    stringResource(Res.string.dashboard_quality_instant),
                    stringResource(Res.string.dashboard_quality_quick),
                    stringResource(Res.string.dashboard_quality_smooth),
                    stringResource(Res.string.dashboard_quality_fine),
                    stringResource(Res.string.dashboard_quality_clearer),
                    stringResource(Res.string.dashboard_quality_detailed),
                )
                    .chunked(3)
                    .forEachIndexed { rowIndex, labels ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            labels.forEachIndexed { columnIndex, label ->
                                val sdkValue = rowIndex * 3 + columnIndex
                                FilterChip(
                                    selected = state.imageThumbnailQualitySdkValue == sdkValue,
                                    onClick = {
                                        if (sdkValue == 5) {
                                            pendingDetailedQuality = sdkValue
                                        } else {
                                            onAction(GlassesDashboardAction.SelectImageThumbnailQuality(sdkValue))
                                        }
                                    },
                                    label = { Text(label) },
                                    modifier = Modifier
                                        .weight(1f)
                                        .heightIn(min = 48.dp)
                                        .testTag("ai_image_thumbnail_quality_$sdkValue"),
                                )
                            }
                        }
                    }
            }
        }
        pendingDetailedQuality?.let { sdkValue ->
            AlertDialog(
                onDismissRequest = { pendingDetailedQuality = null },
                icon = { WarningDialogIcon() },
                title = { Text(stringResource(Res.string.dashboard_quality_detailed_warning_title)) },
                text = {
                    Text(
                        stringResource(
                            if (state.showEyevueControls) {
                                Res.string.dashboard_quality_detailed_warning_body_eyevue
                            } else {
                                Res.string.dashboard_quality_detailed_warning_body
                            },
                        ),
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val value = pendingDetailedQuality
                            pendingDetailedQuality = null
                            if (value != null) onAction(GlassesDashboardAction.SelectImageThumbnailQuality(value))
                        },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text(stringResource(Res.string.dashboard_quality_detailed_warning_continue)) }
                },
                dismissButton = {
                    TextButton(
                        onClick = { pendingDetailedQuality = null },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text(stringResource(Res.string.dashboard_quality_detailed_warning_cancel))
                    }
                },
            )
        }
        if (state.showAdvancedDeveloperTools) {
            HorizontalDivider()
            Column(modifier = Modifier.testTag("advanced_developer_tools")) {
                SectionTitle(stringResource(Res.string.dashboard_developer_tools))
                TextButton(
                    onClick = { onAction(GlassesDashboardAction.AddDeviceListener) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                ) { Text(stringResource(Res.string.dashboard_register_listener)) }
                TextButton(
                    onClick = { onAction(GlassesDashboardAction.StartClassicBluetoothScan) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                ) { Text(stringResource(Res.string.dashboard_classic_scan)) }
                TextButton(
                    onClick = { onAction(GlassesDashboardAction.DumpOtaInfo) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                ) { Text(stringResource(Res.string.dashboard_dump_ota)) }
                TextButton(
                    onClick = { onAction(GlassesDashboardAction.TestPullOta) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                ) { Text(stringResource(Res.string.dashboard_test_pull_ota)) }
            }
        }
        if (state.showAdvancedOta) {
            HorizontalDivider()
            Column(modifier = Modifier.testTag("advanced_ota")) {
                SectionTitle(stringResource(Res.string.dashboard_ota_update))
                Text(
                    text = stringResource(Res.string.dashboard_ota_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OtaProgressSection(state.ota)
                ActionRow(
                    primaryLabel = stringResource(Res.string.dashboard_choose_ota_files),
                    onPrimary = onRequestOtaFirmware,
                    primaryStyle = ActionButtonStyle.Primary,
                    secondaryLabel = stringResource(Res.string.action_cancel),
                    onSecondary = { onAction(GlassesDashboardAction.CancelOta) },
                    secondaryStyle = ActionButtonStyle.Destructive,
                    primaryEnabled = state.ota.canStart,
                    secondaryEnabled = state.ota.canCancel,
                )
            }
        }
    }
}

@Composable
private fun OtaFirmwareSourcePickerDialog(
    riskAcknowledged: Boolean,
    onRiskAcknowledgedChange: (Boolean) -> Unit,
    onDismissRequest: () -> Unit,
    onSourceSelected: (OtaFirmwareSource) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        modifier = Modifier.testTag("ota_firmware_source_picker"),
        icon = { WarningDialogIcon() },
        title = { Text(stringResource(Res.string.dashboard_ota_choose_source)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(Res.string.dashboard_ota_source_body_one))
                Text(stringResource(Res.string.dashboard_ota_source_body_two))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = riskAcknowledged,
                        onCheckedChange = onRiskAcknowledgedChange,
                        modifier = Modifier.testTag("ota_firmware_risk_acknowledgement"),
                    )
                    Text(stringResource(Res.string.dashboard_ota_acknowledge))
                }
                OtaFirmwareSource.entries.forEach { source ->
                    OutlinedButton(
                        enabled = riskAcknowledged,
                        onClick = { onSourceSelected(source) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 64.dp)
                            .testTag("ota_firmware_source_${source.name.lowercase()}"),
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(localizedOtaSourceLabel(source), style = MaterialTheme.typography.labelLarge)
                            Text(
                                localizedOtaSourceDescription(source),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(
                onClick = onDismissRequest,
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text(stringResource(Res.string.action_cancel)) }
        },
    )
}

@Composable
private fun FirmwarePatchRequestDialog(
    request: FirmwarePatchRequestUiState,
    onDismissRequest: () -> Unit,
    onSubmit: (String) -> Unit,
) {
    var contactEmail by remember(
        request.source,
        request.target,
        request.targetHardwareVersion,
        request.targetFirmwareVersion,
        request.suggestedContactEmail,
    ) { mutableStateOf(request.suggestedContactEmail) }
    val validEmail = isValidContactEmail(contactEmail)

    AlertDialog(
        onDismissRequest = onDismissRequest,
        modifier = Modifier.testTag("firmware_patch_request_dialog"),
        icon = { WarningDialogIcon() },
        title = { Text(stringResource(Res.string.dashboard_request_patch)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(Res.string.dashboard_patch_body_one))
                Text(stringResource(Res.string.dashboard_patch_body_two))
                Text(
                    stringResource(
                        Res.string.dashboard_requested,
                        localizedOtaTargetLabel(request.target),
                        request.targetHardwareVersion,
                        request.targetFirmwareVersion,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = contactEmail,
                    onValueChange = { contactEmail = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("firmware_patch_request_email"),
                    label = { Text(stringResource(Res.string.dashboard_contact_email)) },
                    singleLine = true,
                    enabled = !request.isSubmitting,
                    isError = contactEmail.isNotBlank() && !validEmail,
                    supportingText = {
                        Text(
                            if (contactEmail.isNotBlank() && !validEmail) {
                                stringResource(Res.string.dashboard_valid_email)
                            } else {
                                stringResource(Res.string.dashboard_email_supporting)
                            },
                        )
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                )
                request.submissionError?.let { error ->
                    Text(
                        error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = validEmail && !request.isSubmitting,
                onClick = { onSubmit(contactEmail.trim()) },
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("firmware_patch_request_send"),
            ) {
                Text(
                    if (request.isSubmitting) {
                        stringResource(Res.string.dashboard_sending)
                    } else {
                        stringResource(Res.string.dashboard_send_request)
                    },
                )
            }
        },
        dismissButton = {
            TextButton(
                enabled = !request.isSubmitting,
                onClick = onDismissRequest,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("firmware_patch_request_cancel"),
            ) { Text(stringResource(Res.string.action_cancel)) }
        },
    )
}

private fun isValidContactEmail(value: String): Boolean =
    value.trim().matches(Regex("[^\\s@]+@[^\\s@]+\\.[^\\s@]+"))

private enum class ActionButtonStyle {
    Neutral,
    Primary,
    Destructive,
}

@Composable
private fun ActionButton(
    label: String,
    onClick: () -> Unit,
    style: ActionButtonStyle = ActionButtonStyle.Neutral,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    when (style) {
        ActionButtonStyle.Primary -> Button(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier.heightIn(min = 48.dp),
        ) {
            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }

        ActionButtonStyle.Neutral -> OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier.heightIn(min = 48.dp),
        ) {
            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }

        ActionButtonStyle.Destructive -> OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier.heightIn(min = 48.dp),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.error,
                disabledContentColor = MaterialTheme.colorScheme.error.copy(alpha = 0.38f),
            ),
            border = BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.error.copy(alpha = if (enabled) 1f else 0.38f),
            ),
        ) {
            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun ActionRow(
    primaryLabel: String,
    onPrimary: () -> Unit,
    secondaryLabel: String,
    onSecondary: () -> Unit,
    primaryEnabled: Boolean = true,
    secondaryEnabled: Boolean = true,
    primaryStyle: ActionButtonStyle = ActionButtonStyle.Neutral,
    secondaryStyle: ActionButtonStyle = ActionButtonStyle.Neutral,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ActionButton(
            label = primaryLabel,
            onClick = onPrimary,
            enabled = primaryEnabled,
            modifier = Modifier.weight(1f),
            style = primaryStyle,
        )
        ActionButton(
            label = secondaryLabel,
            onClick = onSecondary,
            enabled = secondaryEnabled,
            modifier = Modifier.weight(1f),
            style = secondaryStyle,
        )
    }
}

@Composable
private fun SectionTitle(text: String, accented: Boolean = false) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        color = if (accented) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun StatusPill(text: String, tertiary: Boolean = false) {
    Surface(
        color = if (tertiary) {
            MaterialTheme.colorScheme.tertiary
        } else {
            MaterialTheme.colorScheme.primaryContainer
        },
        contentColor = if (tertiary) {
            MaterialTheme.colorScheme.onTertiary
        } else {
            MaterialTheme.colorScheme.onPrimaryContainer
        },
        shape = MaterialTheme.shapes.large,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun WarningDialogIcon() {
    Surface(
        modifier = Modifier.size(48.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.large,
    ) {
        Icon(
            imageVector = Icons.Outlined.WarningAmber,
            contentDescription = null,
            modifier = Modifier.padding(12.dp),
        )
    }
}

@OptIn(ExperimentalResourceApi::class)
@Composable
private fun OtaProgressSection(ota: OtaSectionUiState) {
    if (ota.stateLabel == "Idle") return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(Res.string.dashboard_status, ota.stateLabel),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            if (ota.stateLabel == "Complete") {
                Text(
                    text = stringResource(Res.string.dashboard_ota_done),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        ota.progress?.let { progress ->
            LinearProgressIndicator(
                progress = { progress.coerceIn(0, 100) / 100f },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(percent = 50)),
            )
        } ?: LinearProgressIndicator(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(percent = 50)),
        )
        if (ota.detail.isNotBlank()) {
            Text(
                text = ota.detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
