package com.fersaiyan.cyanbridge.ui.glasses

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fersaiyan.cyanbridge.ui.theme.CyanAccent
import com.oudmon.ble.base.bluetooth.BleOperateManager
import com.oudmon.ble.base.communication.LargeDataHandler

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlassesScreen() {
    val context = LocalContext.current
    var isConnected by remember { mutableStateOf(BleOperateManager.getInstance().isConnected) }
    var batteryLevel by remember { mutableStateOf<Int?>(null) }
    var deviceClass by remember { mutableStateOf<String?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { _ -> }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Filled.Star,
                            contentDescription = null,
                            tint = CyanAccent,
                            modifier = Modifier.size(28.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "CyanBridge",
                            color = MaterialTheme.colorScheme.onBackground,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            ConnectionStatusCard(
                isConnected = isConnected,
                deviceClass = deviceClass,
                batteryLevel = batteryLevel,
            )

            Spacer(modifier = Modifier.height(16.dp))

            QuickActionsCard(
                onScan = {
                    val perms = arrayOf(
                        Manifest.permission.BLUETOOTH_SCAN,
                        Manifest.permission.ACCESS_FINE_LOCATION,
                    )
                    val notGranted = perms.filter {
                        context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
                    }
                    if (notGranted.isEmpty()) {
                        requestLocationPermission()
                    } else {
                        permissionLauncher.launch(notGranted.toTypedArray())
                    }
                },
                onConnect = {
                    requestBluetoothConnection()
                },
                onDisconnect = {
                    BleOperateManager.getInstance().unBindDevice()
                    isConnected = false
                },
            )

            Spacer(modifier = Modifier.height(16.dp))

            AiModeCard()

            Spacer(modifier = Modifier.height(16.dp))

            MediaControlsCard()

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Legacy dashboard →",
                style = MaterialTheme.typography.bodySmall,
                color = CyanAccent,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun ConnectionStatusCard(
    isConnected: Boolean,
    deviceClass: String?,
    batteryLevel: Int?,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isConnected) {
                CyanAccent.copy(alpha = 0.1f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(
                            if (isConnected) CyanAccent else MaterialTheme.colorScheme.onSurfaceVariant
                        ),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isConnected) "Connected" else "Not connected",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (isConnected) CyanAccent else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (deviceClass != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Device: $deviceClass",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (batteryLevel != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Battery: $batteryLevel%",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun QuickActionsCard(onScan: () -> Unit, onConnect: () -> Unit, onDisconnect: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Connection",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ActionButton(
                    text = "Scan",
                    modifier = Modifier.weight(1f),
                    onClick = onScan,
                )
                ActionButton(
                    text = "Connect",
                    modifier = Modifier.weight(1f),
                    onClick = onConnect,
                )
                ActionButton(
                    text = "Disconnect",
                    modifier = Modifier.weight(1f),
                    onClick = onDisconnect,
                    outlined = true,
                )
            }
        }
    }
}

@Composable
private fun AiModeCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "AI Assistant",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ActionButton(text = "Gemini", modifier = Modifier.weight(1f), onClick = {})
                ActionButton(text = "ChatGPT", modifier = Modifier.weight(1f), onClick = {})
                ActionButton(text = "Tasker", modifier = Modifier.weight(1f), onClick = {}, outlined = true)
            }
        }
    }
}

@Composable
private fun MediaControlsCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Media",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ActionButton(text = "Camera", modifier = Modifier.weight(1f), onClick = {})
                ActionButton(text = "Video", modifier = Modifier.weight(1f), onClick = {})
                ActionButton(text = "Record", modifier = Modifier.weight(1f), onClick = {})
            }
        }
    }
}

@Composable
private fun ActionButton(
    text: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    outlined: Boolean = false,
) {
    if (outlined) {
        Button(
            onClick = onClick,
            modifier = modifier.height(44.dp),
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.background,
                contentColor = CyanAccent,
            ),
        ) {
            Text(text, style = MaterialTheme.typography.bodySmall)
        }
    } else {
        Button(
            onClick = onClick,
            modifier = modifier.height(44.dp),
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = CyanAccent,
                contentColor = MaterialTheme.colorScheme.background,
            ),
        ) {
            Text(text, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun requestLocationPermission() {
}

private fun requestBluetoothConnection() {
}
