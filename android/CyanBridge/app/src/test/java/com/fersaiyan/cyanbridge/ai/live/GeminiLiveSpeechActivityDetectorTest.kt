package com.fersaiyan.cyanbridge.ai.live

import org.junit.Assert.assertEquals
import org.junit.Test

class GeminiLiveSpeechActivityDetectorTest {
    @Test
    fun `speech starts after sustained active chunks and stops after silence`() {
        val changes = mutableListOf<Boolean>()
        val detector = GeminiLiveSpeechActivityDetector(
            startThreshold = 500,
            activeChunksToStart = 2,
            silentChunksToStop = 3,
            onChanged = changes::add,
        )

        detector.offerPcm16Le(chunk(2_000))
        assertEquals(emptyList<Boolean>(), changes)
        detector.offerPcm16Le(chunk(2_000))
        assertEquals(listOf(true), changes)

        detector.offerPcm16Le(chunk(0))
        detector.offerPcm16Le(chunk(0))
        assertEquals(listOf(true), changes)
        detector.offerPcm16Le(chunk(0))
        assertEquals(listOf(true, false), changes)
    }

    @Test
    fun `single noise spike does not start speech window`() {
        val changes = mutableListOf<Boolean>()
        val detector = GeminiLiveSpeechActivityDetector(
            startThreshold = 500,
            activeChunksToStart = 2,
            silentChunksToStop = 3,
            onChanged = changes::add,
        )

        detector.offerPcm16Le(chunk(3_000))
        detector.offerPcm16Le(chunk(0))

        assertEquals(emptyList<Boolean>(), changes)
    }

    private fun chunk(sample: Int, samples: Int = 640): ByteArray {
        val value = sample.toShort().toInt()
        return ByteArray(samples * 2).also { bytes ->
            repeat(samples) { index ->
                bytes[index * 2] = (value and 0xff).toByte()
                bytes[index * 2 + 1] = ((value ushr 8) and 0xff).toByte()
            }
        }
    }
}
