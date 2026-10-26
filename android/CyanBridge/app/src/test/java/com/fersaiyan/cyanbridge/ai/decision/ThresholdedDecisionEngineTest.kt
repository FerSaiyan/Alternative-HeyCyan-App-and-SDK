package com.fersaiyan.cyanbridge.ai.decision

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ThresholdedDecisionEngineTest {
    private val candidates = listOf(DecisionCandidate("A", "Answer"), DecisionCandidate("B", "Look with the camera"))

    private fun scored(confidence: Float, margin: Float) = object : LocalDecisionEngine {
        override suspend fun choose(state: String, candidates: List<DecisionCandidate>, debugTag: String) =
            LocalDecision(1, "B", confidence, floatArrayOf(1 - confidence, confidence), "raw", abstained = true, margin = margin)
    }

    @Test fun `clears the shadow abstention when confidence and margin pass`() = runBlocking {
        val decision = ThresholdedDecisionEngine(scored(0.9f, 0.8f)).choose("what is this", candidates)
        assertFalse(decision.abstained)
        assertEquals(1, decision.index)
    }

    @Test fun `abstains below either threshold so the fallback engine decides`() = runBlocking {
        assertTrue(ThresholdedDecisionEngine(scored(0.6f, 0.8f)).choose("x", candidates).abstained)
        assertTrue(ThresholdedDecisionEngine(scored(0.9f, 0.1f)).choose("x", candidates).abstained)
        assertTrue(ThresholdedDecisionEngine(scored(Float.NaN, 0.9f)).choose("x", candidates).abstained)
    }

    @Test fun `fallback uses the classic engine when the logit engine is unavailable or unsure`() = runBlocking {
        val classic = object : LocalDecisionEngine {
            override suspend fun choose(state: String, candidates: List<DecisionCandidate>, debugTag: String) =
                LocalDecision(0, "A", 0.87f, floatArrayOf(0.87f, 0.13f), "classic")
        }
        val unavailable = object : LocalDecisionEngine {
            override suspend fun choose(state: String, candidates: List<DecisionCandidate>, debugTag: String): LocalDecision =
                error("No loaded chat model exposes decision logits")
        }
        assertEquals("classic", FallbackDecisionEngine(ThresholdedDecisionEngine(unavailable), classic).choose("x", candidates).rawOutput)
        assertEquals("classic", FallbackDecisionEngine(ThresholdedDecisionEngine(scored(0.55f, 0.1f)), classic).choose("x", candidates).rawOutput)
        assertEquals("raw", FallbackDecisionEngine(ThresholdedDecisionEngine(scored(0.95f, 0.9f)), classic).choose("x", candidates).rawOutput)
    }
}
