package com.fersaiyan.cyanbridge.lensmoo
import org.junit.Assert.*
import org.junit.Test
class LensMooBatteryTest {
    @Test fun supportsFullValidatedRange() {
        for(percent in 0..100) assertEquals(percent,LensMooBattery.percent(0,byteArrayOf(0,percent.toByte())))
    }
    @Test fun formatAndLengthAreValidatedBeforePercent() {
        assertThrows(IllegalArgumentException::class.java) { LensMooBattery.percent(2,byteArrayOf(0,42)) }
        assertThrows(IllegalArgumentException::class.java) { LensMooBattery.percent(0,byteArrayOf(42)) }
        assertThrows(IllegalArgumentException::class.java) { LensMooBattery.percent(0,byteArrayOf(0,255.toByte())) }
    }
}
