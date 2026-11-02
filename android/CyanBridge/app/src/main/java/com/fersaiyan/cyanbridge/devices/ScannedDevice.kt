package com.fersaiyan.cyanbridge.devices

import android.os.ParcelUuid
import com.fersaiyan.cyanbridge.shared.devices.DeviceClass

/**
 * UI model for a discovered Bluetooth device.
 */
class ScannedDevice(
    val macAddress: String,
    var advertisedName: String?,
    var rssi: Int,
    var serviceUuids: List<ParcelUuid> = emptyList(),
) {
    var connectionAddress: String = macAddress

    /** Other addresses the same unit was seen with, e.g. a TuneBuds BLE advert next to its Classic row. */
    private val otherAddresses = mutableSetOf<String>()

    fun hasAddress(address: String): Boolean =
        address.equals(macAddress, ignoreCase = true) ||
            address.equals(connectionAddress, ignoreCase = true) ||
            address.uppercase() in otherAddresses

    fun addAddress(address: String) {
        if (!hasAddress(address)) otherAddresses += address.uppercase()
    }

    /** [macAddress], [connectionAddress] and every other address this row answers to. */
    fun addresses(): Set<String> = otherAddresses + macAddress.uppercase() + connectionAddress.uppercase()

    var detectedClass: DeviceClass = DeviceClassifier.guessDeviceClass(
        advertisedName,
        serviceUuids,
        macAddress = macAddress,
    )
        private set

    /**
     * If [userSelectedClass] is null, the UI uses [detectedClass].
     */
    var userSelectedClass: DeviceClass? = null

    fun effectiveSelectedClass(): DeviceClass = userSelectedClass ?: detectedClass

    fun setDetectedClass(newDetected: DeviceClass) {
        detectedClass = newDetected
    }

    fun userOverridden(): Boolean = userSelectedClass != null

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ScannedDevice) return false
        return macAddress.equals(other.macAddress, ignoreCase = true)
    }

    override fun hashCode(): Int = macAddress.uppercase().hashCode()
}

/**
 * The row for a unit seen as [address] (plus, for a TuneBuds BLE advert, the Classic
 * [connectionAddress] from its manufacturer data), or null when no row knows either. Rows that
 * turn out to be the same unit (a bonded or Classic-inquiry row and an advert row) fold into the
 * first one, which then answers to all their addresses, so later scans that carry only the BLE
 * address update it instead of adding a row.
 */
fun MutableList<ScannedDevice>.mergeRowFor(address: String, connectionAddress: String?): ScannedDevice? {
    val keys = listOfNotNull(address, connectionAddress)
    val matching = filter { row -> keys.any(row::hasAddress) }
    val row = matching.firstOrNull() ?: return null
    matching.drop(1).forEach { duplicate ->
        removeAll { it === duplicate }
        duplicate.addresses().forEach(row::addAddress)
    }
    keys.forEach(row::addAddress)
    connectionAddress?.let { row.connectionAddress = it }
    return row
}
