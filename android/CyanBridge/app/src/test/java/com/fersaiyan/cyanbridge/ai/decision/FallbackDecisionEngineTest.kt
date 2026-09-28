package com.fersaiyan.cyanbridge.ai.decision

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FallbackDecisionEngineTest {
    private val candidates = listOf(
        DecisionCandidate("A", "first action"),
        DecisionCandidate("B", "second action"),
    )

    private fun decision(
        label: String,
        abstained: Boolean = false,
    ) = LocalDecision(
        index = 0,
        label = label,
        confidence = 0.9f,
        probabilities = floatArrayOf(0.9f, 0.1f),
        rawOutput = "fake:$label",
        abstained = abstained,
    )

    private fun stub(result: LocalDecision?, throws: Boolean = false) = object : LocalDecisionEngine {
        var calls = 0
        override suspend fun choose(
            state: String,
            candidates: List<DecisionCandidate>,
            debugTag: String,
        ): LocalDecision {
            calls++
            if (throws) throw IllegalStateException("fake primary failure")
            return result!!
        }
    }

    @Test
    fun primaryDecision_isUsedWhenAccepted() = runBlocking {
        val primary = stub(decision("A"))
        val secondary = stub(decision("B"))
        val out = FallbackDecisionEngine(primary, secondary).choose("state", candidates, "t")
        assertEquals("A", out.label)
        assertFalse(out.abstained)
        assertEquals(0, secondary.calls)
    }

    @Test
    fun abstention_cascadesToSecondary() = runBlocking {
        val primary = stub(decision("A", abstained = true))
        val secondary = stub(decision("B"))
        val out = FallbackDecisionEngine(primary, secondary).choose("state", candidates, "t")
        assertEquals("B", out.label)
        assertEquals(1, secondary.calls)
    }

    @Test
    fun primaryFailure_cascadesToSecondary() = runBlocking {
        val primary = stub(null, throws = true)
        val secondary = stub(decision("B"))
        val out = FallbackDecisionEngine(primary, secondary).choose("state", candidates, "t")
        assertEquals("B", out.label)
        assertTrue(out.rawOutput.startsWith("fake:"))
    }
}
