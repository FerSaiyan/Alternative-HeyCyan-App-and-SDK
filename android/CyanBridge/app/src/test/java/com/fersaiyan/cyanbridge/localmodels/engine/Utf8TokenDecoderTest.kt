package com.fersaiyan.cyanbridge.localmodels.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class Utf8TokenDecoderTest {
    @Test fun `preserves multilingual codepoints split across token boundaries`() {
        val text = "Olá 日本語 👓"
        val decoder = Utf8TokenDecoder()
        val output = buildString {
            text.toByteArray(Charsets.UTF_8).forEach { append(decoder.offer(byteArrayOf(it))) }
            append(decoder.finish())
        }
        assertEquals(text, output)
    }

    @Test fun `flushes a truncated final codepoint once`() {
        val decoder = Utf8TokenDecoder()
        assertEquals("", decoder.offer(byteArrayOf(0xf0.toByte(), 0x9f.toByte())))
        assertEquals("\uFFFD", decoder.finish())
    }
}
