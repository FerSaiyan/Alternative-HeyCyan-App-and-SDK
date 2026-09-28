package com.fersaiyan.cyanbridge.ai.decision

import kotlin.math.exp

/** Pure math for Jev-like scoring. Fully JVM-testable, no Android dependency. */
object DecisionMath {
    fun softmax(logits: FloatArray, temperature: Float = 1.0f): FloatArray {
        require(logits.isNotEmpty()) { "logits must not be empty" }
        require(temperature > 0f) { "temperature must be > 0" }
        val scaled = FloatArray(logits.size) { i -> logits[i] / temperature }
        val max = scaled.maxOrNull() ?: 0f
        var sum = 0.0
        val exps = DoubleArray(scaled.size) { i ->
            val e = exp((scaled[i] - max).toDouble())
            sum += e
            e
        }
        return FloatArray(scaled.size) { i -> (exps[i] / sum).toFloat() }
    }

    /**
     * Pseudo-logits from a single decoded letter: winner gets [winnerLogit],
     * everyone else gets 0. Lets single-token decoding reuse the same
     * softmax/confidence path as a future true-logit engine.
     */
    fun pseudoProbabilities(winnerIndex: Int, size: Int, winnerLogit: Float = 3.0f): FloatArray {
        require(size > 0) { "size must be > 0" }
        require(winnerIndex in 0 until size) { "winnerIndex out of range" }
        val logits = FloatArray(size) { 0f }
        logits[winnerIndex] = winnerLogit
        return softmax(logits)
    }

    fun calibratedConfidence(probability: Float, temperature: Float = 1.0f): Float {
        // Placeholder for temperature-scaled calibration. A held-out CyanBridge
        // command dataset should fit `temperature` per decision head; see docs.
        // For now this is identity, kept explicit so call sites don't treat raw
        // softmax as calibrated.
        return probability.coerceIn(0f, 1f)
    }
}
