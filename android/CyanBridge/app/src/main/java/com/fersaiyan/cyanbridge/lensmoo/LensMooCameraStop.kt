package com.fersaiyan.cyanbridge.lensmoo

/** Acknowledgement of the command is insufficient: require a device idle event. */
internal object LensMooCameraStop {
    fun awaitState(readNext: () -> Int?, now: () -> Long, deadline: Long): Int {
        var lastState:Int?=null
        while(now()<deadline) {
            val state=readNext() ?: continue
            if(state==0) return 0
            check(state==2) { "Camera stop returned unknown state" }
            lastState=state
        }
        return lastState ?: error("Camera state timeout: 5710")
    }
    fun confirm(stopAndReadState: () -> Int) {
        repeat(2) {
            val state=stopAndReadState()
            if(state==0) return
            // Hardware trace: first stop can report 2, second reports idle (0).
            // Other values and transport errors have no established recovery path.
            check(state==2) { "Camera stop returned unknown state" }
        }
        error("Camera state timeout: 5710")
    }
}
