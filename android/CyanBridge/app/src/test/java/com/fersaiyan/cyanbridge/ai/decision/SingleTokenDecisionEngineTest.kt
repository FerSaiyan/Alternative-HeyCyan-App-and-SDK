package com.fersaiyan.cyanbridge.ai.decision

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SingleTokenDecisionEngineTest {
    private val candidates = AssistantDecisionOptions.candidates()

    @Test fun `chooses letter from fake model`() = runBlocking {
        val engine = SingleTokenDecisionEngine(generate = { _, _ -> "C" })
        val d = engine.choose("Abra o Spotify e toque minha playlist de academia.", candidates, "test-pt")
        println("JEV_CI decision label=${d.label} conf=${d.confidence} probs=${d.probabilities.joinToString(",")} raw='${d.rawOutput}'")
        assertEquals(2, d.index)
        assertEquals("C", d.label)
        assertTrue(d.confidence > 0.5f)
    }

    @Test fun `tolerates think preamble from reasoning model`() = runBlocking {
        val engine = SingleTokenDecisionEngine(generate = { _, _ ->
            "<think>Usuário quer abrir app... A é responder, C é operar. Escolho C.</think>\nAnswer: C"
        })
        val d = engine.choose("Abra o Spotify", candidates, "test-think")
        println("JEV_CI think-tolerant choice=${d.label}")
        assertEquals("C", d.label)
    }

    @Test fun `prompt forbids thinking and lists options`() {
        val prompt = DecisionPromptBuilder.buildClassificationPrompt("hello", candidates)
        println("JEV_CI prompt=\n$prompt")
        assertTrue(prompt.contains("ONLY the single letter"))
        assertTrue(prompt.contains("No <think>"))
        assertTrue(prompt.contains("A = "))
        assertTrue(prompt.contains("User request:"))
    }

    @Test fun `ui prompt includes goal screen and actions`() {
        val ui = listOf(DecisionCandidate("A", "Tap Search"), DecisionCandidate("B", "Finish task"))
        val prompt = DecisionPromptBuilder.buildUiActionPrompt("Play LTT video", "YouTube home", ui)
        println("JEV_CI ui-prompt=\n$prompt")
        assertTrue(prompt.contains("Play LTT video"))
        assertTrue(prompt.contains("A = Tap Search"))
    }

    @Test fun `system prompt demands single letter`() {
        assertTrue(DecisionPromptBuilder.systemPromptForSingleLetter().contains("exactly one capital letter"))
    }
}
