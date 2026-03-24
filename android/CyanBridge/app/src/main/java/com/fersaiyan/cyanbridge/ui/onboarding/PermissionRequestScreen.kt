package com.fersaiyan.cyanbridge.ui.onboarding

import android.Manifest
import android.app.Activity
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fersaiyan.cyanbridge.ui.theme.Danger
import com.hjq.permissions.OnPermissionCallback
import com.hjq.permissions.XXPermissions

@Composable
fun PermissionRequestScreen(
    onComplete: () -> Unit,
) {
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        val activity = context as? Activity ?: return@LaunchedEffect
        XXPermissions.with(activity)
            .permission(Manifest.permission.BLUETOOTH_SCAN)
            .permission(Manifest.permission.BLUETOOTH_CONNECT)
            .permission(Manifest.permission.ACCESS_FINE_LOCATION)
            .permission(Manifest.permission.RECORD_AUDIO)
            .request(object : OnPermissionCallback {
                override fun onGranted(permissions: MutableList<String>, allGranted: Boolean) {
                    if (allGranted) {
                        Toast.makeText(context, "All permissions granted", Toast.LENGTH_SHORT).show()
                    }
                }

                override fun onDenied(permissions: MutableList<String>, doNotAskAgain: Boolean) {
                    Toast.makeText(
                        context,
                        "Some permissions were denied. The app may not work fully.",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            })
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(20.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        Text(
            text = "Permissions Needed",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )

        Spacer(modifier = Modifier.height(10.dp))

        Text(
            text = "CyanBridge needs Bluetooth, Location, and Microphone permissions to connect to your smart glasses and provide hands-free features.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Bluetooth — Connect to your glasses\nLocation — Scan for nearby devices\nMicrophone — Voice commands and audio recording",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = {
                val activity = context as? Activity ?: return@Button
                XXPermissions.with(activity)
                    .permission(Manifest.permission.BLUETOOTH_SCAN)
                    .permission(Manifest.permission.BLUETOOTH_CONNECT)
                    .permission(Manifest.permission.ACCESS_FINE_LOCATION)
                    .permission(Manifest.permission.RECORD_AUDIO)
                    .request(object : OnPermissionCallback {
                        override fun onGranted(permissions: MutableList<String>, allGranted: Boolean) {
                            onComplete()
                        }

                        override fun onDenied(permissions: MutableList<String>, doNotAskAgain: Boolean) {
                            onComplete()
                        }
                    })
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.background,
            ),
        ) {
            Text("Grant Permissions")
        }

        Spacer(modifier = Modifier.height(10.dp))

        OutlinedButton(
            onClick = onComplete,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = Danger,
            ),
        ) {
            Text("Skip")
        }
    }
}
