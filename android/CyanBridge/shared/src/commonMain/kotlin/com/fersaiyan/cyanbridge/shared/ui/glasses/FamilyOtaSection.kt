package com.fersaiyan.cyanbridge.shared.ui.glasses

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BatteryAlert
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.fersaiyan.cyanbridge.shared.customization.CustomizationFamily
import com.fersaiyan.cyanbridge.shared.devices.firmware.FamilyOtaBlocker
import com.fersaiyan.cyanbridge.shared.generated.resources.Res
import com.fersaiyan.cyanbridge.shared.generated.resources.action_cancel
import com.fersaiyan.cyanbridge.shared.generated.resources.family_ota_acknowledge
import com.fersaiyan.cyanbridge.shared.generated.resources.family_ota_blocked_audio
import com.fersaiyan.cyanbridge.shared.generated.resources.family_ota_blocked_battery
import com.fersaiyan.cyanbridge.shared.generated.resources.family_ota_blocked_busy
import com.fersaiyan.cyanbridge.shared.generated.resources.family_ota_blocked_not_connected
import com.fersaiyan.cyanbridge.shared.generated.resources.family_ota_check
import com.fersaiyan.cyanbridge.shared.generated.resources.family_ota_confirm_file_eyevue
import com.fersaiyan.cyanbridge.shared.generated.resources.family_ota_confirm_file_tunebuds
import com.fersaiyan.cyanbridge.shared.generated.resources.family_ota_confirm_official
import com.fersaiyan.cyanbridge.shared.generated.resources.family_ota_confirm_title
import com.fersaiyan.cyanbridge.shared.generated.resources.family_ota_continue
import com.fersaiyan.cyanbridge.shared.generated.resources.family_ota_description_eyevue
import com.fersaiyan.cyanbridge.shared.generated.resources.family_ota_description_tunebuds
import com.fersaiyan.cyanbridge.shared.generated.resources.family_ota_dismiss
import com.fersaiyan.cyanbridge.shared.generated.resources.family_ota_installed
import com.fersaiyan.cyanbridge.shared.generated.resources.family_ota_install_file
import com.fersaiyan.cyanbridge.shared.generated.resources.family_ota_install_official
import com.fersaiyan.cyanbridge.shared.generated.resources.family_ota_official_unavailable_tunebuds
import com.fersaiyan.cyanbridge.shared.generated.resources.family_ota_recovery
import com.fersaiyan.cyanbridge.shared.generated.resources.family_ota_stage_cancelled
import com.fersaiyan.cyanbridge.shared.generated.resources.family_ota_stage_checking
import com.fersaiyan.cyanbridge.shared.generated.resources.family_ota_stage_downloading
import com.fersaiyan.cyanbridge.shared.generated.resources.family_ota_stage_failed
import com.fersaiyan.cyanbridge.shared.generated.resources.family_ota_stage_installing
import com.fersaiyan.cyanbridge.shared.generated.resources.family_ota_stage_preparing
import com.fersaiyan.cyanbridge.shared.generated.resources.family_ota_stage_reconnecting
import com.fersaiyan.cyanbridge.shared.generated.resources.family_ota_stage_succeeded
import com.fersaiyan.cyanbridge.shared.generated.resources.family_ota_stage_validating
import com.fersaiyan.cyanbridge.shared.generated.resources.family_ota_title
import com.fersaiyan.cyanbridge.shared.generated.resources.family_ota_unknown
import com.fersaiyan.cyanbridge.shared.generated.resources.family_ota_up_to_date
import com.fersaiyan.cyanbridge.shared.generated.resources.family_ota_update_available
import com.fersaiyan.cyanbridge.shared.generated.resources.family_ota_warning
import com.fersaiyan.cyanbridge.shared.generated.resources.family_ota_wifi_part_note
import com.fersaiyan.cyanbridge.shared.glasses.FamilyFirmwareCommand
import com.fersaiyan.cyanbridge.shared.glasses.FamilyOtaStage
import com.fersaiyan.cyanbridge.shared.glasses.FamilyOtaUiState
import org.jetbrains.compose.resources.stringResource

/**
 * The firmware section of the dashboard's Advanced card for EyeVue and TuneBuds: what the glasses
 * run, the update warnings, the blockers, progress, "Update to the latest official firmware"
 * (families with a vendor server) and "Install a firmware file", both behind the risk
 * acknowledgement the HeyCyan section uses.
 */
@Composable
internal fun FamilyOtaSection(
    ota: FamilyOtaUiState,
    onCommand: (FamilyFirmwareCommand) -> Unit,
    onCancel: () -> Unit,
) {
    var pending by remember { mutableStateOf<FamilyFirmwareCommand?>(null) }
    var acknowledged by remember { mutableStateOf(false) }
    val unknown = stringResource(Res.string.family_ota_unknown)
    val minBattery = "${ota.minBatteryPercent}%"

    Column(
        modifier = Modifier.testTag("advanced_family_ota"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SectionTitle(stringResource(Res.string.family_ota_title))
        Text(
            text = stringResource(
                if (ota.family == CustomizationFamily.EYEVUE) Res.string.family_ota_description_eyevue
                else Res.string.family_ota_description_tunebuds,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(Res.string.family_ota_installed, ota.modelCode ?: unknown, ota.currentVersion ?: unknown),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.testTag("family_ota_installed"),
        )
        WarningNote(
            icon = Icons.Outlined.WarningAmber,
            text = stringResource(Res.string.family_ota_warning, minBattery),
        )
        ota.blockers.takeIf { !ota.isRunning }?.forEach { blocker ->
            Text(
                text = when (blocker) {
                    FamilyOtaBlocker.NOT_CONNECTED -> stringResource(Res.string.family_ota_blocked_not_connected)
                    FamilyOtaBlocker.BATTERY_LOW -> stringResource(
                        Res.string.family_ota_blocked_battery,
                        minBattery,
                        ota.batteryPercent?.let { "$it%" } ?: unknown,
                    )
                    FamilyOtaBlocker.GLASSES_BUSY -> stringResource(Res.string.family_ota_blocked_busy)
                    FamilyOtaBlocker.AUDIO_BUSY -> stringResource(Res.string.family_ota_blocked_audio)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("family_ota_blocker_${blocker.name.lowercase()}"),
            )
        }
        FamilyOtaProgress(ota)
        when {
            ota.offer != null && ota.stage == FamilyOtaStage.UPDATE_AVAILABLE -> {
                Text(
                    stringResource(Res.string.family_ota_update_available, ota.offer.version),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                if (ota.offer.includesWifiPart) {
                    Text(
                        stringResource(Res.string.family_ota_wifi_part_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            ota.stage == FamilyOtaStage.UP_TO_DATE -> Text(
                stringResource(Res.string.family_ota_up_to_date),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("family_ota_up_to_date"),
            )
        }
        if (ota.officialUpdates) {
            val offer = ota.offer?.takeIf { ota.stage == FamilyOtaStage.UPDATE_AVAILABLE }
            ActionRow(
                primaryLabel = offer?.let { stringResource(Res.string.family_ota_install_official, it.version) }
                    ?: stringResource(Res.string.family_ota_check),
                onPrimary = {
                    if (offer != null) {
                        pending = FamilyFirmwareCommand.INSTALL_OFFICIAL
                    } else {
                        onCommand(FamilyFirmwareCommand.CHECK_OFFICIAL)
                    }
                },
                primaryStyle = ActionButtonStyle.Primary,
                primaryEnabled = ota.canStart,
                primaryIcon = Icons.Outlined.SystemUpdate,
                secondaryLabel = stringResource(Res.string.action_cancel),
                onSecondary = onCancel,
                secondaryStyle = ActionButtonStyle.Destructive,
                secondaryEnabled = ota.canCancel,
            )
            ActionButton(
                label = stringResource(Res.string.family_ota_install_file),
                onClick = { pending = FamilyFirmwareCommand.INSTALL_FILE },
                enabled = ota.canStart,
                modifier = Modifier.fillMaxWidth().testTag("family_ota_install_file"),
            )
        } else {
            Text(
                stringResource(Res.string.family_ota_official_unavailable_tunebuds),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ActionRow(
                primaryLabel = stringResource(Res.string.family_ota_install_file),
                onPrimary = { pending = FamilyFirmwareCommand.INSTALL_FILE },
                primaryStyle = ActionButtonStyle.Primary,
                primaryEnabled = ota.canStart,
                primaryIcon = Icons.Outlined.SystemUpdate,
                secondaryLabel = stringResource(Res.string.action_cancel),
                onSecondary = onCancel,
                secondaryStyle = ActionButtonStyle.Destructive,
                secondaryEnabled = ota.canCancel,
            )
        }
        if (ota.stage in FINISHED) {
            TextButton(
                onClick = { onCommand(FamilyFirmwareCommand.DISMISS) },
                modifier = Modifier.heightIn(min = 48.dp).testTag("family_ota_dismiss"),
            ) { Text(stringResource(Res.string.family_ota_dismiss)) }
        }
    }

    pending?.let { command ->
        val close = {
            pending = null
            acknowledged = false
        }
        AlertDialog(
            onDismissRequest = close,
            modifier = Modifier.testTag("family_ota_confirm"),
            icon = { WarningDialogIcon() },
            title = { Text(stringResource(Res.string.family_ota_confirm_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        when {
                            command == FamilyFirmwareCommand.INSTALL_OFFICIAL ->
                                stringResource(Res.string.family_ota_confirm_official, ota.offer?.version ?: unknown)
                            ota.family == CustomizationFamily.EYEVUE -> stringResource(Res.string.family_ota_confirm_file_eyevue)
                            else -> stringResource(Res.string.family_ota_confirm_file_tunebuds)
                        },
                    )
                    Text(stringResource(Res.string.family_ota_warning, minBattery))
                    // The whole row toggles, so the label is a large touch target and TalkBack reads one checkbox.
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .toggleable(value = acknowledged, role = Role.Checkbox, onValueChange = { acknowledged = it })
                            .testTag("family_ota_acknowledge"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = acknowledged, onCheckedChange = null)
                        Text(stringResource(Res.string.family_ota_acknowledge), modifier = Modifier.padding(start = 12.dp))
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = acknowledged,
                    onClick = {
                        close()
                        onCommand(command)
                    },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("family_ota_continue"),
                ) { Text(stringResource(Res.string.family_ota_continue)) }
            },
            dismissButton = {
                TextButton(onClick = close, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(Res.string.action_cancel))
                }
            },
        )
    }
}

private val FINISHED = setOf(
    FamilyOtaStage.UP_TO_DATE,
    FamilyOtaStage.SUCCEEDED,
    FamilyOtaStage.FAILED,
    FamilyOtaStage.CANCELLED,
)

@Composable
private fun FamilyOtaProgress(ota: FamilyOtaUiState) {
    val label = when (ota.stage) {
        FamilyOtaStage.CHECKING -> Res.string.family_ota_stage_checking
        FamilyOtaStage.PREPARING -> Res.string.family_ota_stage_preparing
        FamilyOtaStage.DOWNLOADING -> Res.string.family_ota_stage_downloading
        FamilyOtaStage.VALIDATING -> Res.string.family_ota_stage_validating
        FamilyOtaStage.INSTALLING -> Res.string.family_ota_stage_installing
        FamilyOtaStage.RECONNECTING -> Res.string.family_ota_stage_reconnecting
        FamilyOtaStage.SUCCEEDED -> Res.string.family_ota_stage_succeeded
        FamilyOtaStage.FAILED -> Res.string.family_ota_stage_failed
        FamilyOtaStage.CANCELLED -> Res.string.family_ota_stage_cancelled
        FamilyOtaStage.IDLE, FamilyOtaStage.UP_TO_DATE, FamilyOtaStage.UPDATE_AVAILABLE -> null
    } ?: return
    Column(
        modifier = Modifier
            .testTag("family_ota_progress")
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val percent = ota.progress?.takeIf { ota.stage == FamilyOtaStage.INSTALLING || ota.stage == FamilyOtaStage.DOWNLOADING }
        Text(
            text = stringResource(label) + (percent?.let { " $it%" } ?: ""),
            style = MaterialTheme.typography.bodyMedium,
            color = when (ota.stage) {
                FamilyOtaStage.FAILED -> MaterialTheme.colorScheme.error
                FamilyOtaStage.SUCCEEDED -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurface
            },
        )
        if (ota.isRunning) {
            val barModifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(percent = 50))
            if (percent != null) {
                LinearProgressIndicator(progress = { percent.coerceIn(0, 100) / 100f }, modifier = barModifier)
            } else {
                LinearProgressIndicator(modifier = barModifier)
            }
        }
        if (ota.detail.isNotBlank()) {
            Text(
                text = ota.detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("family_ota_detail"),
            )
        }
        if (ota.stage == FamilyOtaStage.FAILED) {
            WarningNote(icon = Icons.Outlined.BatteryAlert, text = stringResource(Res.string.family_ota_recovery))
        }
    }
}

@Composable
private fun WarningNote(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Text(text, style = MaterialTheme.typography.bodySmall)
        }
    }
}
