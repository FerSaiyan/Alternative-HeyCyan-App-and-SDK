package com.fersaiyan.cyanbridge.ai.decision

/**
 * One bounded choice for a Jev-like System-One decision.
 *
 * Labels must be single ASCII letters ("A", "B", ...) so the model can answer
 * with a single token and we can score without free-form generation.
 */
data class DecisionCandidate(
    val label: String,
    val description: String,
) {
    init {
        require(label.length == 1 && label[0] in 'A'..'Z') {
            "DecisionCandidate label must be a single A-Z letter, got '$label'"
        }
        require(description.isNotBlank()) { "DecisionCandidate description must not be blank" }
    }
}

data class LocalDecision(
    val index: Int,
    val label: String,
    val confidence: Float,
    /** Softmax probabilities aligned with [candidates] order. Sums to ~1. */
    val probabilities: FloatArray,
    /** Raw model output, kept for debugging / CI logs. */
    val rawOutput: String,
    /** True when we fell back to heuristics because the model path failed. */
    val usedFallback: Boolean = false,
    /** True when the decision policy rejected the top candidate. */
    val abstained: Boolean = false,
    /** Highest raw score from the active decision backend. */
    val topScore: Float = confidence,
    /** Difference between the top and second-best raw scores. */
    val margin: Float = 0f,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is LocalDecision) return false
        return index == other.index &&
            label == other.label &&
            confidence == other.confidence &&
            probabilities.contentEquals(other.probabilities) &&
            rawOutput == other.rawOutput &&
            usedFallback == other.usedFallback &&
            abstained == other.abstained &&
            topScore == other.topScore &&
            margin == other.margin
    }

    override fun hashCode(): Int {
        var result = index
        result = 31 * result + label.hashCode()
        result = 31 * result + confidence.hashCode()
        result = 31 * result + probabilities.contentHashCode()
        result = 31 * result + rawOutput.hashCode()
        result = 31 * result + usedFallback.hashCode()
        result = 31 * result + abstained.hashCode()
        result = 31 * result + topScore.hashCode()
        result = 31 * result + margin.hashCode()
        return result
    }
}

/**
 * Jev-like System-One abstraction: choose among bounded candidates, no prose.
 */
interface LocalDecisionEngine {
    suspend fun choose(
        state: String,
        candidates: List<DecisionCandidate>,
        debugTag: String = "decision",
    ): LocalDecision
}
