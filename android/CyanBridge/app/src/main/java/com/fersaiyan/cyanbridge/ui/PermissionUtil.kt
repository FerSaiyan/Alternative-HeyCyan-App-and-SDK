package com.fersaiyan.cyanbridge.ui
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.hjq.permissions.OnPermissionCallback
import com.hjq.permissions.Permission
import com.hjq.permissions.XXPermissions

private const val AUTO_INPUT_PACKAGE = "com.joaomgcd.autoinput"

/**
 * @author hzy ,
 * @date  2020/12/22
 * <p>
 * "Programs should be written for other people to read,
 * and only incidentally for machines to execute"
 **/
fun requestCallPhonePermission(
    activity: FragmentActivity,
    requestCallback: OnPermissionCallback,
) {
    XXPermissions.with(activity)
        .permission(Permission.READ_PHONE_STATE)
        .permission(Permission.READ_CALL_LOG)
        .permission(Permission.CALL_PHONE)
        .permission(Permission.READ_CONTACTS)
        .permission(Permission.ANSWER_PHONE_CALLS)
        .request(requestCallback)
}

fun hasCallPhonePermission(
    activity: FragmentActivity,
): Boolean {
    val permissions = mutableListOf<String>()
    permissions.add(Permission.READ_PHONE_STATE)
    permissions.add(Permission.READ_CALL_LOG)
    permissions.add(Permission.CALL_PHONE)
    permissions.add(Permission.READ_CONTACTS)
    permissions.add(Permission.ANSWER_PHONE_CALLS)
    return XXPermissions.isGranted(activity, permissions)
}

fun hasCameraPermission(
    context: Context,
): Boolean {
    val permissions = mutableListOf<String>()
    permissions.add(Permission.CAMERA)
    return XXPermissions.isGranted(context, permissions)
}

fun hasSMSPermission(
    activity: FragmentActivity,
): Boolean {
    val permissions = mutableListOf<String>()
    permissions.add(Permission.READ_SMS)
    permissions.add(Permission.RECEIVE_SMS)
    return XXPermissions.isGranted(activity, permissions)
}

fun hasContactPermission(activity: FragmentActivity): Boolean {
    return XXPermissions.isGranted(activity, Permission.READ_CONTACTS)
}

fun hasLocationPermission(activity: FragmentActivity): Boolean {
    return XXPermissions.isGranted(activity, Permission.ACCESS_FINE_LOCATION)
}

fun hasBgLocationPermission(activity: FragmentActivity): Boolean {
    return XXPermissions.isGranted(activity, Permission.ACCESS_BACKGROUND_LOCATION)
}

fun hasCallPermission(activity: FragmentActivity): Boolean {
    val permissions = mutableListOf<String>()
    permissions.add(Permission.READ_PHONE_STATE)
    permissions.add(Permission.READ_CALL_LOG)
    permissions.add(Permission.CALL_PHONE)
    permissions.add(Permission.ANSWER_PHONE_CALLS)
    return XXPermissions.isGranted(activity, permissions)
}

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

fun requestSMSPermission(
    activity: FragmentActivity,
    requestCallback: OnPermissionCallback,
) {
    XXPermissions.with(activity)
        .permission(Permission.READ_SMS)
        .permission(Permission.RECEIVE_SMS)
        .request(requestCallback)
}

fun requestLocationPermission(
    activity: FragmentActivity,
    requestCallback: OnPermissionCallback,
) {
    XXPermissions.with(activity)
        .permission(Permission.ACCESS_COARSE_LOCATION)
        .permission(Permission.ACCESS_FINE_LOCATION)
        .request(requestCallback)
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

fun requestCallPermission(
    activity: FragmentActivity,
    requestCallback: OnPermissionCallback,
) {
    XXPermissions.with(activity)
        .permission(Permission.READ_PHONE_STATE)
        .permission(Permission.READ_CALL_LOG)
        .permission(Permission.CALL_PHONE)
        .permission(Permission.ANSWER_PHONE_CALLS)
        .request(requestCallback)
}

fun requestContactPermission(
    activity: FragmentActivity,
    requestCallback: OnPermissionCallback,
) {
    XXPermissions.with(activity)
        .permission(Permission.READ_CONTACTS)
        .request(requestCallback)
}

fun requestBgLocation(activity: FragmentActivity, requestCallback: OnPermissionCallback) {
    XXPermissions.with(activity)
        .permission(Permission.ACCESS_COARSE_LOCATION)
        .permission(Permission.ACCESS_FINE_LOCATION)
        .permission(Permission.ACCESS_BACKGROUND_LOCATION)
        .request(requestCallback)
}

fun requestAlertWindowPermission(activity: FragmentActivity) {
    activity.startActivity(
        Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${activity.packageName}"),
        )
    )
}

fun requestNearbyWifiDevicesPermission(
    activity: FragmentActivity,
    requestCallback: OnPermissionCallback
) {
    requestWifiP2pPermission(activity, requestCallback)
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

fun hasNearbyWifiDevicesPermission(
    context: Context,
): Boolean {
    return hasWifiP2pPermission(context)
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

fun requestAccessibilityServicePermission(
    activity: FragmentActivity,
    feature: String,
): Boolean {
    if (hasAccessibilityServicePermission(activity)) return true
    Toast.makeText(
        activity,
        "Enable AutoInput accessibility for $feature to continue",
        Toast.LENGTH_LONG,
    ).show()
    activity.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    return false
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

fun requestCameraPermission(
    activity: FragmentActivity,
    requestCallback: OnPermissionCallback,
) {
    XXPermissions.with(activity).permission(
        Permission.CAMERA
    ).request(requestCallback)
}
