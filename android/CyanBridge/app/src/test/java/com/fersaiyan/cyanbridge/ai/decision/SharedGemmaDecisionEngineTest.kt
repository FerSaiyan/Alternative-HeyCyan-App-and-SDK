package com.fersaiyan.cyanbridge.ai.decision

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SharedGemmaDecisionEngineTest {
    private val candidates = listOf(DecisionCandidate("D", "Tap the selected Search node 17"), DecisionCandidate("F", "Defer to planner"), DecisionCandidate("H", "Done"))

    @Test fun `reversed position labels map back to original candidate identity`() = runBlocking {
        var calls = 0
        val engine = SharedGemmaDecisionEngine { prompt, labels ->
            assertEquals(listOf("A", "B", "C"), labels)
            if (calls++ == 0) {
                assertTrue(prompt.contains("⊲A⊳ Tap the selected Search node 17"))
                DecisionLogits(floatArrayOf(8f, 0f, -8f), 0.8f)
            } else {
                assertTrue(prompt.contains("⊲C⊳ Tap the selected Search node 17"))
                DecisionLogits(floatArrayOf(-8f, 0f, 8f), 0.7f)
            }
        }
        val decision = engine.choose("Search is visible", candidates, "test")
        assertEquals(2, calls)
        assertEquals(0, decision.index)
        assertEquals("D", decision.label)
        assertTrue(decision.probabilities[0] > 0.99f)
        assertEquals(1f, decision.probabilities.sum(), 1e-6f)
        assertTrue(decision.abstained)
        assertTrue(decision.rawOutput.contains("labelMass=0.8,0.7"))
    }

    @Test fun `uncertain real logits stay uncertain rather than fabricate confidence`() = runBlocking {
        val engine = SharedGemmaDecisionEngine { _, _ -> DecisionLogits(floatArrayOf(0f, 0f, 0f), 0.01f) }
        val result = engine.choose("ambiguous", candidates, "test")
        assertEquals(1f / 3f, result.confidence, 1e-6f)
        assertEquals(0f, result.margin, 1e-6f)
        assertTrue(result.abstained)
    }

    @Test fun `complete state and menu are preserved`() = runBlocking {
        val state = "x".repeat(5000) + "important fresh value"
        val options = listOf(DecisionCandidate("A", "y".repeat(600) + "node 37"), DecisionCandidate("B", "Defer"))
        val engine = SharedGemmaDecisionEngine { prompt, _ ->
            assertTrue(prompt.contains(state))
            assertTrue(prompt.contains(options[0].description))
            DecisionLogits(floatArrayOf(0f, 1f), 1f)
        }
        engine.choose(state, options, "test")
        Unit
    }

    @Test fun `rejects malformed scores and duplicate identities`() = runBlocking {
        for (scores in listOf(DecisionLogits(floatArrayOf(1f), 1f), DecisionLogits(floatArrayOf(Float.NaN, 0f, 1f), 1f), DecisionLogits(floatArrayOf(0f, 0f, 1f), Float.NaN))) {
            val engine = SharedGemmaDecisionEngine { _, _ -> scores }
            try { engine.choose("state", candidates, "test"); fail("Malformed scores accepted") }
            catch (_: IllegalArgumentException) {}
        }
        val engine = SharedGemmaDecisionEngine { _, _ -> error("Duplicate labels reached runtime") }
        try { engine.choose("state", listOf(candidates[0], candidates[0]), "test"); fail("Duplicate identities accepted") }
        catch (_: IllegalArgumentException) {}
    }
}
