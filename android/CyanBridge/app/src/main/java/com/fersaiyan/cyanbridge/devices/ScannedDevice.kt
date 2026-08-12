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
    var detectedClass: DeviceClass = DeviceClassifier.guessDeviceClass(advertisedName, serviceUuids)
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
