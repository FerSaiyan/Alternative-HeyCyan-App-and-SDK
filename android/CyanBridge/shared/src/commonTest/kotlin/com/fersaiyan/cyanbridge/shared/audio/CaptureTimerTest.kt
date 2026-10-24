package com.fersaiyan.cyanbridge.shared.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CaptureTimerTest {

    @Test
    fun computeStopAtMs_nullOrNonPositive() {
        assertNull(CaptureTimer.computeStopAtMs(1000L, null))
        assertNull(CaptureTimer.computeStopAtMs(1000L, 0L))
        assertNull(CaptureTimer.computeStopAtMs(1000L, -5L))
    }

    @Test
    fun computeStopAtMs_positive() {
        assertEquals(6000L, CaptureTimer.computeStopAtMs(1000L, 5L))
    }

    @Test
    fun isExpired_behavior() {
        val stopAt = 10_000L
        assertFalse(CaptureTimer.isExpired(9_999L, stopAt))
        assertTrue(CaptureTimer.isExpired(10_000L, stopAt))
        assertTrue(CaptureTimer.isExpired(10_001L, stopAt))
        assertFalse(CaptureTimer.isExpired(10_001L, null))
    }

    @Test
    fun remainingMs_behavior() {
        val stopAt = 10_000L
        assertEquals(1L, CaptureTimer.remainingMs(9_999L, stopAt))
        assertEquals(0L, CaptureTimer.remainingMs(10_000L, stopAt))
        assertEquals(-1L, CaptureTimer.remainingMs(10_001L, stopAt))
        assertNull(CaptureTimer.remainingMs(10_001L, null))
    }
}
