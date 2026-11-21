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
 * What to tell the user when the glasses accepted the media-sync command but the phone never saw their Wi-Fi Direct
 * peer. Seen in the field (log_mwqq8cyw, AM01CY 2.20.10): glasses on the charger heated from 43 to 53 C during the
 * attempt, no peer ever appeared, and they finally reported Wi-Fi error 255.
 */
object GlassesWifiStartHint {
    /** Temperatures at or above this are mentioned as a likely reason. */
    const val HOT_CELSIUS = 45

    /** Null when a peer was seen: the failure happened later and this hint does not apply. */
    fun message(condition: GlassesCondition, peerSeen: Boolean): String? {
        if (peerSeen) return null
        val temperature = condition.temperatureCelsius
        val hot = temperature != null && temperature >= HOT_CELSIUS
        val charging = condition.charging == true
        return when {
            charging && hot ->
                "The glasses' Wi-Fi did not start. They are charging and at $temperature °C: unplug them, let them " +
                    "cool for a few minutes, then sync again."
            charging ->
                "The glasses' Wi-Fi did not start while they are charging. Unplug them, then sync again."
            hot ->
                "The glasses' Wi-Fi did not start. They are at $temperature °C: let them cool for a few minutes, " +
                    "then sync again."
            else ->
                "The glasses' Wi-Fi did not start: the phone never saw it. Restart the glasses, then sync again."
        }
    }
}
