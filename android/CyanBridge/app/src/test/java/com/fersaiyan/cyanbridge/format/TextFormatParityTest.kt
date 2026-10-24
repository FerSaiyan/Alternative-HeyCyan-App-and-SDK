package com.fersaiyan.cyanbridge.format

import com.fersaiyan.cyanbridge.shared.format.formatFixed
import com.fersaiyan.cyanbridge.shared.format.formatLocalDate
import com.fersaiyan.cyanbridge.shared.format.formatLocalDateTime
import com.fersaiyan.cyanbridge.shared.format.formatLocalMonthDayTime
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Test

/** The shared formatters replace String.format and SimpleDateFormat on screens; their text must not change. */
class TextFormatParityTest {
    @Test
    fun fixedDecimalsMatchJavaFormatter() {
        val random = Random(42)
        val values = listOf(0.0, -0.0, 0.005, 0.015, 1.005, 2.675, 9.995, 99.95, 1e-7, 123456789.125, -0.04, -12.345) +
            List(2_000) { random.nextDouble(0.0, 40.0) } +
            List(500) { random.nextLong(0L, 80_000_000_000L) / 1_073_741_824.0 }
        for (value in values) {
            for (decimals in 1..2) {
                assertEquals("$value", String.format(Locale.US, "%.${decimals}f", value), formatFixed(value, decimals))
            }
            assertEquals("$value", String.format(Locale.US, "%+.1f", value), formatFixed(value, 1, plusSign = true))
        }
    }

    @Test
    fun localDatesMatchSimpleDateFormat() {
        val original = TimeZone.getDefault()
        try {
            for (zone in listOf("UTC", "America/Sao_Paulo", "Asia/Kolkata", "Pacific/Auckland")) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone))
                val random = Random(zone.hashCode())
                repeat(500) {
                    val millis = random.nextLong(0L, 4_102_444_800_000L)
                    val date = Date(millis)
                    assertEquals(SimpleDateFormat("yyyy-MM-dd", Locale.US).format(date), formatLocalDate(millis))
                    assertEquals(SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(date), formatLocalDateTime(millis))
                    assertEquals(SimpleDateFormat("MMM d, HH:mm", Locale.US).format(date), formatLocalMonthDayTime(millis))
                }
            }
        } finally {
            TimeZone.setDefault(original)
        }
    }
}
