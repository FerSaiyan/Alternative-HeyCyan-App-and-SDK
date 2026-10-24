package com.fersaiyan.cyanbridge.ui
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.hjq.permissions.OnPermissionCallback
import com.hjq.permissions.Permission
import com.hjq.permissions.XXPermissions

private const val AUTO_INPUT_PACKAGE = "com.joaomgcd.autoinput"

private fun requiredBluetoothPermissions(): List<String> {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        listOf(Permission.BLUETOOTH_SCAN, Permission.BLUETOOTH_CONNECT)
    } else {
        listOf(Permission.ACCESS_FINE_LOCATION)
    }
}

fun hasBluetooth(context: Context): Boolean {
    return requiredBluetoothPermissions().all { permission ->
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }
}

fun requestBluetoothPermission(
    activity: FragmentActivity,
    requestCallback: OnPermissionCallback
) {
    XXPermissions.with(activity)
        .permission(requiredBluetoothPermissions())
        .request(requestCallback)
}

fun ensureBluetoothRuntimePermission(
    activity: FragmentActivity,
    feature: String,
    onGranted: () -> Unit,
) {
    if (hasBluetooth(activity)) {
        onGranted()
        return
    }

    requestBluetoothPermission(activity, object : OnPermissionCallback {
        override fun onGranted(permissions: MutableList<String>, all: Boolean) {
            if (all) {
                onGranted()
            } else {
                Toast.makeText(
                    activity,
                    "Bluetooth permission is required for $feature",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }

        override fun onDenied(permissions: MutableList<String>, never: Boolean) {
            super.onDenied(permissions, never)
            Toast.makeText(
                activity,
                "Bluetooth permission is required for $feature",
                Toast.LENGTH_LONG,
            ).show()
            if (never) {
                XXPermissions.startPermissionActivity(activity, permissions)
            }
        }
    })
}

private fun requiredWifiP2pPermissions(): List<String> {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        listOf(Permission.NEARBY_WIFI_DEVICES)
    } else {
        listOf(Permission.ACCESS_FINE_LOCATION)
    }
}

fun requestWifiP2pPermission(
    activity: FragmentActivity,
    requestCallback: OnPermissionCallback,
) {
    XXPermissions.with(activity)
        .permission(requiredWifiP2pPermissions())
        .request(requestCallback)
}

fun hasWifiP2pPermission(context: Context): Boolean {
    return requiredWifiP2pPermissions().all { permission ->
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }
}

fun hasNotificationPermission(context: Context): Boolean {
    return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(
            context,
            Permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
}

fun requestNotificationPermission(
    activity: FragmentActivity,
    requestCallback: OnPermissionCallback,
) {
    if (hasNotificationPermission(activity)) {
        requestCallback.onGranted(mutableListOf(Permission.POST_NOTIFICATIONS), true)
        return
    }
    XXPermissions.with(activity)
        .permission(Permission.POST_NOTIFICATIONS)
        .request(requestCallback)
}

/**
 * Compatibility name retained for older callers. CyanBridge no longer declares its own
 * AccessibilityService; Android UI automation is delegated to AutoInput, so readiness now
 * means that AutoInput's accessibility service is enabled.
 */
fun hasAccessibilityServicePermission(context: Context): Boolean {
    val enabled = Settings.Secure.getInt(
        context.contentResolver,
        Settings.Secure.ACCESSIBILITY_ENABLED,
        0,
    ) == 1
    if (!enabled) return false

    val services = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
    ).orEmpty()
    return services.split(':')
        .mapNotNull(ComponentName::unflattenFromString)
        .any { it.packageName.equals(AUTO_INPUT_PACKAGE, ignoreCase = true) }
}

fun ensureNotificationPermission(
    activity: FragmentActivity,
    feature: String,
    onDenied: () -> Unit = {},
    onGranted: () -> Unit,
) {
    if (hasNotificationPermission(activity)) {
        onGranted()
        return
    }

    requestNotificationPermission(activity, object : OnPermissionCallback {
        override fun onGranted(permissions: MutableList<String>, all: Boolean) {
            if (all) {
                onGranted()
            } else {
                Toast.makeText(
                    activity,
                    "Notification permission is required for $feature",
                    Toast.LENGTH_LONG,
                ).show()
                onDenied()
            }
        }

        override fun onDenied(permissions: MutableList<String>, never: Boolean) {
            super.onDenied(permissions, never)
            Toast.makeText(
                activity,
                "Notification permission is required for $feature",
                Toast.LENGTH_LONG,
            ).show()
            onDenied()
            if (never) {
                XXPermissions.startPermissionActivity(activity, permissions)
            }
        }
    })
}
