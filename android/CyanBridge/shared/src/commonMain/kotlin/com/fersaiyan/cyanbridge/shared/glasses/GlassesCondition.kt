package com.fersaiyan.cyanbridge.shared.glasses

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * Battery, charging and temperature the HeyCyan glasses last reported over BLE (device notifies 0x05 and 0x0B),
 * with the time each value arrived. Media sync uses it to explain a Wi-Fi start that never happened.
 */
data class GlassesCondition(
    val batteryPercent: Int? = null,
    val charging: Boolean? = null,
    val temperatureCelsius: Int? = null,
    val batteryAtMs: Long = 0L,
    val temperatureAtMs: Long = 0L,
) {
    /** The same condition without values older than [maxAgeMs] at [nowMs]. */
    fun freshAt(nowMs: Long, maxAgeMs: Long): GlassesCondition {
        val batteryFresh = batteryAtMs > 0 && nowMs - batteryAtMs <= maxAgeMs
        val temperatureFresh = temperatureAtMs > 0 && nowMs - temperatureAtMs <= maxAgeMs
        return copy(
            batteryPercent = batteryPercent.takeIf { batteryFresh },
            charging = charging.takeIf { batteryFresh },
            temperatureCelsius = temperatureCelsius.takeIf { temperatureFresh },
        )
    }
}

/** The HeyCyan device notifies (cmd 0x73) that carry the glasses condition; the type is `loadData[6]`. */
object GlassesConditionNotify {
    const val TYPE_BATTERY = 0x05
    const val TYPE_TEMPERATURE = 0x0B

    data class Battery(val percent: Int, val charging: Boolean?)

    /** 0x05: battery percent at `loadData[7]`, charging flag at `loadData[8]` (absent on short frames). */
    fun battery(load: ByteArray): Battery? {
        if (typeOf(load) != TYPE_BATTERY) return null
        val percent = load.getOrNull(7)?.toInt()?.and(0xFF) ?: return null
        return Battery(percent, load.getOrNull(8)?.let { it.toInt() == 1 })
    }

    /** 0x0B: glasses temperature in degrees Celsius at `loadData[7]` (the official app shows it as such). */
    fun temperatureCelsius(load: ByteArray): Int? {
        if (typeOf(load) != TYPE_TEMPERATURE) return null
        return load.getOrNull(7)?.toInt()?.and(0xFF)
    }

    private fun typeOf(load: ByteArray): Int? = load.getOrNull(6)?.toInt()?.and(0xFF)
}

/** Process-wide holder for the latest [GlassesCondition]. */
object GlassesConditionState {
    private val lock = SynchronizedObject()
    private var current = GlassesCondition()

    fun onBattery(percent: Int, charging: Boolean?, nowMs: Long) = synchronized(lock) {
        current = current.copy(batteryPercent = percent, charging = charging ?: current.charging, batteryAtMs = nowMs)
    }

    fun onTemperature(celsius: Int, nowMs: Long) = synchronized(lock) {
        current = current.copy(temperatureCelsius = celsius, temperatureAtMs = nowMs)
    }

    fun snapshot(): GlassesCondition = synchronized(lock) { current }

    fun clear() = synchronized(lock) { current = GlassesCondition() }
}

/**
 * What to tell the user when a HeyCyan media sync never saw the glasses' Wi-Fi Direct peer.
 *
 * Firmware facts (firmware_dump add.236, BR22 AM01CY 2.00.10 executed in the pi32v2 emulator; same rules in the V2.2
 * builds around the field build 2.20.10):
 * - The transfer-mode reply carrying the peer name (`dataType 1`, `errorCode 1`) is sent only after the Wi-Fi chip
 *   reported that Wi-Fi Direct is up; the glasses then wait up to 119 s for the phone.
 * - Charging never blocks the Wi-Fi start; only low battery while not charging does (start <= 15 %, session <= 9 %).
 * - The 0x0B temperature is the Wi-Fi chip's CPU; the glasses stop a session at >= 107 C (throttling from 70 C).
 * - `09 ff 02` is a watchdog: about 32 s after Wi-Fi Direct came up nothing connected.
 * In log_mwqq8cyw the glasses were up as "CY 01_..." and the phone never found them, so the hint points at the phone.
 */
object GlassesWifiStartHint {
    /** The glasses stop a running session at this temperature (BR22 aglink case 0x13). */
    const val STOP_CELSIUS = 107

    /** From here a "let them cool" hint is worth giving (the Wi-Fi chip throttles from 70 C, shuts down at 110 C). */
    const val HOT_CELSIUS = 100

    /**
     * Null when a peer was seen (the failure happened later). [glassesWifiUp]: the glasses confirmed Wi-Fi Direct is
     * up (their transfer-mode reply), advertising [peerName].
     */
    fun message(
        condition: GlassesCondition,
        peerSeen: Boolean,
        glassesWifiUp: Boolean,
        peerName: String? = null,
    ): String? {
        if (peerSeen) return null
        val temperature = condition.temperatureCelsius
        return when {
            temperature != null && temperature >= STOP_CELSIUS ->
                "The glasses stopped Wi-Fi Direct because they are too hot ($temperature °C). Let them cool, then sync again."
            temperature != null && temperature >= HOT_CELSIUS ->
                "The glasses are very hot ($temperature °C) and may stop Wi-Fi Direct. Let them cool, then sync again."
            glassesWifiUp -> {
                val name = peerName?.takeIf { it.isNotBlank() }?.let { " as \"$it\"" }.orEmpty()
                "The glasses started Wi-Fi Direct$name, but this phone did not find them. Turn the phone's Wi-Fi " +
                    "off and on, keep it close to the glasses, then sync again."
            }
            else ->
                "The glasses' Wi-Fi did not start: the phone never saw it. Restart the glasses, then sync again."
        }
    }
}
