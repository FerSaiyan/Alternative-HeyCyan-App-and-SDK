package com.fersaiyan.cyanbridge.shared.devices.eyevue

import java.util.Calendar
import java.util.TimeZone
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals

/** The common date math must match the java.util.Calendar fields the Android app used to send. */
class EyevueSetTimeCalendarTest {
    @Test
    fun matchesCalendarFieldsAcrossZonesAndDstTransitions() {
        val original = TimeZone.getDefault()
        val random = Random(20261024)
        try {
            for (zone in listOf("UTC", "America/Sao_Paulo", "Europe/Berlin", "Asia/Kolkata", "Pacific/Chatham", "America/St_Johns")) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone))
                repeat(2_000) {
                    // 2000-01-01 .. 2099-12-31, the range the one-byte year field can carry.
                    val epochMillis = random.nextLong(946_684_800_000L, 4_102_444_800_000L)
                    val calendar = Calendar.getInstance().apply { timeInMillis = epochMillis }
                    assertContentEquals(
                        EyevueProtocol.buildSetTimePacket(
                            year = calendar.get(Calendar.YEAR),
                            month = calendar.get(Calendar.MONTH) + 1,
                            day = calendar.get(Calendar.DAY_OF_MONTH),
                            hour = calendar.get(Calendar.HOUR_OF_DAY),
                            minute = calendar.get(Calendar.MINUTE),
                            second = calendar.get(Calendar.SECOND),
                        ),
                        EyevueProtocol.buildSetTimePacket(epochMillis),
                        "zone=$zone epochMillis=$epochMillis",
                    )
                }
            }
        } finally {
            TimeZone.setDefault(original)
        }
    }
}
