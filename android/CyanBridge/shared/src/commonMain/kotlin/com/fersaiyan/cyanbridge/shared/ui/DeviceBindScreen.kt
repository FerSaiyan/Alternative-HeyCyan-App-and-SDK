package com.fersaiyan.cyanbridge.shared.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.DevicesOther
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fersaiyan.cyanbridge.shared.devices.DeviceClass
import com.fersaiyan.cyanbridge.shared.devices.ScannedDevice
import com.fersaiyan.cyanbridge.shared.generated.resources.*
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.stringResource

/**
 * User-facing pairing groups the closely-related consumer camera-glasses protocols.
 * HEY_CYAN is the UI sentinel for HeyCyan / EyeVue / TuneBuds / MoYoung. Tapping it
 * opens a second manual picker with the four concrete protocols; no automatic probing
 * is performed. The concrete choice is persisted as the selected class.
 */
private val pairingChoices = listOf(
    DeviceClass.HEY_CYAN,
    DeviceClass.META_RAYBAN,
    DeviceClass.MEIZU_MYVU,
    DeviceClass.GENERIC_AUDIO,
)

private val consumerProtocolChoices = listOf(
    DeviceClass.HEY_CYAN,
    DeviceClass.EYEVUE,
    DeviceClass.TUNEBUDS,
    DeviceClass.MOYOUNG_W620,
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
    onConfirmManualProtocol: (DeviceClass) -> Unit = {},
    onDismissConnection: () -> Unit,
    onBack: () -> Unit,
) {
    var showManualProtocolPicker by remember { mutableStateOf(false) }
    var manualSelection by remember { mutableStateOf(DeviceClass.HEY_CYAN) }
    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.device_bind_title)) },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(Res.string.action_back),
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = onScan,
                        modifier = Modifier.size(48.dp),
                    ) {
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
                Button(
                    onClick = onScan,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp),
                ) {
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
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp),
                ) {
                    Text(stringResource(Res.string.device_bind_pair_meta))
                }
            }
            if (devices.isEmpty()) {
                item {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        shape = MaterialTheme.shapes.extraLarge,
                    ) {
                        Text(
                            text = if (isScanning) {
                                stringResource(Res.string.device_bind_looking)
                            } else {
                                stringResource(Res.string.device_bind_empty)
                            },
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp),
                        )
                    }
                }
            } else {
                items(devices, key = { it.macAddress }) { device ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.extraLarge,
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        ),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 88.dp)
                                .padding(16.dp),
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
                                Surface(
                                    color = MaterialTheme.colorScheme.secondaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                    shape = MaterialTheme.shapes.large,
                                ) {
                                    Text(
                                        text = stringResource(Res.string.device_bind_signal, device.rssi),
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                        style = MaterialTheme.typography.labelMedium,
                                    )
                                }
                            }
                            FilledTonalButton(
                                onClick = { onSelectDevice(device) },
                                modifier = Modifier.heightIn(min = 48.dp),
                            ) {
                                Text(stringResource(Res.string.action_connect))
                            }
                        }
                    }
                }
            }
        }
    }

    connectingDevice?.let { device ->
        if (!showManualProtocolPicker) {
            AlertDialog(
                onDismissRequest = {
                    showManualProtocolPicker = false
                    onDismissConnection()
                },
                icon = { BindDialogIcon(Icons.Outlined.DevicesOther) },
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
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp),
                            )
                        }
                    }
                },
                confirmButton = {
                    FilledTonalButton(
                        onClick = {
                            if (selectedClass == DeviceClass.HEY_CYAN) {
                                val hint = device.effectiveSelectedClass()
                                manualSelection = when (hint) {
                                    DeviceClass.EYEVUE,
                                    DeviceClass.TUNEBUDS,
                                    DeviceClass.MOYOUNG_W620,
                                    DeviceClass.HEY_CYAN,
                                    -> hint
                                    else -> DeviceClass.HEY_CYAN
                                }
                                showManualProtocolPicker = true
                            } else {
                                onConfirmConnection()
                            }
                        },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text(stringResource(Res.string.action_connect))
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            showManualProtocolPicker = false
                            onDismissConnection()
                        },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text(stringResource(Res.string.action_cancel))
                    }
                },
            )
        } else {
            AlertDialog(
                onDismissRequest = { showManualProtocolPicker = false },
                icon = { BindDialogIcon(Icons.Outlined.DevicesOther) },
                title = { Text(stringResource(Res.string.device_bind_manual_protocol_title)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = stringResource(Res.string.device_bind_manual_protocol_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = device.advertisedName
                                ?.takeIf { it.isNotBlank() }
                                ?: stringResource(Res.string.device_bind_unnamed_device),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        consumerProtocolChoices.forEach { type ->
                            FilterChip(
                                selected = manualSelection == type,
                                onClick = { manualSelection = type },
                                label = { Text(localizedDeviceClass(type)) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp),
                            )
                        }
                    }
                },
                confirmButton = {
                    FilledTonalButton(
                        onClick = {
                            showManualProtocolPicker = false
                            onConfirmManualProtocol(manualSelection)
                        },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text(stringResource(Res.string.action_connect))
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { showManualProtocolPicker = false },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text(stringResource(Res.string.action_cancel))
                    }
                },
            )
        }
    }
}

@Composable
private fun BindDialogIcon(imageVector: ImageVector) {
    Surface(
        modifier = Modifier.size(48.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = MaterialTheme.shapes.large,
    ) {
        Icon(
            imageVector = imageVector,
            contentDescription = null,
            modifier = Modifier.padding(12.dp),
        )
    }
}
