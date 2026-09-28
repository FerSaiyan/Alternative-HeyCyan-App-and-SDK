package com.fersaiyan.cyanbridge.ai.decision

import com.fersaiyan.cyanbridge.localmodels.engine.TextEmbeddingEngine
import com.fersaiyan.cyanbridge.localmodels.engine.TextEmbeddingResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddingDecisionEngineTest {
    @Test
    fun normalizeAndCosine_areFiniteAndDimensionSafe() {
        val normalized = EmbeddingDecisionMath.l2Normalize(floatArrayOf(3f, 4f))
        assertEquals(0.6f, normalized[0], 1e-6f)
        assertEquals(0.8f, normalized[1], 1e-6f)
        assertEquals(1f, EmbeddingDecisionMath.cosine(normalized, normalized), 1e-6f)
    }

    @Test
    fun rank_prefersHighestScore_andBreaksTiesByCandidateOrder() {
        val ranking = EmbeddingDecisionMath.rank(
            query = floatArrayOf(1f, 0f),
            candidates = listOf(
                floatArrayOf(1f, 0f),
                floatArrayOf(0f, 1f),
                floatArrayOf(1f, 0f),
            ),
            minimumTopScore = 0.5f,
            minimumMargin = 0f,
        )
        assertEquals(0, ranking.index)
        assertEquals(1f, ranking.topScore, 1e-6f)
        assertEquals(0f, ranking.margin, 1e-6f)
        assertTrue(ranking.accepted)
    }

    @Test
    fun rank_abstainsWhenMarginIsTooSmall() {
        val ranking = EmbeddingDecisionMath.rank(
            query = floatArrayOf(1f, 0f),
            candidates = listOf(
                floatArrayOf(1f, 0f),
                floatArrayOf(0.99f, 0.1f),
            ),
            minimumTopScore = 0.5f,
            minimumMargin = 0.1f,
        )
        assertFalse(ranking.accepted)
        assertEquals("margin<0.1", ranking.rejectionReason)
    }

    @Test
    fun engine_returnsAcceptedChoice_andExplicitAbstention() = runBlocking {
        val candidates = listOf(
            DecisionCandidate("A", "answer a question"),
            DecisionCandidate("B", "operate the phone"),
        )
        val acceptedEngine = EmbeddingDecisionEngine(
            embedder = FakeEmbedder(
                mapOf(
                    "weather" to floatArrayOf(1f, 0f),
                    "answer a question" to floatArrayOf(1f, 0f),
                    "operate the phone" to floatArrayOf(0f, 1f),
                ),
            ),
            minimumTopScore = 0.5f,
            minimumMargin = 0.1f,
        )
        val accepted = acceptedEngine.choose("weather", candidates, "unit-accepted")
        assertEquals("A", accepted.label)
        assertFalse(accepted.abstained)
        assertTrue(accepted.margin > 0.1f)

        val abstainingEngine = EmbeddingDecisionEngine(
            embedder = FakeEmbedder(
                mapOf(
                    "unclear" to floatArrayOf(1f, 0f),
                    "answer a question" to floatArrayOf(1f, 0f),
                    "operate the phone" to floatArrayOf(0.99f, 0.1f),
                ),
            ),
            minimumTopScore = 0.5f,
            minimumMargin = 0.1f,
        )
        val abstained = abstainingEngine.choose("unclear", candidates, "unit-abstain")
        assertTrue(abstained.abstained)
        assertEquals("A", abstained.label)
    }

    @Test(expected = IllegalArgumentException::class)
    fun cosine_rejectsDimensionMismatch() {
        EmbeddingDecisionMath.cosine(floatArrayOf(1f, 0f), floatArrayOf(1f))
    }

    @Test(expected = IllegalArgumentException::class)
    fun cosine_rejectsNonFiniteValues() {
        EmbeddingDecisionMath.cosine(floatArrayOf(Float.NaN), floatArrayOf(1f))
    }

    private class FakeEmbedder(
        private val vectors: Map<String, FloatArray>,
    ) : TextEmbeddingEngine {
        override suspend fun embed(text: String): TextEmbeddingResult {
            return TextEmbeddingResult(
                vector = vectors[text] ?: error("No fake vector for '$text'"),
                modelPath = "fake",
                elapsedMs = 0,
                backend = "fake",
            )
        }
    }
}
