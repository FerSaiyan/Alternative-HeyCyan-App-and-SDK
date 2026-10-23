package com.fersaiyan.cyanbridge.lensmoo
import org.junit.Assert.*
import org.junit.Test
class LensMooVoiceButtonTest {
    @Test fun idleCameraNeverActsAsVoiceKey() {
        val gate=LensMooVoiceButton()
        assertFalse(gate.accept("5710",0,1,"READY",0))
        assertFalse(gate.accept("C002",0,0,"READY",0))
        assertFalse(gate.accept("C001",1,0,"READY",0))
        assertFalse(gate.accept("C001",0,1,"READY",0))
        assertTrue(gate.accept("C001",0,0,"READY",0))
    }
    @Test fun debounceDoesNotSuppressFuturePress() {
        val gate=LensMooVoiceButton()
        assertTrue(gate.accept("C001",0,0,"PHOTO_PENDING",1000))
        assertFalse(gate.accept("C001",0,0,"READY",1200))
        assertTrue(gate.accept("C001",0,0,"READY",2600))
    }
    @Test fun ownCameraOperationsAndClosedStateNeverTriggerVoice() {
        for(state in listOf("STOPPING","CAPTURING","CLOSED","STARTING")) {
            assertFalse(LensMooVoiceButton().accept("C001",0,0,state,1000))
        }
    }
}
