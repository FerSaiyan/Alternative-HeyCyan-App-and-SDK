package com.fersaiyan.cyanbridge.ai.decision

import com.fersaiyan.cyanbridge.localmodels.engine.TextEmbeddingEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Embedding-backed bounded decision head.
 *
 * The query is embedded once and each legal candidate is embedded as a short
 * prototype. Candidates are ranked by cosine similarity. Cosine values are
 * deliberately retained as raw scores; the softmax below only preserves the
 * existing [LocalDecision] compatibility and is not a calibrated probability.
 */
class EmbeddingDecisionEngine(
    private val embedder: TextEmbeddingEngine,
    private val queryText: (String) -> String = { it },
    private val candidateText: (DecisionCandidate) -> String = { it.description },
    private val minimumTopScore: Float = -1f,
    private val minimumMargin: Float = 0f,
    private val scoreTemperature: Float = 0.05f,
) : LocalDecisionEngine {

    override suspend fun choose(
        state: String,
        candidates: List<DecisionCandidate>,
        debugTag: String,
    ): LocalDecision = withContext(Dispatchers.Default) {
        require(candidates.size in 2..8) { "Need 2..8 candidates" }
        require(minimumTopScore in -1f..1f) { "minimumTopScore must be in [-1, 1]" }
        require(minimumMargin in 0f..2f) { "minimumMargin must be in [0, 2]" }

        val query = embedder.embed(queryText(state)).vector.validatedVector("query")
        val candidateVectors = candidates.mapIndexed { index, candidate ->
            embedder.embed(candidateText(candidate)).vector
                .validatedVector("candidate[$index:${candidate.label}]")
        }
        val ranking = EmbeddingDecisionMath.rank(
            query = query,
            candidates = candidateVectors,
            minimumTopScore = minimumTopScore,
            minimumMargin = minimumMargin,
        )
        val probabilities = DecisionMath.softmax(ranking.scores, scoreTemperature)
        val top = candidates[ranking.index]
        val raw = buildString {
            append("embedding top=${top.label}")
            append(" score=${ranking.topScore}")
            append(" margin=${ranking.margin}")
            append(" accepted=${ranking.accepted}")
            append(" scores=")
            candidates.forEachIndexed { index, candidate ->
                if (index > 0) append(',')
                append(candidate.label).append(':').append(ranking.scores[index])
            }
            if (!ranking.accepted) append(" reason=${ranking.rejectionReason}")
        }
        println("JEV_EMBED [$debugTag] $raw")

        LocalDecision(
            index = ranking.index,
            label = top.label,
            // This is a bounded raw cosine score for compatibility with the
            // existing router, not a calibrated confidence probability.
            confidence = ranking.topScore.coerceIn(0f, 1f),
            probabilities = probabilities,
            rawOutput = raw.take(1000),
            abstained = !ranking.accepted,
            topScore = ranking.topScore,
            margin = ranking.margin,
        )
    }

    private fun FloatArray.validatedVector(name: String): FloatArray {
        require(isNotEmpty()) { "$name embedding is empty" }
        require(all { it.isFinite() }) { "$name embedding contains NaN/Infinity" }
        var squaredNorm = 0.0
        forEach { value -> squaredNorm += value.toDouble() * value.toDouble() }
        require(squaredNorm > 0.0) { "$name embedding has zero norm" }
        return this
    }
}

data class EmbeddingRanking(
    val index: Int,
    val scores: FloatArray,
    val topScore: Float,
    val margin: Float,
    val accepted: Boolean,
    val rejectionReason: String? = null,
)

/** Pure vector math for embedding decision heads; no Android dependencies. */
object EmbeddingDecisionMath {
    fun l2Normalize(vector: FloatArray): FloatArray {
        validateVector(vector, "vector")
        var squaredNorm = 0.0
        vector.forEach { value -> squaredNorm += value.toDouble() * value.toDouble() }
        require(squaredNorm > 0.0) { "vector has zero norm" }
        val norm = kotlin.math.sqrt(squaredNorm).toFloat()
        return FloatArray(vector.size) { vector[it] / norm }
    }

    fun cosine(a: FloatArray, b: FloatArray): Float {
        validateVector(a, "a")
        validateVector(b, "b")
        require(a.size == b.size) { "embedding dimension mismatch ${a.size} vs ${b.size}" }
        var dot = 0.0
        var aa = 0.0
        var bb = 0.0
        for (i in a.indices) {
            dot += a[i].toDouble() * b[i].toDouble()
            aa += a[i].toDouble() * a[i].toDouble()
            bb += b[i].toDouble() * b[i].toDouble()
        }
        return (dot / (kotlin.math.sqrt(aa) * kotlin.math.sqrt(bb))).toFloat()
    }

    fun rank(
        query: FloatArray,
        candidates: List<FloatArray>,
        minimumTopScore: Float = -1f,
        minimumMargin: Float = 0f,
    ): EmbeddingRanking {
        require(candidates.size >= 2) { "Need at least two candidate vectors" }
        require(minimumTopScore in -1f..1f) { "minimumTopScore must be in [-1, 1]" }
        require(minimumMargin in 0f..2f) { "minimumMargin must be in [0, 2]" }
        val scores = FloatArray(candidates.size) { index -> cosine(query, candidates[index]) }
        // sortedWith is stable only as an implementation detail; thenBy makes
        // ties deterministic and keeps candidate order as the final tie-break.
        val order = scores.indices.sortedWith(
            compareByDescending<Int> { scores[it] }.thenBy { it },
        )
        val topIndex = order.first()
        val topScore = scores[topIndex]
        val secondScore = scores[order[1]]
        val margin = topScore - secondScore
        val accepted = topScore >= minimumTopScore && margin >= minimumMargin
        val rejectionReason = when {
            topScore < minimumTopScore -> "top_score<$minimumTopScore"
            margin < minimumMargin -> "margin<$minimumMargin"
            else -> null
        }
        return EmbeddingRanking(
            index = topIndex,
            scores = scores,
            topScore = topScore,
            margin = margin,
            accepted = accepted,
            rejectionReason = rejectionReason,
        )
    }

    private fun validateVector(vector: FloatArray, name: String) {
        require(vector.isNotEmpty()) { "$name embedding is empty" }
        require(vector.all { it.isFinite() }) { "$name embedding contains NaN/Infinity" }
    }
}
