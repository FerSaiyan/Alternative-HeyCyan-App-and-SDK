package com.fersaiyan.cyanbridge.ai.image

import com.fersaiyan.cyanbridge.media.autocapture.SpeechActivityDetector
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class QuestionEndpointTest {
    @Test fun speakingBeyondOldTwentySecondLimitDoesNotEndTheQuestion() {
        val endpoint = QuestionEndpoint()
        repeat(2500) { i -> assertEquals(QuestionEndpoint.Decision.CONTINUE, endpoint.advance(true, 20, (i + 1) * 20L)) }
        assertTrue(endpoint.heardSpeech)
        repeat(89) { i -> assertEquals(QuestionEndpoint.Decision.CONTINUE, endpoint.advance(false, 20, 50_000 + (i + 1) * 20L)) }
        assertEquals(QuestionEndpoint.Decision.COMPLETE, endpoint.advance(false, 20, 51_800))
    }
    @Test fun hesitationInsideQuestionResetsSilenceHangoverWhenSpeechReturns() {
        val endpoint = QuestionEndpoint()
        repeat(5) { endpoint.advance(true, 20, 100) }
        repeat(70) { assertEquals(QuestionEndpoint.Decision.CONTINUE, endpoint.advance(false, 20, 1500)) }
        assertEquals(QuestionEndpoint.Decision.CONTINUE, endpoint.advance(true, 20, 1520))
        repeat(89) { assertEquals(QuestionEndpoint.Decision.CONTINUE, endpoint.advance(false, 20, 3000)) }
    }
    @Test fun isolatedNoiseSpikeDoesNotTurnSilenceIntoAQuestion() {
        val endpoint = QuestionEndpoint()
        endpoint.advance(true, 20, 20)
        repeat(163) { endpoint.advance(false, 20, (it + 2) * 20L) }
        assertEquals(QuestionEndpoint.Decision.NO_SPEECH, endpoint.advance(false, 20, 3300))
        assertFalse(endpoint.heardSpeech)
    }
    @Test fun resourceLimitIsNotReportedAsACompletedTruncatedQuestion() {
        val endpoint = QuestionEndpoint(resourceLimitMs = 2000)
        repeat(99) { endpoint.advance(true, 20, (it + 1) * 20L) }
        assertEquals(QuestionEndpoint.Decision.LIMIT_REACHED, endpoint.advance(true, 20, 2000))
    }
    @Test fun sharedEnergyPrimitiveHandlesFullScaleNegativePcmWithoutOverflow() {
        assertEquals(32768.0, SpeechActivityDetector.rms(shortArrayOf(Short.MIN_VALUE, Short.MIN_VALUE)), 0.01)
        assertEquals(0.0, SpeechActivityDetector.rms(shortArrayOf()), 0.01)
    }
    @Test fun wavHeaderDescribesTheActualPcmSentToOfflineRecognitionAndModels() {
        val bytes = AudioQuestionRecorder.wavHeader(32_000)
        val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(bytes, 0, 4))
        assertEquals(32_036, header.getInt(4))
        assertEquals(16_000, header.getInt(24))
        assertEquals(1, header.getShort(22).toInt())
        assertEquals(16, header.getShort(34).toInt())
        assertEquals(32_000, header.getInt(40))
    }
}
