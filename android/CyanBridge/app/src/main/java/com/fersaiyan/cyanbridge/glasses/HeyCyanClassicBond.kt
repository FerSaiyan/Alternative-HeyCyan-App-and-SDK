package com.fersaiyan.cyanbridge.glasses

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.fersaiyan.cyanbridge.shared.diagnostics.DiagnosticEvents
import com.fersaiyan.cyanbridge.shared.diagnostics.DiagnosticSubsystem
import com.oudmon.ble.base.bluetooth.BleOperateManager

/**
 * The Classic Bluetooth (BR/EDR) bond that puts HeyCyan glasses in the phone's Bluetooth settings and lets Android
 * use them for calls and media (HFP/A2DP). CyanBridge's HeyCyan link is BLE only; the official app creates the bond
 * right after each BLE connection: its address doubles as the Classic one (JieLi dual-mode), it starts a Classic
 * scan one second later, and when the scan finds that address it bonds (vendor `createBondBluetoothJieLi`, BR/EDR),
 * retrying every 5 s until Android reports it bonded. Without this, glasses never paired with the official app had no
 * bond, so no audio. A media sync cancels the scan (Classic discovery slows Wi-Fi Direct).
 */
object HeyCyanClassicBond {
    private const val TAG = "HeyCyanClassicBond"
    private const val SCAN_DELAY_MS = 1_000L
    private const val SCAN_WINDOW_MS = 20_000L
    private const val RETRY_MS = 5_000L
    private const val MAX_BOND_ATTEMPTS = 3
    /** Android asks the user to approve the pairing; give them time once a bond request is out. */
    private const val CONSENT_WAIT_MS = 60_000L

    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var target: String? = null
    private var attempts = 0
    /** Android started pairing (BONDING): a later BOND_NONE is the user declining, not a request to repeat. */
    private var sawBonding = false
    private var appContext: Context? = null

    private val stopScan = Runnable {
        if (attempts == 0) {
            finish("scan window ended")
        } else {
            // A bond request is out (Android's "Pair?" prompt may be waiting): stop discovering, keep following it.
            appContext?.takeIf { hasPermission(it, connect = false) }
                ?.let { runCatching { BleOperateManager.getInstance().classicBluetoothStopScan() } }
            handler.postDelayed({ finish("no bond within ${CONSENT_WAIT_MS / 1000} s of the request") }, CONSENT_WAIT_MS)
        }
    }

    /** After a HeyCyan BLE connection to [address]: start the Classic scan once if the glasses are not bonded. */
    @SuppressLint("MissingPermission")
    fun onHeyCyanConnected(context: Context, address: String?) {
        if (address.isNullOrBlank() || target != null) return
        if (!hasPermission(context, connect = true) || !hasPermission(context, connect = false)) {
            Log.i(TAG, "Bluetooth permissions missing; no Classic bond attempt")
            return
        }
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return
        val device = runCatching { adapter.getRemoteDevice(address) }.getOrNull() ?: return
        if (device.bondState == BluetoothDevice.BOND_BONDED) {
            Log.i(TAG, "$address already bonded (Classic)")
            return
        }
        appContext = context.applicationContext
        target = address.uppercase()
        attempts = 0
        Log.i(TAG, "$address not bonded; Classic scan in ${SCAN_DELAY_MS} ms")
        DiagnosticEvents.info(DiagnosticSubsystem.BLE, "classic bond: scanning")
        handler.postDelayed({
            if (target != null) runCatching { BleOperateManager.getInstance().classicBluetoothStartScan() }
                .onFailure { Log.w(TAG, "Classic scan failed to start", it) }
        }, SCAN_DELAY_MS)
        handler.postDelayed(stopScan, SCAN_DELAY_MS + SCAN_WINDOW_MS)
    }

    /**
     * A Classic scan result (ACTION_FOUND). Bonds when it is the saved HeyCyan glasses ([savedAddress]), whether this
     * object or the user (legacy scan button) started the scan. True when handled.
     */
    @SuppressLint("MissingPermission")
    fun onDeviceFound(context: Context, device: BluetoothDevice, savedAddress: String?): Boolean {
        if (savedAddress.isNullOrBlank() || !device.address.equals(savedAddress, ignoreCase = true)) return false
        if (device.bondState != BluetoothDevice.BOND_NONE) return true
        appContext = context.applicationContext
        if (target == null) target = device.address.uppercase()
        requestBond(device)
        return true
    }

    @SuppressLint("MissingPermission")
    private fun requestBond(device: BluetoothDevice) {
        if (attempts >= MAX_BOND_ATTEMPTS) {
            finish("no bond after $attempts attempts")
            return
        }
        attempts++
        Log.i(TAG, "Bonding ${device.address} over Classic (attempt $attempts/$MAX_BOND_ATTEMPTS)")
        DiagnosticEvents.info(DiagnosticSubsystem.BLE, "classic bond: requested", mapOf("attempt" to attempts.toString()))
        // The vendor call bonds over BR/EDR through a hidden API; the public createBond() is the fallback (the
        // official app's own retry) when Android refuses the hidden one.
        runCatching { BleOperateManager.getInstance().createBondBluetoothJieLi(device) }
        handler.postDelayed({
            if (target == null || sawBonding || device.bondState == BluetoothDevice.BOND_BONDED) return@postDelayed
            if (device.bondState == BluetoothDevice.BOND_NONE) runCatching { device.createBond() }
            handler.postDelayed({
                if (target != null && !sawBonding && device.bondState == BluetoothDevice.BOND_NONE) requestBond(device)
            }, RETRY_MS)
        }, 1_500L)
    }

    /** ACTION_BOND_STATE_CHANGED: stop scanning once the glasses are bonded. */
    fun onBondStateChanged(device: BluetoothDevice, state: Int) {
        val wanted = target ?: return
        if (!device.address.equals(wanted, ignoreCase = true)) return
        when (state) {
            BluetoothDevice.BOND_BONDING -> sawBonding = true
            BluetoothDevice.BOND_BONDED -> {
                Log.i(TAG, "${device.address} bonded (Classic)")
                DiagnosticEvents.info(DiagnosticSubsystem.BLE, "classic bond: bonded")
                finish(null)
            }
            BluetoothDevice.BOND_NONE -> if (sawBonding) finish("pairing declined or failed")
        }
    }

    /** A media sync is starting, or the glasses disconnected: stop the Classic scan now. */
    fun cancel(reason: String) {
        if (target != null) finish(reason)
    }

    @SuppressLint("MissingPermission")
    private fun finish(reason: String?) {
        handler.removeCallbacksAndMessages(null)
        val context = appContext
        if (context != null && hasPermission(context, connect = false)) {
            runCatching { BleOperateManager.getInstance().classicBluetoothStopScan() }
        }
        reason?.let {
            Log.i(TAG, "Classic bond attempt stopped: $it")
            DiagnosticEvents.info(DiagnosticSubsystem.BLE, "classic bond: stopped", mapOf("reason" to it))
        }
        target = null
        attempts = 0
        sawBonding = false
    }

    /** Whether [address] has a Classic bond (support reports); null when unknown. */
    @SuppressLint("MissingPermission")
    fun isBonded(context: Context, address: String?): Boolean? {
        if (address.isNullOrBlank() || !hasPermission(context, connect = true)) return null
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return null
        return runCatching { adapter.getRemoteDevice(address).bondState == BluetoothDevice.BOND_BONDED }.getOrNull()
    }

    private fun hasPermission(context: Context, connect: Boolean): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val permission = if (connect) Manifest.permission.BLUETOOTH_CONNECT else Manifest.permission.BLUETOOTH_SCAN
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }
}
