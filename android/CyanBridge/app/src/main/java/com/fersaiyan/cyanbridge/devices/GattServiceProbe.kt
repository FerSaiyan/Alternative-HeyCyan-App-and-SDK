package com.fersaiyan.cyanbridge.devices

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.util.Log
import com.fersaiyan.cyanbridge.shared.devices.BleDeviceClassifier
import com.fersaiyan.cyanbridge.shared.devices.DeviceClass
import com.fersaiyan.cyanbridge.shared.diagnostics.DiagnosticEvents
import com.fersaiyan.cyanbridge.shared.diagnostics.DiagnosticSubsystem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Pairing-time protocol check: a short BLE GATT connection that lists the glasses' services, so the family chosen by
 * name can be checked against the protocol the glasses really speak (log_mwrbx20e "M89-S-0395" was set to HeyCyan
 * by hand, had no HeyCyan service, and every command was dropped). The link is always disconnected and closed before
 * the family's own manager connects.
 */
object GattServiceProbe {
    data class Result(val address: String, val services: List<String>, val detected: DeviceClass?)

    /** The last probe in this process, for support reports. */
    @Volatile var last: Result? = null
        private set

    /**
     * The address being probed, until its link is closed and settled. The probe's own ACL connect/disconnect must not
     * start the app's auto-connect (BluetoothReceiver): that connected the HeyCyan SDK while the pairing screen was
     * still open, the dashboard missed the connected event and showed the glasses as disconnected.
     */
    @Volatile private var probingAddress: String? = null

    fun isProbing(address: String?): Boolean = address != null && address.equals(probingAddress, ignoreCase = true)

    private const val TAG = "GattServiceProbe"
    private const val TIMEOUT_MS = 8_000L
    /** Some stacks refuse an immediate reconnect to a device whose link was just closed. */
    private const val SETTLE_AFTER_CLOSE_MS = 1_500L

    /** Null when the glasses could not be reached or listed in time (the caller then connects as chosen). */
    @SuppressLint("MissingPermission")
    suspend fun probe(context: Context, address: String): Result? {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return null
        val device = runCatching { adapter.getRemoteDevice(address) }.getOrNull() ?: return null
        probingAddress = address
        try {
            return probeLocked(context, device, address)
        } finally {
            probingAddress = null
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun probeLocked(context: Context, device: BluetoothDevice, address: String): Result? {
        val services = CompletableDeferred<List<String>?>()
        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                when {
                    newState == BluetoothProfile.STATE_CONNECTED -> if (!gatt.discoverServices()) services.complete(null)
                    newState == BluetoothProfile.STATE_DISCONNECTED -> services.complete(null)
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                services.complete(
                    if (status == BluetoothGatt.GATT_SUCCESS) gatt.services.map { it.uuid.toString().lowercase() } else null,
                )
            }
        }
        val gatt = try {
            device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        } catch (error: Exception) {
            Log.w(TAG, "connectGatt failed for $address", error)
            null
        } ?: return null
        val listed = try {
            withTimeoutOrNull(TIMEOUT_MS) { services.await() }
        } finally {
            runCatching { gatt.disconnect() }
            runCatching { gatt.close() }
        }
        delay(SETTLE_AFTER_CLOSE_MS)
        if (listed == null) {
            Log.w(TAG, "No service list from $address within ${TIMEOUT_MS} ms")
            DiagnosticEvents.warn(DiagnosticSubsystem.BLE, "pairing protocol check failed", mapOf("reason" to "no service list"))
            return null
        }
        val detected = BleDeviceClassifier.classifyByGattServices(listed)
        Log.i(TAG, "Glasses $address services=$listed detected=${detected ?: "none"}")
        return Result(address, listed, detected).also { last = it }
    }
}
