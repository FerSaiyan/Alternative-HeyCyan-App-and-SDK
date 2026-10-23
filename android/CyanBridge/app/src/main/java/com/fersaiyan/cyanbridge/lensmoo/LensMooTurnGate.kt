package com.fersaiyan.cyanbridge.lensmoo

/** One physical press owns recognition, capture, inference and speech until completion. */
internal class LensMooTurnGate {
    enum class Phase { IDLE, LISTENING, WORKING, SPEAKING, CLOSED }
    var phase = Phase.IDLE
        private set
    private var sequence = 0L
    @Synchronized fun begin(): Long? {
        if (phase != Phase.IDLE) return null
        phase = Phase.LISTENING
        return ++sequence
    }
    @Synchronized fun advance(token: Long, from: Phase, to: Phase): Boolean {
        if (token != sequence || phase != from) return false
        phase = to
        return true
    }
    @Synchronized fun active(token: Long) = token == sequence && phase != Phase.IDLE && phase != Phase.CLOSED
    @Synchronized fun finish(token: Long): Boolean {
        if (!active(token)) return false
        phase = Phase.IDLE
        return true
    }
    @Synchronized fun close() { phase = Phase.CLOSED; sequence++ }
}
