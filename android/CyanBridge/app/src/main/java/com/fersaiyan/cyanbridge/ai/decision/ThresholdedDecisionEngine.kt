package com.fersaiyan.cyanbridge.ai.decision

/**
 * Turns a scored engine into an acting one: the top choice is kept only when its probability and
 * its margin over the runner-up clear the thresholds; otherwise the decision abstains, so a
 * [FallbackDecisionEngine] hands it to the next engine. This also clears the unconditional
 * abstention of [SharedGemmaDecisionEngine]. The defaults are conservative placeholders until
 * the bounded-decision evaluation fits them on device.
 */
class ThresholdedDecisionEngine(
    private val delegate: LocalDecisionEngine,
    private val minConfidence: Float = 0.70f,
    private val minMargin: Float = 0.25f,
) : LocalDecisionEngine {
    override suspend fun choose(state: String, candidates: List<DecisionCandidate>, debugTag: String): LocalDecision {
        val decision = delegate.choose(state, candidates, debugTag)
        val passes = decision.confidence.isFinite() && decision.confidence >= minConfidence && decision.margin >= minMargin
        return decision.copy(abstained = !passes)
    }
}
