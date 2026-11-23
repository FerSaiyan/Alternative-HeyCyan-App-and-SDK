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
    fun hintFollowsTheFirmwareRules() {
        // log_mwqq8cyw: charging, 53 C, glasses reported Wi-Fi Direct up, phone saw no peer.
        val field = GlassesCondition(charging = true, temperatureCelsius = 53)
        assertNull(GlassesWifiStartHint.message(field, peerSeen = true, glassesWifiUp = true))
        val phoneSide = GlassesWifiStartHint.message(field, peerSeen = false, glassesWifiUp = true, peerName = "CY 01_32F8135584CA")!!
        assertTrue("\"CY 01_32F8135584CA\"" in phoneSide && "this phone did not find them" in phoneSide, phoneSide)
        // Charging and ordinary heat are never given as the cause (the firmware does not gate on them).
        assertTrue("charging" !in phoneSide && "cool" !in phoneSide, phoneSide)
        // The glasses stop a session at 107 C; from 100 C a cool-down hint.
        assertTrue("too hot (107 °C)" in GlassesWifiStartHint.message(GlassesCondition(temperatureCelsius = 107), false, true)!!)
        assertTrue("very hot (101 °C)" in GlassesWifiStartHint.message(GlassesCondition(temperatureCelsius = 101), false, true)!!)
        // No confirmation from the glasses: their Wi-Fi never started.
        assertTrue("Restart the glasses" in GlassesWifiStartHint.message(GlassesCondition(), false, glassesWifiUp = false)!!)
    }

    /** BR22 firmware: Wi-Fi start refused at <= 15 % while not charging; a session stops at <= 9 %. */
    @Test
    fun lowBatteryIsExplainedBeforeAndAfterASync() {
        val low = GlassesCondition(batteryPercent = 12, charging = false)
        assertEquals(12, GlassesWifiStartHint.batteryTooLowToStart(low))
        assertTrue("12 %" in GlassesWifiStartHint.lowBatteryBeforeSync(low)!!)
        val failed = GlassesWifiStartHint.message(low, peerSeen = false, glassesWifiUp = false)!!
        assertTrue("battery is at 12 %" in failed && "Restart" !in failed, failed)

        // Charging, unknown charging state treated as not charging, and the 15 % boundary.
        assertNull(GlassesWifiStartHint.lowBatteryBeforeSync(low.copy(charging = true)))
        assertEquals(15, GlassesWifiStartHint.batteryTooLowToStart(GlassesCondition(batteryPercent = 15)))
        assertNull(GlassesWifiStartHint.batteryTooLowToStart(GlassesCondition(batteryPercent = 16, charging = false)))
        assertNull(GlassesWifiStartHint.lowBatteryBeforeSync(GlassesCondition()))

        // Wi-Fi came up, then the battery ran down to the session limit.
        val ranDown = GlassesWifiStartHint.message(GlassesCondition(batteryPercent = 8, charging = false), false, glassesWifiUp = true)!!
        assertTrue("ran down to 8 %" in ranDown, ranDown)
        // Above the session limit with Wi-Fi up, the phone-side hint stays.
        assertTrue("did not find them" in GlassesWifiStartHint.message(GlassesCondition(batteryPercent = 12, charging = false), false, true)!!)
    }
}
