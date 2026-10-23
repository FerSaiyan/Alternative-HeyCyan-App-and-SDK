package com.fersaiyan.cyanbridge.lensmoo

import org.junit.Assert.*
import org.junit.Test

class LensMooTurnGateTest {
    @Test fun repeatedButtonCannotOverlapAnyStageOfQuestion() {
        val gate = LensMooTurnGate()
        val token = gate.begin()!!
        assertNull(gate.begin())
        assertTrue(gate.advance(token, LensMooTurnGate.Phase.LISTENING, LensMooTurnGate.Phase.WORKING))
        assertNull(gate.begin())
        assertTrue(gate.advance(token, LensMooTurnGate.Phase.WORKING, LensMooTurnGate.Phase.SPEAKING))
        assertNull(gate.begin())
        assertTrue(gate.finish(token))
        assertNotNull(gate.begin())
    }
    @Test fun lateCompletionCannotReleaseAnotherQuestion() {
        val gate = LensMooTurnGate()
        val old = gate.begin()!!
        assertTrue(gate.finish(old))
        val current = gate.begin()!!
        assertFalse(gate.finish(old))
        assertTrue(gate.active(current))
        assertNull(gate.begin())
    }
    @Test fun staleTranscriptCannotStartCaptureAfterShutdown() {
        val gate = LensMooTurnGate()
        val token = gate.begin()!!
        gate.close()
        assertFalse(gate.advance(token, LensMooTurnGate.Phase.LISTENING, LensMooTurnGate.Phase.WORKING))
        assertFalse(gate.active(token))
        assertNull(gate.begin())
    }
    @Test fun duplicateRecognitionResultDoesNotCaptureTwice() {
        val gate = LensMooTurnGate()
        val token = gate.begin()!!
        assertTrue(gate.advance(token, LensMooTurnGate.Phase.LISTENING, LensMooTurnGate.Phase.WORKING))
        assertFalse(gate.advance(token, LensMooTurnGate.Phase.LISTENING, LensMooTurnGate.Phase.WORKING))
    }
}
