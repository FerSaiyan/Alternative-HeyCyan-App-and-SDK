package com.fersaiyan.cyanbridge.ai.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Photo questions after the first one must reach the running Live session (users saw them dropped). */
class GeminiLiveQuestionTurnsTest {
    private val photo1 = LiveQuestionTurn("/cache/q1.jpg", "Describe the image")
    private val photo2 = LiveQuestionTurn("/cache/q2.jpg", "Describe the image")
    private val photo3 = LiveQuestionTurn("/cache/q3.jpg", null)
    private val voice = LiveQuestionTurn(null, "What time is it in Tokyo?")

    @Test
    fun startDecisions() {
        val cases = listOf(
            Triple(Pair(false, false), photo1, LiveStartDecision.START_SESSION),
            Triple(Pair(false, false), voice, LiveStartDecision.START_SESSION),
            // The reported bug: a second photo in a running session was ignored as a duplicate start.
            Triple(Pair(true, false), photo2, LiveStartDecision.ADD_TO_SESSION),
            Triple(Pair(true, false), voice, LiveStartDecision.IGNORE),
            Triple(Pair(true, true), photo2, LiveStartDecision.IGNORE),
            Triple(Pair(false, true), photo2, LiveStartDecision.IGNORE),
        )
        for ((state, turn, expected) in cases) {
            assertEquals("$state $turn", expected, LiveStartDecision.of(state.first, state.second, turn))
        }
    }

    @Test
    fun photosBeforeSetupWaitAndLaterOnesGoOutImmediately() {
        val queue = LiveQuestionTurnQueue()
        assertNull(queue.offer(voice))
        assertNull(queue.offer(photo1))
        assertEquals(listOf(voice, photo1), queue.onSetupComplete())
        assertEquals(photo2, queue.offer(photo2))
        assertEquals(photo3, queue.offer(photo3))
        // A resumed socket completes setup again; nothing is resent.
        assertEquals(emptyList<LiveQuestionTurn>(), queue.onSetupComplete())
        assertNull(queue.offer(LiveQuestionTurn(null, null)))

        queue.reset()
        assertNull(queue.offer(photo3))
        assertEquals(listOf(photo3), queue.onSetupComplete())
    }

    @Test
    fun initializingCueOnlyWhenTheQuestionStartsASession() {
        assertTrue(photo1.speaksInitializingCue(sessionRunning = false))
        assertTrue(voice.speaksInitializingCue(sessionRunning = false))
        // Spoken into a running session, the Live microphone heard the cue.
        assertFalse(photo2.speaksInitializingCue(sessionRunning = true))
        assertFalse(voice.speaksInitializingCue(sessionRunning = true))
    }
}
