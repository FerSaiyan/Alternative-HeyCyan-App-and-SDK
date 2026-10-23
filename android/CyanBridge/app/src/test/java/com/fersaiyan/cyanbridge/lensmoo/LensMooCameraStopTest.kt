package com.fersaiyan.cyanbridge.lensmoo

import org.junit.Assert.*
import org.junit.Test

class LensMooCameraStopTest {
    @Test fun stateTwoDoesNotEndShutdownWaitBeforeLaterIdle() {
        var time=0L
        var reads=0
        val result=LensMooCameraStop.awaitState(
            readNext={ time++; if(reads++==0) 2 else 0 },
            now={time}, deadline=15L,
        )
        assertEquals(0,result)
        assertEquals(2,reads)
    }
    @Test fun persistentStateTwoWaitsUntilDeadlineBeforeRetry() {
        var time=0L
        val result=LensMooCameraStop.awaitState({time++;2},{time},15L)
        assertEquals(2,result)
        assertEquals(15L,time)
    }
    @Test fun missingStateIsNotTreatedAsIdle() {
        var time=0L
        assertThrows(IllegalStateException::class.java) {
            LensMooCameraStop.awaitState({time++;null},{time},15L)
        }
    }
    @Test fun confirmedIdleNeedsOneStop() {
        var calls=0
        LensMooCameraStop.confirm { calls++;0 }
        assertEquals(1,calls)
    }
    @Test fun nonIdleThenConfirmedIdleRecoversWithoutLosingPhoto() {
        var calls=0
        LensMooCameraStop.confirm { if(calls++==0) 2 else 0 }
        assertEquals(2,calls)
    }
    @Test fun nonIdleNeverCountsAsSuccessAndRetriesAreBounded() {
        var calls=0
        assertThrows(IllegalStateException::class.java) { LensMooCameraStop.confirm { calls++;2 } }
        assertEquals(2,calls)
    }
    @Test fun disconnectedTransportDoesNotRetry() {
        var calls=0
        assertThrows(IllegalStateException::class.java) { LensMooCameraStop.confirm { calls++;error("Disconnected") } }
        assertEquals(1,calls)
    }
}
