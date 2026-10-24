package com.fersaiyan.cyanbridge.shared.devices

import com.fersaiyan.cyanbridge.shared.speech.StreamingSpeechChunker
import kotlin.test.Test
import kotlin.test.assertEquals

class StreamingSpeechChunkerTest {
    private fun chunk(tokens: List<String>): List<String> {
        val out = mutableListOf<String>()
        val chunker = StreamingSpeechChunker { out += it }
        tokens.forEach(chunker::append)
        chunker.finish()
        return out
    }

    @Test
    fun firstSentenceIsSpokenAsSoonAsItEnds() {
        val out = mutableListOf<String>()
        val chunker = StreamingSpeechChunker { out += it }
        "A capital da França é Paris. Ela fica".split(" ").forEach { chunker.append("$it ") }
        assertEquals(listOf("A capital da França é Paris."), out)
        chunker.finish()
        assertEquals("Ela fica", out.last())
    }

    @Test
    fun laterChunksWaitForLongerSentences() {
        val out = chunk(listOf("Oi, tudo bem com você? ", "Sim. ", "Hoje faz sol e o céu está bem azul. ", "Fim."))
        assertEquals("Oi, tudo bem com você?", out.first())
        assertEquals("Sim. Hoje faz sol e o céu está bem azul.", out[1])
        assertEquals("Fim.", out.last())
    }

    @Test
    fun reasoningIsNeverSpoken() {
        val out = chunk(listOf("<think>", "the user wants ", "a greeting</think>", "Olá! Como posso ajudar hoje?"))
        assertEquals(listOf("Olá! Como posso ajudar hoje?"), out)
    }

    @Test
    fun numbersAndMarkdownDoNotBreakSentences() {
        val out = chunk(listOf("Passo 1. Abra o app **CyanBridge** agora. ", "Depois toque em Examinar."))
        assertEquals("Passo 1. Abra o app CyanBridge agora.", out.first())
    }
}
