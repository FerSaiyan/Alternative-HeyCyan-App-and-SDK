package com.fersaiyan.cyanbridge.ui.main

import android.widget.Toast
import com.fersaiyan.cyanbridge.ui.hasBluetooth
import com.fersaiyan.cyanbridge.ui.hasWifiP2pPermission
import com.fersaiyan.cyanbridge.ui.requestBluetoothPermission
import com.fersaiyan.cyanbridge.ui.requestWifiP2pPermission
import com.hjq.permissions.OnPermissionCallback
import com.hjq.permissions.XXPermissions

/**
 * Bluetooth and Wi-Fi Direct (Nearby devices/Location) permissions for glasses transports. The
 * Wi-Fi rationale is shown as a dashboard dialog before the system request.
 */
internal class GlassesTransportPermissions(host: MainScreenHost) : MainScreenController(host) {
    private var pendingTransportPermissionAction: (() -> Unit)? = null

    fun ensureBluetoothPermission(feature: String, onGranted: () -> Unit) {
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

    fun ensureGlassesTransportPermissions(feature: String, onGranted: () -> Unit) {
        ensureBluetoothPermission(feature) {
            if (hasWifiP2pPermission(activity)) {
                onGranted()
                return@ensureBluetoothPermission
            }

            // Show Material 3 Expressive dialog first (permission rationale) instead of silent Toast.
            // The dialog explains Wi-Fi Direct + Nearby devices/Location need for TuneBuds Detailed
            // (and similar Wi-Fi flows) and offers Grant/Cancel.
            pendingTransportPermissionAction = onGranted
            updateDashboardState { state ->
                state.copy(
                    showTransportPermissionDialog = true,
                    transportPermissionFeature = feature,
                )
            }
        }
    }

    fun requestPendingTransportPermission() {
        val onGranted = pendingTransportPermissionAction ?: return
        pendingTransportPermissionAction = null
        // Keep dialog visible state reset; request callback will handle success/denial UI.
        updateDashboardState { state -> state.copy(showTransportPermissionDialog = false) }
        requestWifiP2pPermission(activity, object : OnPermissionCallback {
            override fun onGranted(permissions: MutableList<String>, all: Boolean) {
                if (all) {
                    onGranted()
                } else {
                    // User granted via system sheet but not all - surface via dialog state + toast fallback
                    updateDashboardState { state ->
                        state.copy(
                            showTransportPermissionDialog = true,
                            transportPermissionFeature = dashboardState.transportPermissionFeature,
                        )
                    }
                    Toast.makeText(
                        activity,
                        "Nearby devices or Location permission is required",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }

            override fun onDenied(permissions: MutableList<String>, never: Boolean) {
                super.onDenied(permissions, never)
                updateDashboardState { state -> state.copy(showTransportPermissionDialog = false) }
                Toast.makeText(
                    activity,
                    "Nearby devices or Location permission is required",
                    Toast.LENGTH_LONG,
                ).show()
                if (never) {
                    XXPermissions.startPermissionActivity(activity, permissions)
                } else {
                    // Re-show rationale dialog so user can retry, instead of failing silently
                    pendingTransportPermissionAction = onGranted
                    updateDashboardState { state ->
                        state.copy(
                            showTransportPermissionDialog = true,
                            transportPermissionFeature = state.transportPermissionFeature,
                        )
                    }
                }
            }
        })
    }

    fun dismissTransportPermissionDialog() {
        pendingTransportPermissionAction = null
        updateDashboardState { state -> state.copy(showTransportPermissionDialog = false) }
    }
}
