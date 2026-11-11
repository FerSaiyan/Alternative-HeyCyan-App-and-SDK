package com.fersaiyan.cyanbridge.ai.live

/** One question handed to Live: an optional still image, then an optional text turn. */
internal data class LiveQuestionTurn(val imagePath: String?, val prompt: String?) {
    val hasImage: Boolean get() = imagePath != null
    val isEmpty: Boolean get() = imagePath == null && prompt == null
}

/** What a Live start request does to the service's session. */
internal enum class LiveStartDecision {
    START_SESSION,
    ADD_TO_SESSION,
    IGNORE;

    companion object {
        /** A photo question while Live runs joins the session; other repeats are ignored as before. */
        fun of(sessionStarted: Boolean, stopping: Boolean, turn: LiveQuestionTurn): LiveStartDecision = when {
            stopping -> IGNORE
            !sessionStarted -> START_SESSION
            turn.hasImage -> ADD_TO_SESSION
            else -> IGNORE
        }
    }
}

/**
 * "Initializing Live" is spoken only for a question that starts a session. A photo that joins a
 * running session goes in silently: the Live microphone would hear the cue as user speech.
 */
internal fun LiveQuestionTurn.speaksInitializingCue(sessionRunning: Boolean): Boolean =
    LiveStartDecision.of(sessionStarted = sessionRunning, stopping = false, turn = this) == LiveStartDecision.START_SESSION

/**
 * The questions of one Live session, in arrival order.
 *
 * The first one is the session's initial turn. A photo question that arrives while the session
 * runs (the second or third photo, or a photo after a voice question) joins the same session.
 * It used to be dropped as a "duplicate start", so the model never saw any image but the first.
 * Turns wait for setup to complete, then go out in order.
 */
internal class LiveQuestionTurnQueue {
    private val pending = ArrayDeque<LiveQuestionTurn>()
    private var setupComplete = false

    @Synchronized
    fun reset() {
        pending.clear()
        setupComplete = false
    }

    /** Returns [turn] when it can be sent now; null when it waits for setup or is empty. */
    @Synchronized
    fun offer(turn: LiveQuestionTurn): LiveQuestionTurn? {
        if (turn.isEmpty) return null
        if (setupComplete) return turn
        pending.addLast(turn)
        return null
    }

    /** The first setup of the session: every turn queued so far. A resumed socket gets none. */
    @Synchronized
    fun onSetupComplete(): List<LiveQuestionTurn> {
        if (setupComplete) return emptyList()
        setupComplete = true
        return pending.toList().also { pending.clear() }
    }
}
