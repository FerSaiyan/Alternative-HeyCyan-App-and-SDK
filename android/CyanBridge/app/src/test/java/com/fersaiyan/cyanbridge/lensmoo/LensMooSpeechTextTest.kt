package com.fersaiyan.cyanbridge.lensmoo

import org.junit.Assert.*
import org.junit.Test

class LensMooSpeechTextTest {
    @Test fun longAnswerPreservesAllTextWithinEngineLimit() {
        val text="A sentence with spaces. ".repeat(400)
        val chunks=LensMooSpeechText.chunks(text,4000)
        assertEquals(text,chunks.joinToString(""))
        assertTrue(chunks.size>1)
        assertTrue(chunks.all { it.isNotEmpty() && it.length<=4000 })
    }
    @Test fun unbrokenWordAndSurrogatePairAreNotLost() {
        val text="x".repeat(3999)+"😀"+"y".repeat(4001)
        val chunks=LensMooSpeechText.chunks(text,4000)
        assertEquals(text,chunks.joinToString(""))
        assertTrue(chunks.all { it.length<=4000 && !it.last().isHighSurrogate() && !it.first().isLowSurrogate() })
    }
    @Test fun shortAnswerUsesSingleChunk() {
        assertEquals(listOf("Short answer"),LensMooSpeechText.chunks("Short answer",4000))
    }
}
