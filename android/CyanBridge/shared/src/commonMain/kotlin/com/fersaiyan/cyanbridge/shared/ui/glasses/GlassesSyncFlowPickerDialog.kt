package com.fersaiyan.cyanbridge.shared.ui.glasses

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.fersaiyan.cyanbridge.shared.glasses.GlassesSyncFlow
import com.fersaiyan.cyanbridge.shared.ui.localizedSyncFlowDescription
import com.fersaiyan.cyanbridge.shared.ui.localizedSyncFlowLabel
import com.fersaiyan.cyanbridge.shared.generated.resources.Res
import com.fersaiyan.cyanbridge.shared.generated.resources.action_cancel
import com.fersaiyan.cyanbridge.shared.generated.resources.sync_flow_experimental
import com.fersaiyan.cyanbridge.shared.generated.resources.sync_flow_title
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.stringResource

/**
 * Compose-owned picker for the Android media-sync handlers, shown on every Sync tap unless
 * Settings → Media sync remembers a flow.
 */
@OptIn(ExperimentalResourceApi::class)
@Composable
fun GlassesSyncFlowPickerDialog(
    onDismissRequest: () -> Unit,
    onFlowSelected: (GlassesSyncFlow) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        icon = {
            Surface(
                modifier = Modifier.size(48.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                shape = MaterialTheme.shapes.large,
            ) {
                Icon(
                    imageVector = Icons.Outlined.SwapHoriz,
                    contentDescription = null,
                    modifier = Modifier.padding(12.dp),
                )
            }
        },
        title = { Text(stringResource(Res.string.sync_flow_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                GlassesSyncFlow.entries.forEach { flow ->
                    OutlinedButton(
                        onClick = { onFlowSelected(flow) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 72.dp)
                            .testTag("sync_flow_${flow.name}"),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(localizedSyncFlowLabel(flow), style = MaterialTheme.typography.labelLarge)
                                if (flow.experimental) SyncFlowExperimentalTag()
                            }
                            Text(
                                text = localizedSyncFlowDescription(flow),
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
            ) {
                Text(stringResource(Res.string.action_cancel))
            }
        },
    )
}

/** The "Experimental" tag next to a sync flow name (the Adaptive flow). */
@OptIn(ExperimentalResourceApi::class)
@Composable
fun SyncFlowExperimentalTag() {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            text = stringResource(Res.string.sync_flow_experimental),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}
