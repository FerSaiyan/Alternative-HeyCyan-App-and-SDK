package com.fersaiyan.cyanbridge.shared.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpeechEnergyTest {
    @Test
    fun rmsOfFullScaleAndEmptyInput() {
        assertEquals(32768.0, PcmEnergy.rms(shortArrayOf(Short.MIN_VALUE, Short.MIN_VALUE)), 0.01)
        assertEquals(0.0, PcmEnergy.rms(shortArrayOf()), 0.01)
        assertEquals(100.0, PcmEnergy.rms(shortArrayOf(0, 100, -100, 0), offset = 1, count = 2), 0.01)
    }

    @Test
    fun detectorUsesTwentyMillisecondFramesAndOnlyTheReadCount() {
        val detector = SpeechActivityDetector(sampleRateHz = 16_000, rmsThreshold = 200.0, minVoicedFraction = 0.10)
        val buffer = ShortArray(1_024) { 300 }
        detector.feed(buffer, 0, 640)
        assertEquals(2, detector.totalFrames)
        assertEquals(2, detector.voicedFrames)
        assertTrue(detector.hasSignificantSpeech())
        assertEquals("frames=2 voiced=2 (100%) maxRms=300", detector.debugInfo())
    }

    @Test
    fun quietWindowIsNotSpeechButOneLoudFrameIs() {
        val detector = SpeechActivityDetector(rmsThreshold = 250.0, minVoicedFraction = 0.15)
        repeat(20) { detector.feed(ShortArray(320) { 10 }) }
        assertFalse(detector.hasSignificantSpeech())
        detector.feed(ShortArray(320) { 600 })
        assertTrue(detector.hasSignificantSpeech())
        detector.reset()
        assertFalse(detector.hasSignificantSpeech())
        assertEquals("frames=0 voiced=0 (0%) maxRms=0", detector.debugInfo())
    }

    @Test
    fun questionEndsOnSilenceAfterSpeechAndReportsMissingSpeech() {
        val endpoint = QuestionEndpoint()
        repeat(4) { assertEquals(QuestionEndpoint.Decision.CONTINUE, endpoint.advance(true, 20, (it + 1) * 20L)) }
        assertTrue(endpoint.heardSpeech)
        repeat(89) { assertEquals(QuestionEndpoint.Decision.CONTINUE, endpoint.advance(false, 20, 1_000L + it)) }
        assertEquals(QuestionEndpoint.Decision.COMPLETE, endpoint.advance(false, 20, 2_000))

        val silent = QuestionEndpoint()
        assertEquals(QuestionEndpoint.Decision.NO_SPEECH, silent.advance(false, 20, 3_300))
        assertEquals(QuestionEndpoint.Decision.LIMIT_REACHED, QuestionEndpoint(resourceLimitMs = 100).advance(true, 20, 100))
    }
}
