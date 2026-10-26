package com.fersaiyan.cyanbridge.ai.speaker

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class SpeakerMathTest {
    private fun tone(ms: Int, amplitude: Int = 4_000) =
        ShortArray(16 * ms) { (amplitude * sin(2 * PI * 220 * it / 16_000.0)).toInt().toShort() }

    private fun silence(ms: Int) = ShortArray(16 * ms)

    @Test fun `trim keeps speech with padding and drops silence`() {
        val pcm = silence(1_000) + tone(1_500) + silence(1_000)
        val trimmed = SpeakerMath.trimToSpeech(pcm, padFrames = 5)
        assertEquals(16 * 1_500 + 2 * 5 * 320, trimmed.size)
        assertTrue(SpeakerMath.trimToSpeech(silence(2_000)).isEmpty())
    }

    @Test fun `short speech is tiled into one window and very short speech yields none`() {
        val windows = SpeakerMath.windows(tone(1_500), windowSamples = 32_000)
        assertEquals(1, windows.size)
        assertEquals(32_000, windows[0].size)
        // Tiled, not zero-padded: the tail repeats the start.
        assertEquals(windows[0][0], windows[0][24_000], 0f)
        assertTrue(SpeakerMath.windows(tone(900), windowSamples = 32_000).isEmpty())
    }

    @Test fun `long speech is windowed with hop and an end-aligned tail`() {
        // 3.7 s: starts 0 s and 1 s fit; the 0.7 s remainder exceeds half a hop -> tail at 1.7 s.
        val windows = SpeakerMath.windows(tone(3_700), windowSamples = 32_000, hopSamples = 16_000)
        assertEquals(3, windows.size)
        // 3.2 s: starts 0 s and 1 s; remainder 0.2 s is dropped.
        assertEquals(2, SpeakerMath.windows(tone(3_200), windowSamples = 32_000, hopSamples = 16_000).size)
    }

    @Test fun `template is the normalised mean of normalised embeddings`() {
        val t = SpeakerMath.template(listOf(floatArrayOf(10f, 0f), floatArrayOf(0f, 0.1f)))
        assertEquals(t[0], t[1], 1e-6f)
        assertEquals(1f, t[0] * t[0] + t[1] * t[1], 1e-5f)
        assertEquals(1f, SpeakerMath.cosine(floatArrayOf(1f, 2f), floatArrayOf(2f, 4f)), 1e-6f)
        assertEquals(0f, SpeakerMath.cosine(floatArrayOf(1f, 0f), floatArrayOf(0f, 3f)), 1e-6f)
    }

    @Test fun `leave-one-out flags the phrase that disagrees`() {
        val same = listOf(floatArrayOf(1f, 0.1f, 0f), floatArrayOf(0.9f, 0f, 0.1f))
        val other = listOf(floatArrayOf(0f, 0f, 1f))
        val scores = SpeakerMath.leaveOneOutScores(listOf(same, same, other))
        assertEquals(2, scores.indices.minBy { scores[it] })
        assertTrue(scores[2] < EnrollmentQuality.MIN_LEAVE_ONE_OUT)
        assertTrue(scores[0] > 0.8f)
    }

    @Test fun `quality check reports silence, short and clipped phrases`() {
        assertEquals(PhraseIssue.NO_SPEECH, EnrollmentQuality.check(silence(3_000)))
        assertEquals(PhraseIssue.TOO_SHORT, EnrollmentQuality.check(silence(500) + tone(800) + silence(500)))
        assertEquals(PhraseIssue.CLIPPED, EnrollmentQuality.check(tone(2_000, amplitude = 32_767)))
        assertNull(EnrollmentQuality.check(silence(300) + tone(2_000) + silence(300)))
    }
}
