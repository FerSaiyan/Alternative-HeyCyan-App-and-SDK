package com.fersaiyan.cyanbridge.ui

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.os.Build
import android.util.Log
import android.content.Context
import com.hjq.permissions.Permission
import com.hjq.permissions.XXPermissions
import com.oudmon.ble.base.bluetooth.BleOperateManager
import com.oudmon.ble.base.bluetooth.DeviceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Keeps the BLE side of the glasses connected while the app process is alive.
 *
 * The official HeyCyan app aggressively reconnects in the background; this is a
 * lightweight version that:
 * - reconnects to the last bound device address if present
 * - otherwise tries a best-effort fallback using already-bonded devices
 */
object AutoPairManager {
    private const val TAG = "AutoPair"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var started = false

    /**
     * When true, automatic reconnect attempts are disabled until the user manually
     * initiates pairing/reconnect again (or the app process is restarted).
     */
    @Volatile
    private var suppressAutoReconnect: Boolean = false

    fun setAutoReconnectSuppressed(suppressed: Boolean, reason: String) {
        suppressAutoReconnect = suppressed
        Log.i(TAG, "autoReconnectSuppressed=$suppressed ($reason)")
    }

    fun isAutoReconnectSuppressed(): Boolean = suppressAutoReconnect

    fun start(context: Context) {
        if (started) return
        started = true

        val appContext = context.applicationContext
        Log.i(TAG, "AutoPairManager started")

        // Immediate attempt on app startup.
        requestConnect(appContext, reason = "startup")

        // Periodic reconnect loop (only while process stays alive).
        scope.launch {
            var backoffMs = 5_000L
            while (isActive) {
                if (suppressAutoReconnect) {
                    // User explicitly disconnected; keep the list stable until manual reconnect.
                    delay(10_000L)
                    continue
                }

                val connected = BleOperateManager.getInstance().isConnected
                if (connected) {
                    backoffMs = 5_000L
                    delay(20_000L)
                    continue
                }

                val attempted = tryConnectOnce(appContext, reason = "loop")
                if (!attempted) {
                    // Nothing to connect to (no saved MAC / no permissions)
                    delay(30_000L)
                    continue
                }

                delay(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(60_000L)
            }
        }
    }

    fun requestConnect(context: Context, reason: String) {
        if (suppressAutoReconnect) {
            Log.d(TAG, "Skipping auto-pair ($reason): suppressed")
            return
        }
        val appContext = context.applicationContext
        scope.launch {
            tryConnectOnce(appContext, reason)
        }
    }

    fun requestConnectToMac(context: Context, mac: String, reason: String) {
        if (suppressAutoReconnect) {
            Log.d(TAG, "Skipping auto-pair ($reason): suppressed")
            return
        }
        val appContext = context.applicationContext
        scope.launch {
            tryConnectToMacOnce(appContext, mac, reason)
        }
    }

    private fun canReadBluetoothState(context: Context): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            XXPermissions.isGranted(context, Permission.BLUETOOTH_CONNECT)
    }

    private fun isBluetoothEnabled(): Boolean {
        return try {
            BluetoothAdapter.getDefaultAdapter()?.isEnabled == true
        } catch (e: Exception) {
            false
        }
    }

    private fun looksLikeGlasses(device: BluetoothDevice): Boolean {
        val name = device.name ?: return false
        return name.contains("HeyCyan", ignoreCase = true) ||
            name.contains("Cyan", ignoreCase = true) ||
            name.startsWith("O_") ||
            name.startsWith("Q_")
    }

    private fun getTargetMac(context: Context): String? {
        val saved = DeviceManager.getInstance().deviceAddress
        if (!saved.isNullOrBlank()) return saved

        if (!canReadBluetoothState(context)) return null
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return null
        val bonded = try {
            adapter.bondedDevices
        } catch (e: SecurityException) {
            null
        } ?: return null

        val candidate = bonded.firstOrNull { looksLikeGlasses(it) } ?: return null
        val mac = candidate.address

        // Best-effort: let the vendor SDK know what device to reconnect to.
        // Use reflection so we compile even if the SDK exposes this as read-only.
        try {
            val dm = DeviceManager.getInstance()
            val m = dm.javaClass.methods.firstOrNull {
                it.name == "setDeviceAddress" && it.parameterTypes.size == 1 && it.parameterTypes[0] == String::class.java
            }
            m?.invoke(dm, mac)
        } catch (_: Throwable) {
        }

        return mac
    }

    /**
     * @return true if we attempted a connection.
     */
    private fun tryConnectOnce(context: Context, reason: String): Boolean {
        if (suppressAutoReconnect) {
            Log.d(TAG, "Skipping auto-pair ($reason): suppressed")
            return false
        }
        if (!isBluetoothEnabled()) {
            Log.d(TAG, "Skipping auto-pair ($reason): Bluetooth disabled")
            return false
        }

        val mac = getTargetMac(context)
        if (mac.isNullOrBlank()) {
            Log.d(TAG, "Skipping auto-pair ($reason): no saved/bonded glasses MAC")
            return false
        }

        val mgr = BleOperateManager.getInstance()
        if (mgr.isConnected) return true

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            !XXPermissions.isGranted(context, Permission.BLUETOOTH_CONNECT)
        ) {
            Log.d(TAG, "Skipping auto-pair ($reason): missing BLUETOOTH_CONNECT")
            return false
        }

        Log.i(TAG, "Auto-pair ($reason): connectDirectly($mac)")
        try {
            mgr.reConnectMac = mac
        } catch (_: Throwable) {
            // Optional; some SDK builds may not expose this.
        }
        mgr.connectDirectly(mac)
        return true
    }

    private fun tryConnectToMacOnce(context: Context, mac: String, reason: String): Boolean {
        if (suppressAutoReconnect) {
            Log.d(TAG, "Skipping auto-pair ($reason): suppressed")
            return false
        }
        if (mac.isBlank()) return false
        if (!isBluetoothEnabled()) return false

        val mgr = BleOperateManager.getInstance()
        if (mgr.isConnected) return true

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            !XXPermissions.isGranted(context, Permission.BLUETOOTH_CONNECT)
        ) {
            return false
        }

        Log.i(TAG, "Auto-pair ($reason): connectDirectly($mac)")
        try {
            mgr.reConnectMac = mac
        } catch (_: Throwable) {
        }
        mgr.connectDirectly(mac)
        return true
    }
}
