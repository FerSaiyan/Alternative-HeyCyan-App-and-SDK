package com.fersaiyan.cyanbridge.lensmoo

import org.junit.Assert.*
import org.junit.Test

class LensMooPreviewGateTest {
    @Test fun closingDialogBeforeQueuedTaskPreventsConnectionAndCamera() {
        val gate=LensMooPreviewGate();val token=gate.request();gate.cancel()
        var calls=0
        assertFalse(gate.startIfActive(token,{calls++},{calls++}))
        assertEquals(0,calls)
    }
    @Test fun closingDialogDuringConnectionPreventsCameraStart() {
        val gate=LensMooPreviewGate();val token=gate.request();var started=false
        assertFalse(gate.startIfActive(token,{gate.cancel()},{started=true}))
        assertFalse(started)
    }
    @Test fun newPreviewInvalidatesOldTask() {
        val gate=LensMooPreviewGate();val old=gate.request();val current=gate.request()
        assertFalse(gate.isActive(old))
        assertTrue(gate.startIfActive(current,{},{}))
    }
}
