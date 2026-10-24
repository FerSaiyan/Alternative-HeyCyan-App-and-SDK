package com.fersaiyan.cyanbridge.ui.main

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BatteryResponseParserTest {
    @Suppress("unused")
    private class FakeBatteryResponse(private val battery: Int, private val charging: Boolean)

    private class UnknownResponse {
        override fun toString(): String = "UnknownResponse"
    }

    @Test
    fun nullResponseIsReportedWithoutLevel() {
        assertEquals(
            BatteryResult(null, null, "Battery callback: null response"),
            BatteryResponseParser.parse(null),
        )
    }

    @Test
    fun readsPrivateBatteryAndChargingFields() {
        assertEquals(
            BatteryResult(87, true, "Battery: 87% (charging)"),
            BatteryResponseParser.parse(FakeBatteryResponse(87, true)),
        )
        assertEquals(
            BatteryResult(12, false, "Battery: 12% (not charging)"),
            BatteryResponseParser.parse(FakeBatteryResponse(12, false)),
        )
    }

    @Test
    fun unknownResponseFallsBackToItsText() {
        assertEquals(
            BatteryResult(null, null, "Battery: UnknownResponse"),
            BatteryResponseParser.parse(UnknownResponse()),
        )
    }
}
