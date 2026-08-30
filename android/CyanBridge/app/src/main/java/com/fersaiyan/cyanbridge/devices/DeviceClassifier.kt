package com.fersaiyan.cyanbridge.devices

import android.os.ParcelUuid
import com.fersaiyan.cyanbridge.devices.eyevue.EyevueProtocol
import com.fersaiyan.cyanbridge.shared.devices.DeviceClass
import java.util.UUID

/**
 * Chapter 3 heuristics:
 * - Classify by advertised name (primary)
 * - Optionally use service UUIDs if available.
 */
object DeviceClassifier {

    private val TUNEBUDS_BLE_SERVICE_UUID: UUID =
        UUID.fromString("0000fdb3-0000-1000-8000-00805f9b34fb")
    private val MYVU_ADVERTISED_SERVICE_UUID: UUID =
        UUID.fromString("00000bd3-0000-1000-8000-00805f9b34fb")
    private val MYVU_GATT_SERVICE_UUID: UUID =
        UUID.fromString("00000bd1-0000-1000-8000-00805f9b34fb")
    private val MOYOUNG_SERVICE_UUID: UUID =
        UUID.fromString("0000fea8-0000-1000-8000-00805f9b34fb")
    private val TUNEBUDS_COMPANY_IDS = setOf(0x475A, 0x455A, 0x535A, 0x4D5A)

    fun guessDeviceClass(
        advertisedName: String?,
        serviceUuids: List<ParcelUuid> = emptyList(),
        manufacturerCompanyIds: Set<Int> = emptySet(),
        macAddress: String? = null,
    ): DeviceClass {
        val name = advertisedName?.trim().orEmpty()
        val lower = name.lowercase()

        // Eyevue devices may omit their local name while still advertising the
        // vendor service. Check the UUID before name-based fallbacks.
        if (serviceUuids.any { it.uuid == EyevueProtocol.SERVICE_UUID }) {
            return DeviceClass.EYEVUE
        }

        if (serviceUuids.any { it.uuid == TUNEBUDS_BLE_SERVICE_UUID }) {
            return DeviceClass.TUNEBUDS
        }

        if (serviceUuids.any { it.uuid == MYVU_ADVERTISED_SERVICE_UUID || it.uuid == MYVU_GATT_SERVICE_UUID }) {
            return DeviceClass.MEIZU_MYVU
        }

        if (serviceUuids.any { it.uuid == MOYOUNG_SERVICE_UUID }) {
            return DeviceClass.MOYOUNG_W620
        }

        if (manufacturerCompanyIds.any(TUNEBUDS_COMPANY_IDS::contains)) {
            return DeviceClass.TUNEBUDS
        }

        if (lower.contains("w620") || lower.contains("moyoung") || lower.contains("da echo")) {
            return DeviceClass.MOYOUNG_W620
        }

        if (macAddress?.uppercase()?.startsWith("DB:3E:33") == true) {
            return DeviceClass.MOYOUNG_W620
        }

        if (name.isEmpty()) return DeviceClass.UNKNOWN

        if (lower.contains("eyevue")) {
            return DeviceClass.EYEVUE
        }

        if (lower.contains("xk one") || lower.contains("tunebuds") || lower.contains("ab mate")) {
            return DeviceClass.TUNEBUDS
        }

        // HeyCyan-class heuristics (already used elsewhere in the app).
        if (
            lower.contains("heycyan") ||
            lower.contains("cyan") ||
            name.startsWith("O_") ||
            name.startsWith("Q_")
        ) {
            return DeviceClass.HEY_CYAN
        }

        // Meta Ray-Ban heuristics.
        if (
            lower.contains("ray-ban") ||
            lower.contains("rayban") ||
            (lower.contains("ray") && lower.contains("ban")) ||
            lower.contains("ray-ban meta") ||
            lower.contains("meta ray")
        ) {
            return DeviceClass.META_RAYBAN
        }

        // Meizu MYVU / Star Air (XGA010C) advertises as MYVU on the BLE link.
        if (lower.contains("myvu") || lower.contains("star air") || lower.contains("starair")) {
            return DeviceClass.MEIZU_MYVU
        }

        // Generic audio heuristics (best-effort).
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
