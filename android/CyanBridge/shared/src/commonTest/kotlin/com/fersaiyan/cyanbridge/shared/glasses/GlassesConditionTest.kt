package com.fersaiyan.cyanbridge.shared.glasses

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GlassesConditionTest {
    private fun frame(type: Int, vararg payload: Int): ByteArray =
        ByteArray(6) + byteArrayOf(type.toByte()) + ByteArray(payload.size) { payload[it].toByte() }

    @Test
    fun batteryNotifyWithAndWithoutChargingByte() {
        assertEquals(GlassesConditionNotify.Battery(71, true), GlassesConditionNotify.battery(frame(0x05, 71, 1)))
        assertEquals(GlassesConditionNotify.Battery(80, false), GlassesConditionNotify.battery(frame(0x05, 80, 0)))
        // A short frame keeps the battery and leaves charging unknown instead of throwing.
        assertEquals(GlassesConditionNotify.Battery(42, null), GlassesConditionNotify.battery(frame(0x05, 42)))
        assertNull(GlassesConditionNotify.battery(frame(0x05)))
        assertNull(GlassesConditionNotify.battery(frame(0x0B, 50)))
    }

    @Test
    fun temperatureNotifyAsTheFieldLogSentIt() {
        // log_mwqq8cyw: loadData=-68,115,2,0,-58,-105,11,53
        val field = byteArrayOf(-68, 115, 2, 0, -58, -105, 11, 53)
        assertEquals(53, GlassesConditionNotify.temperatureCelsius(field))
        assertNull(GlassesConditionNotify.temperatureCelsius(frame(0x0B)))
        assertNull(GlassesConditionNotify.temperatureCelsius(frame(0x05, 71, 1)))
    }

    @Test
    fun staleValuesAreDropped() {
        val c = GlassesCondition(batteryPercent = 71, charging = true, temperatureCelsius = 53, batteryAtMs = 1_000, temperatureAtMs = 9_000)
        val fresh = c.freshAt(nowMs = 10_000, maxAgeMs = 5_000)
        assertNull(fresh.batteryPercent)
        assertNull(fresh.charging)
        assertEquals(53, fresh.temperatureCelsius)
    }

    @Test
    fun hintNamesChargingAndHeatOnlyWhenNoPeerAppeared() {
        val hotCharging = GlassesCondition(charging = true, temperatureCelsius = 53)
        assertNull(GlassesWifiStartHint.message(hotCharging, peerSeen = true))
        val msg = GlassesWifiStartHint.message(hotCharging, peerSeen = false)!!
        assertTrue("charging" in msg && "53 °C" in msg && "unplug" in msg, msg)
        assertTrue("charging" in GlassesWifiStartHint.message(GlassesCondition(charging = true, temperatureCelsius = 30), false)!!)
        assertTrue("47 °C" in GlassesWifiStartHint.message(GlassesCondition(charging = false, temperatureCelsius = 47), false)!!)
        assertTrue("Restart the glasses" in GlassesWifiStartHint.message(GlassesCondition(), false)!!)
    }
}
