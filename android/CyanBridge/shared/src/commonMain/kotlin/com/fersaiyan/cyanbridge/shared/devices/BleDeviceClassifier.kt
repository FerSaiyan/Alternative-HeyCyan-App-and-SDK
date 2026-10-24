package com.fersaiyan.cyanbridge.shared.devices

/**
 * Platform-neutral port of the Android app's DeviceClassifier heuristics so iOS
 * classifies scan results the same way. UUIDs are compared as lowercase 128-bit
 * strings; 16-bit forms (CoreBluetooth reports "AA12") are expanded first.
 */
object BleDeviceClassifier {
    private const val EYEVUE_SERVICE_UUID = "0000aa12-0000-1000-8000-00805f9b34fb"
    private const val TUNEBUDS_BLE_SERVICE_UUID = "0000fdb3-0000-1000-8000-00805f9b34fb"
    private const val MYVU_ADVERTISED_SERVICE_UUID = "00000bd3-0000-1000-8000-00805f9b34fb"
    private const val MYVU_GATT_SERVICE_UUID = "00000bd1-0000-1000-8000-00805f9b34fb"
    private const val MOYOUNG_SERVICE_UUID = "0000fea8-0000-1000-8000-00805f9b34fb"
    private val TUNEBUDS_COMPANY_IDS = setOf(0x475A, 0x455A, 0x535A, 0x4D5A)

    fun normalizeUuid(uuid: String): String {
        val value = uuid.trim().lowercase()
        return when (value.length) {
            4 -> "0000$value-0000-1000-8000-00805f9b34fb"
            8 -> "$value-0000-1000-8000-00805f9b34fb"
            else -> value
        }
    }

    fun guessDeviceClass(
        advertisedName: String?,
        serviceUuids: List<String> = emptyList(),
        manufacturerCompanyIds: Set<Int> = emptySet(),
        address: String? = null,
        heyCyanServiceUuids: List<String> = emptyList(),
    ): DeviceClass {
        val name = advertisedName?.trim().orEmpty()
        val lower = name.lowercase()
        val uuids = serviceUuids.map(::normalizeUuid).toSet()
        val heyCyanUuids = heyCyanServiceUuids.map(::normalizeUuid)

        if (EYEVUE_SERVICE_UUID in uuids) return DeviceClass.EYEVUE
        if (TUNEBUDS_BLE_SERVICE_UUID in uuids) return DeviceClass.TUNEBUDS
        if (MYVU_ADVERTISED_SERVICE_UUID in uuids || MYVU_GATT_SERVICE_UUID in uuids) return DeviceClass.MEIZU_MYVU
        if (MOYOUNG_SERVICE_UUID in uuids) return DeviceClass.MOYOUNG_W620
        if (heyCyanUuids.any { it in uuids }) return DeviceClass.HEY_CYAN
        if (manufacturerCompanyIds.any(TUNEBUDS_COMPANY_IDS::contains)) return DeviceClass.TUNEBUDS

        if (lower.contains("w620") || lower.contains("moyoung") || lower.contains("da echo")) {
            return DeviceClass.MOYOUNG_W620
        }
        if (address?.uppercase()?.startsWith("DB:3E:33") == true) return DeviceClass.MOYOUNG_W620

        if (name.isEmpty()) return DeviceClass.UNKNOWN

        if (lower.contains("eyevue")) return DeviceClass.EYEVUE
        if (lower.contains("xk one") || lower.contains("tunebuds") || lower.contains("ab mate")) {
            return DeviceClass.TUNEBUDS
        }
        if (lower.contains("heycyan") || lower.contains("cyan") || name.startsWith("O_") || name.startsWith("Q_")) {
            return DeviceClass.HEY_CYAN
        }
        if (
            lower.contains("ray-ban") ||
            lower.contains("rayban") ||
            (lower.contains("ray") && lower.contains("ban")) ||
            lower.contains("meta ray")
        ) {
            return DeviceClass.META_RAYBAN
        }
        if (lower.contains("myvu") || lower.contains("star air") || lower.contains("starair")) {
            return DeviceClass.MEIZU_MYVU
        }
        if (
            lower.contains("airpods") ||
            lower.contains("headset") ||
            lower.contains("headphones") ||
            lower.contains("earbuds") ||
            lower.contains("buds")
        ) {
            return DeviceClass.GENERIC_AUDIO
        }
        return DeviceClass.UNKNOWN
    }
}
