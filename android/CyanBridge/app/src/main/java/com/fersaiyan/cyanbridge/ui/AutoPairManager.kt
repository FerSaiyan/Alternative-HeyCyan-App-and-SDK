package com.fersaiyan.cyanbridge.ui

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.fersaiyan.cyanbridge.devices.DeviceProfileStore
import com.fersaiyan.cyanbridge.devices.eyevue.EyevueManager
import com.fersaiyan.cyanbridge.devices.meizumyvu.MeizuMyvuManager
import com.fersaiyan.cyanbridge.devices.moyoung.MoyoungW620Manager
import com.fersaiyan.cyanbridge.devices.tunebuds.TuneBudsManager
import com.fersaiyan.cyanbridge.shared.devices.DeviceClass
import com.hjq.permissions.Permission
import com.hjq.permissions.XXPermissions
import com.oudmon.ble.base.bluetooth.BleOperateManager
import com.oudmon.ble.base.bluetooth.DeviceManager
import com.oudmon.ble.base.communication.LargeDataHandler
import com.oudmon.ble.base.communication.utils.ByteUtil
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
 * - reapplies user-hidden maximum capture-duration defaults after reconnect
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

                val selectedClass = DeviceProfileStore.selectedClass(appContext)
                val connected = BleOperateManager.getInstance().isConnected
                if (selectedClass == DeviceClass.EYEVUE) {
                    if (EyevueManager.getInstance(appContext).isConnected()) {
                        backoffMs = 5_000L
                        delay(20_000L)
                        continue
                    }
                }
                if (selectedClass == DeviceClass.TUNEBUDS) {
                    if (TuneBudsManager.getInstance(appContext).isConnected()) {
                        backoffMs = 5_000L
                        delay(20_000L)
                        continue
                    }
                }
                if (selectedClass == DeviceClass.MOYOUNG_W620) {
                    if (MoyoungW620Manager.getInstance(appContext).isConnected()) {
                        backoffMs = 5_000L
                        delay(20_000L)
                        continue
                    }
                }
                if (connected && selectedClass == DeviceClass.HEY_CYAN) {
                    scheduleHeyCyanMaximumDurations()
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
        if (DeviceProfileStore.selectedClass(context) == DeviceClass.EYEVUE) {
            if (suppressAutoReconnect) {
                Log.d(TAG, "Skipping Eyevue reconnect ($reason): suppressed")
                return
            }
            connectEyevueWithMaximumDuration(context, mac, null)
            return
        }
        if (DeviceProfileStore.selectedClass(context) == DeviceClass.TUNEBUDS) {
            if (suppressAutoReconnect) {
                Log.d(TAG, "Skipping TuneBuds reconnect ($reason): suppressed")
                return
            }
            TuneBudsManager.getInstance(context).connect(mac)
            return
        }
        if (DeviceProfileStore.selectedClass(context) == DeviceClass.MOYOUNG_W620) {
            if (suppressAutoReconnect) {
                Log.d(TAG, "Skipping MoYoung reconnect ($reason): suppressed")
                return
            }
            MoyoungW620Manager.getInstance(context).connect(mac)
            return
        }
        if (DeviceProfileStore.isMetaSelected(context) || DeviceProfileStore.isMeizuMyvuSelected(context)) {
            Log.d(TAG, "Skipping vendor reconnect for selected non-HeyCyan glasses ($reason)")
            return
        }
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
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
    }

    private fun isBluetoothEnabled(): Boolean {
        return try {
            BluetoothAdapter.getDefaultAdapter()?.isEnabled == true
        } catch (e: Exception) {
            false
        }
    }

    private fun looksLikeGlasses(context: Context, device: BluetoothDevice): Boolean {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) !=
                PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        val name = device.name ?: return false
        return name.contains("HeyCyan", ignoreCase = true) ||
            name.contains("Cyan", ignoreCase = true) ||
            name.startsWith("O_") ||
            name.startsWith("Q_") ||
            name.contains("W620", ignoreCase = true) ||
            name.contains("MoYoung", ignoreCase = true)
    }

    private fun getTargetMac(context: Context): String? {
        // Pairing persists the user's explicit selection independently of the vendor SDK.
        // Prefer it so devices with names outside our fallback heuristics still reconnect.
        val profile = DeviceProfileStore.loadLastSelected(context)
        if (profile?.selectedClass == DeviceClass.EYEVUE) {
            profile.macAddress.takeIf { it.isNotBlank() }?.let {
                connectEyevueWithMaximumDuration(context, it, profile.advertisedName)
            }
            return null
        }
        if (profile?.selectedClass == DeviceClass.MOYOUNG_W620) {
            profile.macAddress.takeIf { it.isNotBlank() }?.let {
                MoyoungW620Manager.getInstance(context).connect(it, profile.advertisedName)
            }
            return null
        }
        if (profile?.selectedClass == DeviceClass.META_RAYBAN) {
            // Meta owns its transport through DAT. Never hand its Bluetooth identity to
            // the Oudmon connector, even when the device is also visible to Android BLE.
            Log.d(TAG, "Skipping vendor reconnect for selected Meta Ray-Ban")
            return null
        }
        val profileMac = profile
            ?.macAddress
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        if (profileMac != null) {
            updateSdkReconnectMac(profileMac)
            return profileMac
        }

        val saved = DeviceManager.getInstance().deviceAddress
        if (!saved.isNullOrBlank()) return saved

        if (!canReadBluetoothState(context)) return null
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return null
        val bonded = try {
            adapter.bondedDevices
        } catch (e: SecurityException) {
            null
        } ?: return null

        val candidate = bonded.firstOrNull { looksLikeGlasses(context, it) } ?: return null
        val mac = candidate.address

        updateSdkReconnectMac(mac)

        return mac
    }

    /** Best-effort bridge for SDK builds where the saved address is writable. */
    private fun updateSdkReconnectMac(mac: String) {
        try {
            val dm = DeviceManager.getInstance()
            val method = dm.javaClass.methods.firstOrNull {
                it.name == "setDeviceAddress" &&
                    it.parameterTypes.size == 1 &&
                    it.parameterTypes[0] == String::class.java
            }
            method?.invoke(dm, mac)
        } catch (_: Throwable) {
            // Some vendor SDK builds expose the address as read-only.
        }
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

        val profile = DeviceProfileStore.loadLastSelected(context)
        if (profile?.selectedClass == DeviceClass.MEIZU_MYVU) {
            val address = profile.macAddress.takeIf { it.isNotBlank() } ?: return false
            val manager = MeizuMyvuManager.getInstance(context)
            if (manager.isReady()) return true
            if (!manager.shouldRequestConnection()) {
                Log.d(TAG, "Auto-pair ($reason): MYVU transport or upstream retry already active")
                return true
            }
            Log.i(TAG, "Auto-pair ($reason): starting MYVU foreground connection")
            manager.connect(address)
            return true
        }
        if (profile?.selectedClass == DeviceClass.TUNEBUDS) {
            val address = profile.macAddress.takeIf { it.isNotBlank() } ?: return false
            Log.i(TAG, "Auto-pair ($reason): TuneBuds RFCOMM $address")
            TuneBudsManager.getInstance(context).connect(address, profile.advertisedName)
            return true
        }
        if (profile?.selectedClass == DeviceClass.MOYOUNG_W620) {
            val address = profile.macAddress.takeIf { it.isNotBlank() } ?: return false
            Log.i(TAG, "Auto-pair ($reason): MoYoung BLE $address")
            MoyoungW620Manager.getInstance(context).connect(address, profile.advertisedName)
            return true
        }

        val mac = getTargetMac(context)
        if (mac.isNullOrBlank()) {
            Log.d(TAG, "Skipping auto-pair ($reason): no saved/bonded glasses MAC")
            return false
        }

        val mgr = BleOperateManager.getInstance()
        if (mgr.isConnected) {
            if (profile?.selectedClass == DeviceClass.HEY_CYAN) scheduleHeyCyanMaximumDurations()
            return true
        }

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
        if (profile?.selectedClass == DeviceClass.HEY_CYAN) scheduleHeyCyanMaximumDurations()
        return true
    }

    private fun tryConnectToMacOnce(context: Context, mac: String, reason: String): Boolean {
        if (suppressAutoReconnect) {
            Log.d(TAG, "Skipping auto-pair ($reason): suppressed")
            return false
        }
        if (mac.isBlank()) return false
        if (!isBluetoothEnabled()) return false

        if (DeviceProfileStore.selectedClass(context) == DeviceClass.EYEVUE) {
            connectEyevueWithMaximumDuration(context, mac, null)
            return true
        }
        if (DeviceProfileStore.selectedClass(context) == DeviceClass.TUNEBUDS) {
            TuneBudsManager.getInstance(context).connect(mac)
            return true
        }
        if (DeviceProfileStore.selectedClass(context) == DeviceClass.MOYOUNG_W620) {
            MoyoungW620Manager.getInstance(context).connect(mac)
            return true
        }

        val mgr = BleOperateManager.getInstance()
        if (mgr.isConnected) {
            if (DeviceProfileStore.selectedClass(context) == DeviceClass.HEY_CYAN) {
                scheduleHeyCyanMaximumDurations()
            }
            return true
        }

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
        if (DeviceProfileStore.selectedClass(context) == DeviceClass.HEY_CYAN) {
            scheduleHeyCyanMaximumDurations()
        }
        return true
    }

    private fun connectEyevueWithMaximumDuration(context: Context, mac: String, name: String?) {
        val manager = EyevueManager.getInstance(context)
        manager.connect(mac, name)
        scope.launch {
            repeat(60) {
                if (manager.isConnected()) {
                    manager.setRecordingDuration(EYEVUE_MAX_RECORDING_SECONDS)
                    Log.i(TAG, "EyeVue recording duration defaulted to ${EYEVUE_MAX_RECORDING_SECONDS}s")
                    return@launch
                }
                delay(100L)
            }
        }
    }

    private fun scheduleHeyCyanMaximumDurations() {
        scope.launch {
            val manager = BleOperateManager.getInstance()
            repeat(60) {
                if (manager.isConnected) {
                    setHeyCyanCaptureDuration(dataType = 0x02, seconds = HEY_CYAN_MAX_VIDEO_SECONDS)
                    delay(150L)
                    setHeyCyanCaptureDuration(dataType = 0x06, seconds = HEY_CYAN_MAX_AUDIO_SECONDS)
                    return@launch
                }
                delay(100L)
            }
            Log.w(TAG, "HeyCyan maximum capture durations were not applied: connection timed out")
        }
    }

    private fun setHeyCyanCaptureDuration(dataType: Int, seconds: Int) {
        if (!BleOperateManager.getInstance().isConnected) return
        val command = byteArrayOf(
            0x02,
            dataType.toByte(),
            0x00,
            ByteUtil.loword(seconds).toByte(),
            ByteUtil.hiword(seconds).toByte(),
        )
        LargeDataHandler.getInstance().glassesControl(command) { _, response ->
            Log.i(TAG, "Applied HeyCyan maximum capture duration type=$dataType seconds=$seconds response=${response.dataType}")
        }
    }

    private const val HEY_CYAN_MAX_VIDEO_SECONDS = 720
    private const val HEY_CYAN_MAX_AUDIO_SECONDS = 7_200
    private const val EYEVUE_MAX_RECORDING_SECONDS = 600
}
