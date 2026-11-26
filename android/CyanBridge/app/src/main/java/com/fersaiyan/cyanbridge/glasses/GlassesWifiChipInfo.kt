package com.fersaiyan.cyanbridge.glasses

/**
 * The HeyCyan glasses' Wi-Fi chip identifiers (V821 hardware + firmware, e.g. "WIFIAM01G1" / "1.00.28") from the
 * last syncDeviceInfo reply in this process, for support reports: field sync failures can then be replayed against
 * the same Wi-Fi firmware version on a test rig. Cleared on disconnect.
 */
object GlassesWifiChipInfo {
    @Volatile var hardwareVersion: String? = null
        private set
    @Volatile var firmwareVersion: String? = null
        private set

    fun record(hardware: String?, firmware: String?) {
        hardware?.trim()?.takeIf { it.isNotEmpty() }?.let { hardwareVersion = it }
        firmware?.trim()?.takeIf { it.isNotEmpty() }?.let { firmwareVersion = it }
    }

    fun clear() {
        hardwareVersion = null
        firmwareVersion = null
    }
}
