package com.fersaiyan.cyanbridge.ui.onboarding

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.fersaiyan.cyanbridge.ui.theme.Danger

@Composable
fun BatteryOptimizationScreen(
    onComplete: () -> Unit,
) {
    val context = LocalContext.current
    var isOptimized by remember { mutableStateOf(!isBatteryOptimizationIgnored(context)) }

    val batteryOptLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        isOptimized = !isBatteryOptimizationIgnored(context)
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isOptimized = !isBatteryOptimizationIgnored(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
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
            text = "Keep CyanBridge Running",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )

        Spacer(modifier = Modifier.height(10.dp))

        Text(
            text = if (isOptimized) {
                "Status: Battery optimization is ON for CyanBridge"
            } else {
                "Status: Battery optimization is OFF for CyanBridge"
            },
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )

        Spacer(modifier = Modifier.height(10.dp))

        Text(
            text = "Some midrange/low-end phones may disconnect Bluetooth devices when CyanBridge is in the background or when you close the app. To keep your glasses connected, disable battery optimization for CyanBridge.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                    Toast.makeText(context, "Battery optimization settings are not available on this Android version.", Toast.LENGTH_SHORT).show()
                    return@Button
                }
                try {
                    batteryOptLauncher.launch(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                            data = Uri.parse("package:${context.packageName}")
                        }
                    )
                } catch (_: ActivityNotFoundException) {
                    openBatteryOptimizationList(context)
                }
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.background,
            ),
        ) {
            Text("Disable Battery Optimization")
        }

        Spacer(modifier = Modifier.height(10.dp))

        OutlinedButton(
            onClick = { openBatteryOptimizationList(context) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Open Battery Optimization Settings")
        }

        Spacer(modifier = Modifier.height(10.dp))

        OutlinedButton(
            onClick = { openAppInfo(context) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Open App Info")
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Also lock CyanBridge in Recents",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = "Open Recents, find CyanBridge, then use the app menu (often a long-press or tap the app icon) and choose Lock/Pin/Keep open. This prevents the system from clearing the app.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(18.dp))

        Button(
            onClick = {
                if (isBatteryOptimizationIgnored(context)) {
                    onComplete()
                } else {
                    Toast.makeText(
                        context,
                        "Battery optimization still appears to be ON. Please disable it, then tap \"I Locked It\" again.",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.background,
            ),
        ) {
            Text("I Locked It")
        }

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedButton(
            onClick = onComplete,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = Danger,
            ),
        ) {
            Text("Don't show again")
        }
    }
}

private fun isBatteryOptimizationIgnored(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
    val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    return pm.isIgnoringBatteryOptimizations(context.packageName)
}

private fun openBatteryOptimizationList(context: Context) {
    try {
        context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    } catch (_: ActivityNotFoundException) {
        openAppInfo(context)
    }
}

private fun openAppInfo(context: Context) {
    try {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
            },
        )
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "Could not open settings on this device.", Toast.LENGTH_SHORT).show()
    }
}
