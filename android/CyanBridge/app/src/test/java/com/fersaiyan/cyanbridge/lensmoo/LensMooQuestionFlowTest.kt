package com.fersaiyan.cyanbridge.lensmoo

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class LensMooQuestionFlowTest {
    @Test fun authenticationFailureDoesNotCaptureOrCallAi() = runBlocking {
        var captures=0; var calls=0
        try {
            LensMooQuestionFlow.run<Int,String>(
                freshSession={ throw IllegalStateException("auth failed") },
                captureOnce={ captures++; byteArrayOf(1) },
                answer={ calls++; "unexpected" })
            fail("Expected authentication error")
        } catch(expected: IllegalStateException) { assertEquals("auth failed",expected.message) }
        assertEquals(0,captures); assertEquals(0,calls)
    }
    @Test fun captureFailureIsNeverRetriedAndDoesNotCallAi() = runBlocking {
        var sessions=0; var captures=0; var calls=0
        try {
            LensMooQuestionFlow.run<Int,String>(
                freshSession={ ++sessions },
                captureOnce={ captures++; throw IllegalStateException("capture failed after shutter") },
                answer={ calls++; "unexpected" })
            fail("Expected capture error")
        } catch(expected: IllegalStateException) { assertTrue(expected.message!!.contains("capture failed")) }
        assertEquals(1,sessions); assertEquals(1,captures); assertEquals(0,calls)
    }
    @Test fun eachQuestionUsesNewSessionWhileKeepingWorkingGate() = runBlocking {
        val gate=LensMooTurnGate()
        var generation=0
        val captured=mutableListOf<Int>()
        repeat(2) {
            val token=gate.begin()!!
            gate.advance(token,LensMooTurnGate.Phase.LISTENING,LensMooTurnGate.Phase.WORKING)
            val result=LensMooQuestionFlow.run(
                freshSession={
                    assertTrue(gate.active(token))
                    assertNull(gate.begin())
                    ++generation
                },
                captureOnce={ session -> captured+=session; byteArrayOf(session.toByte()) },
                answer={ bytes -> assertEquals(LensMooTurnGate.Phase.WORKING,gate.phase); bytes[0].toInt() })
            assertEquals(generation,result)
            assertTrue(gate.finish(token))
            assertFalse(gate.active(token))
        }
        assertEquals(listOf(1,2),captured)
    }
    @Test fun stoppedTurnDuringReconnectDoesNotCapture() = runBlocking {
        val gate=LensMooTurnGate(); val token=gate.begin()!!
        gate.advance(token,LensMooTurnGate.Phase.LISTENING,LensMooTurnGate.Phase.WORKING)
        var captures=0
        try {
            LensMooQuestionFlow.run<Int,String>(
                freshSession={ gate.close(); check(gate.active(token)); 1 },
                captureOnce={ captures++; byteArrayOf(1) },
                answer={ "unexpected" })
            fail("Expected closed turn rejection")
        } catch(expected: IllegalStateException) { assertFalse(gate.active(token)) }
        assertEquals(0,captures)
    }
}
