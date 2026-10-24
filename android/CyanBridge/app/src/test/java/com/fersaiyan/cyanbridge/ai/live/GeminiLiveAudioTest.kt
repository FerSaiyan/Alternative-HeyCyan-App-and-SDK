package com.fersaiyan.cyanbridge.ai.live

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class GeminiLiveAudioTest {
    @Test
    fun `resampler preserves PCM when source rate already matches Gemini Live`() {
        val input = shortArrayOf(-500, 0, 500)
        assertArrayEquals(input, PcmResampler.resampleMono16(input, 16_000, 16_000))
    }

    @Test
    fun `resampler converts 24 kHz glasses PCM to 16 kHz PCM`() {
        val input = shortArrayOf(0, 300, 600, 900, 1200, 1500)
        val output = PcmResampler.resampleMono16(input, 24_000, 16_000)
        assertEquals(4, output.size)
        assertEquals(0, output.first().toInt())
        assertEquals(900, output[2].toInt())
    }
}
