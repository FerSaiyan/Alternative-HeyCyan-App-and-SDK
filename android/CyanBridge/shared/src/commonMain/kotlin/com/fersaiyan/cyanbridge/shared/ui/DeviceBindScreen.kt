package com.fersaiyan.cyanbridge.shared.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fersaiyan.cyanbridge.shared.devices.DeviceClass
import com.fersaiyan.cyanbridge.shared.devices.ScannedDevice
import com.fersaiyan.cyanbridge.shared.generated.resources.*
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.stringResource

/**
 * User-facing pairing intentionally groups the closely-related consumer camera-glasses protocols.
 * HEY_CYAN is used as the UI sentinel for this automatic family; Android resolves and persists the
 * actual HEY_CYAN / EYEVUE / TUNEBUDS protocol after the user confirms the device.
 */
private val pairingChoices = listOf(
    DeviceClass.HEY_CYAN,
    DeviceClass.META_RAYBAN,
    DeviceClass.MEIZU_MYVU,
    DeviceClass.GENERIC_AUDIO,
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalResourceApi::class)
@Composable
fun DeviceBindScreen(
    devices: List<ScannedDevice>,
    isScanning: Boolean,
    connectingDevice: ScannedDevice?,
    selectedClass: DeviceClass,
    onScan: () -> Unit,
    onPairMetaGlasses: () -> Unit,
    onSelectDevice: (ScannedDevice) -> Unit,
    onSelectedClassChange: (DeviceClass) -> Unit,
    onConfirmConnection: () -> Unit,
    onDismissConnection: () -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.device_bind_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(Res.string.action_back),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onScan) {
                        Icon(
                            Icons.Outlined.Refresh,
                            contentDescription = stringResource(Res.string.device_bind_scan),
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
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                FilledTonalButton(onClick = onScan, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        if (isScanning) {
                            stringResource(Res.string.device_bind_scanning)
                        } else {
                            stringResource(Res.string.device_bind_scan)
                        },
                    )
                }
            }
            item {
                OutlinedButton(
                    onClick = onPairMetaGlasses,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(Res.string.device_bind_pair_meta))
                }
            }
            if (devices.isEmpty()) {
                item {
                    Text(
                        text = if (isScanning) {
                            stringResource(Res.string.device_bind_looking)
                        } else {
                            stringResource(Res.string.device_bind_empty)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 24.dp),
                    )
                }
            } else {
                items(devices, key = { it.macAddress }) { device ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = device.advertisedName
                                        ?.takeIf { it.isNotBlank() }
                                        ?: stringResource(Res.string.device_bind_unnamed_device),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    text = stringResource(Res.string.device_bind_signal, device.rssi),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            OutlinedButton(onClick = { onSelectDevice(device) }) {
                                Text(stringResource(Res.string.action_connect))
                            }
                        }
                    }
                }
            }
        }
    }

    connectingDevice?.let { device ->
        AlertDialog(
            onDismissRequest = onDismissConnection,
            title = { Text(stringResource(Res.string.device_bind_select_type)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = device.advertisedName
                            ?.takeIf { it.isNotBlank() }
                            ?: stringResource(Res.string.device_bind_unnamed_device),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    pairingChoices.forEach { type ->
                        FilterChip(
                            selected = selectedClass == type,
                            onClick = { onSelectedClassChange(type) },
                            label = {
                                if (type == DeviceClass.HEY_CYAN) {
                                    Column {
                                        Text(stringResource(Res.string.device_bind_auto_camera_glasses))
                                        Text(
                                            stringResource(Res.string.device_bind_auto_camera_glasses_hint),
                                            style = MaterialTheme.typography.labelSmall,
                                        )
                                    }
                                } else {
                                    Text(localizedDeviceClass(type))
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = onConfirmConnection) {
                    Text(stringResource(Res.string.action_connect))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissConnection) {
                    Text(stringResource(Res.string.action_cancel))
                }
            },
        )
    }
}
