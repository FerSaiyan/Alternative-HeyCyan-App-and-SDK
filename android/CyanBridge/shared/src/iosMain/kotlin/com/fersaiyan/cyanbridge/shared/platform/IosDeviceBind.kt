package com.fersaiyan.cyanbridge.shared.platform

import com.fersaiyan.cyanbridge.shared.devices.DeviceClass
import com.fersaiyan.cyanbridge.shared.devices.ScannedDevice

/** Named devices first, strongest signal first, like the Android bind list. */
internal fun List<ScannedDevice>.upsert(
    identifier: String,
    name: String?,
    rssi: Int,
    detectedClass: DeviceClass,
): List<ScannedDevice> {
    val sanitizedName = name?.trim()?.takeIf { it.isNotEmpty() }
    val existing = firstOrNull { it.macAddress == identifier }
    val updated = existing?.copy(
        advertisedName = existing.advertisedName ?: sanitizedName,
        rssi = rssi,
        detectedClass = if (detectedClass != DeviceClass.UNKNOWN) detectedClass else existing.detectedClass,
    ) ?: ScannedDevice(
        macAddress = identifier,
        advertisedName = sanitizedName,
        rssi = rssi,
        detectedClass = detectedClass,
        selectedClass = null,
        userOverridden = false,
    )
    return (filterNot { it.macAddress == identifier } + updated)
        .sortedWith(compareBy<ScannedDevice> { it.advertisedName == null }.thenByDescending { it.rssi })
}
