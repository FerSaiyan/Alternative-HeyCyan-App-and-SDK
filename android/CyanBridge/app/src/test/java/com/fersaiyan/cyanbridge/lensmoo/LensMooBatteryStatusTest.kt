package com.fersaiyan.cyanbridge.lensmoo

import org.junit.Assert.*
import org.junit.Test

class LensMooBatteryStatusTest {
    @Test fun noReadingDoesNotImplyZeroBatteryOrHealthyConnection() {
        val status=LensMooBatteryStatus()
        assertNull(status.latest())
        assertFalse(status.isFresh(1000,true))
    }
    @Test fun validatedPercentageExpiresAndDisconnectImmediatelyMakesItStale() {
        val status=LensMooBatteryStatus()
        status.update(72,1000,5000)
        assertTrue(status.isFresh(31_000,true))
        assertFalse(status.isFresh(46_001,true))
        assertFalse(status.isFresh(1001,false))
        assertEquals(72,status.latest()!!.percent)
        assertEquals(5000L,status.latest()!!.wallMillis)
    }
    @Test fun reconnectAloneDoesNotRefreshOldReading() {
        val status=LensMooBatteryStatus()
        status.update(40,1000,1000)
        assertFalse(status.isFresh(90_000,true))
        status.update(41,90_000,90_000)
        assertTrue(status.isFresh(90_001,true))
        assertEquals(41,status.latest()!!.percent)
    }
    @Test(expected=IllegalArgumentException::class) fun outOfRangeValueIsNotStored() {
        LensMooBatteryStatus().update(255,1000,1000)
    }
}
