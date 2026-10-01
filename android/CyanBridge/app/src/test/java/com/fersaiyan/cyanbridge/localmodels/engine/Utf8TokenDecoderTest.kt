package com.fersaiyan.cyanbridge.localmodels.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class Utf8TokenDecoderTest {
    @Test fun splitMultilingualTokensNeverEmitReplacementCharacters() {
        val text = "Olá 日本語 😀"
        val decoder = Utf8TokenDecoder()
        val result = buildString {
            text.toByteArray(Charsets.UTF_8).forEach { append(decoder.offer(byteArrayOf(it))) }
            append(decoder.finish())
        }
        assertEquals(text, result)
    }
}
