package com.fersaiyan.cyanbridge.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Warns before HeyCyan Wi-Fi Direct sync when the official app can compete for
 * the same glasses connection. This is informational because Android does not
 * allow CyanBridge to force-stop another application.
 */
@Composable
internal fun OfficialHeyCyanWarningDialog(
    onDismissRequest: () -> Unit,
    onOpenAppInfo: () -> Unit,
    onSyncAnyway: (suppressFutureWarnings: Boolean) -> Unit,
) {
    var suppressFutureWarnings by rememberSaveable { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        icon = {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            ) {
                Box(
                    modifier = Modifier.size(52.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.WarningAmber,
                        contentDescription = null,
                        modifier = Modifier.size(28.dp),
                    )
                }
            }
        },
        title = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = "CONNECTION PROTECTION",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "HeyCyan may interrupt sync",
                    style = MaterialTheme.typography.headlineSmall,
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    text = "The official HeyCyan app is installed. If it is running, it can " +
                        "take over the glasses Bluetooth or Wi-Fi Direct connection and interrupt " +
                        "CyanBridge media sync.",
                    style = MaterialTheme.typography.bodyLarge,
                )

                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Info,
                            contentDescription = null,
                            modifier = Modifier.size(22.dp),
                        )
                        Text(
                            text = "For a reliable sync, open App info and force-stop HeyCyan, " +
                                "then return to CyanBridge. Android does not let CyanBridge stop " +
                                "another app for you.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.large)
                        .clickable(
                            role = Role.Checkbox,
                            onClick = { suppressFutureWarnings = !suppressFutureWarnings },
                        )
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = suppressFutureWarnings,
                        onCheckedChange = null,
                    )
                    Text(
                        text = "Don't remind me again",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onOpenAppInfo,
                shapes = ButtonDefaults.shapes(shape = MaterialTheme.shapes.large),
            ) {
                Text("Open App info")
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onDismissRequest, shapes = ButtonDefaults.shapes()) {
                    Text("Cancel")
                }
                FilledTonalButton(
                    onClick = { onSyncAnyway(suppressFutureWarnings) },
                    shapes = ButtonDefaults.shapes(shape = MaterialTheme.shapes.large),
                ) {
                    Text("Sync anyway")
                }
            }
        },
        shape = MaterialTheme.shapes.extraLarge,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 6.dp,
    )
}
