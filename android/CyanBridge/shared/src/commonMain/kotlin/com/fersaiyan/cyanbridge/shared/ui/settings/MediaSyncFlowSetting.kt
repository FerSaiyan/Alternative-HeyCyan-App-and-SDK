package com.fersaiyan.cyanbridge.shared.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.fersaiyan.cyanbridge.shared.generated.resources.Res
import com.fersaiyan.cyanbridge.shared.generated.resources.action_cancel
import com.fersaiyan.cyanbridge.shared.generated.resources.settings_media_sync
import com.fersaiyan.cyanbridge.shared.generated.resources.settings_media_sync_ask
import com.fersaiyan.cyanbridge.shared.generated.resources.settings_media_sync_hint
import com.fersaiyan.cyanbridge.shared.glasses.GlassesSyncFlow
import com.fersaiyan.cyanbridge.shared.ui.glasses.SyncFlowExperimentalTag
import com.fersaiyan.cyanbridge.shared.ui.localizedSyncFlowDescription
import com.fersaiyan.cyanbridge.shared.ui.localizedSyncFlowLabel
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.stringResource

/** The Settings → Media sync subtitle value: the remembered flow, or "Ask every time". */
@OptIn(ExperimentalResourceApi::class)
@Composable
internal fun mediaSyncFlowChoiceLabel(flow: GlassesSyncFlow?): String =
    flow?.let { localizedSyncFlowLabel(it) } ?: stringResource(Res.string.settings_media_sync_ask)

/**
 * Remember a HeyCyan media sync flow, or ask every time (null, the default). Selecting an
 * option applies it and closes the dialog.
 */
@OptIn(ExperimentalResourceApi::class)
@Composable
internal fun MediaSyncFlowDialog(
    selected: GlassesSyncFlow?,
    onSelect: (GlassesSyncFlow?) -> Unit,
    onDismissRequest: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(Res.string.settings_media_sync)) },
        text = {
            Column(
                modifier = Modifier.selectableGroup(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    stringResource(Res.string.settings_media_sync_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                (listOf<GlassesSyncFlow?>(null) + GlassesSyncFlow.entries).forEach { flow ->
                    MediaSyncFlowOption(flow, selected = flow == selected, onSelect = { onSelect(flow) })
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismissRequest, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(Res.string.action_cancel))
            }
        },
    )
}

@Composable
private fun MediaSyncFlowOption(flow: GlassesSyncFlow?, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .testTag("settings_media_sync_${flow?.name ?: "ASK"}"),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(modifier = Modifier.weight(1f)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(mediaSyncFlowChoiceLabel(flow), style = MaterialTheme.typography.bodyLarge)
                if (flow?.experimental == true) SyncFlowExperimentalTag()
            }
            if (flow != null) {
                Text(
                    localizedSyncFlowDescription(flow),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
