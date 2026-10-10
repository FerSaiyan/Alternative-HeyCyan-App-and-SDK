package com.fersaiyan.cyanbridge.localmodels.engine

import com.fersaiyan.cyanbridge.ai.decision.DecisionMath
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.ln

class LiteRtDecisionBindingTest {
    class FakeEngine(private val values: FloatArray) {
        var prompt: String? = null
        fun scoreDecision(bytes: ByteArray, labels: Array<String>): FloatArray {
            prompt = bytes.toString(Charsets.UTF_8)
            assertArrayEquals(arrayOf("A", "B"), labels)
            return values
        }
    }
    class FailingEngine {
        fun scoreDecision(bytes: ByteArray, labels: Array<String>): FloatArray {
            throw IllegalArgumentException("unsupported boundary")
        }
    }

    @Test fun `full vocabulary log probabilities retain label mass and relative ranking`() {
        val engine = FakeEngine(floatArrayOf(ln(0.06).toFloat(), ln(0.02).toFloat(), 0.08f))
        val result = LiteRtDecisionBinding.score(engine, "Olá 👓 ⊲", listOf("A", "B"))
        assertEquals("Olá 👓 ⊲", engine.prompt)
        assertEquals(0.08f, result.labelMass, 1e-6f)
        assertArrayEquals(floatArrayOf(0.75f, 0.25f), DecisionMath.softmax(result.logits), 1e-6f)
    }

    @Test fun `stock SDK reports missing scoring API clearly`() {
        try {
            LiteRtDecisionBinding.score(Any(), "prompt", listOf("A", "B"))
            fail("Missing API accepted")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("patched 0.16.0 AAR"))
        }
    }

    @Test fun `native failures are unwrapped`() {
        try {
            LiteRtDecisionBinding.score(FailingEngine(), "prompt", listOf("A", "B"))
            fail("Native failure hidden")
        } catch (expected: IllegalArgumentException) {
            assertEquals("unsupported boundary", expected.message)
        }
    }

    @Test fun `invalid or renormalized confidence is rejected`() {
        val invalid = listOf(
            floatArrayOf(-1f), floatArrayOf(Float.NaN, -1f, 0.1f),
            floatArrayOf(1f, -1f, 0.1f), floatArrayOf(-1f, -1f, Float.NaN),
            floatArrayOf(-1f, -1f, 1.1f), floatArrayOf(-4f, -4f, 1f),
        )
        invalid.forEach { values ->
            try {
                LiteRtDecisionBinding.score(FakeEngine(values), "prompt", listOf("A", "B"))
                fail("Invalid scores accepted: ${values.joinToString()}")
            } catch (_: IllegalArgumentException) {}
        }
    }

    @Test fun `invalid labels fail before invoking fork`() {
        for (labels in listOf(listOf("A", "A"), listOf("A", "AA"), listOf("A", "👓"))) {
            try {
                LiteRtDecisionBinding.score(Any(), "prompt", labels)
                fail("Invalid labels accepted")
            } catch (_: IllegalArgumentException) {}
        }
    }
}
