package com.fersaiyan.cyanbridge.shared.devices.eyevue

/** Whether a Wi-Fi Direct peer name belongs to the Eyevue glasses advertising [target]. */
fun matchesEyevuePeerName(target: String, deviceName: String?): Boolean {
    val name = deviceName?.trim().orEmpty()
    return target.isNotBlank() && name.isNotBlank() &&
        (name.contains(target, ignoreCase = true) || target.contains(name, ignoreCase = true))
}
