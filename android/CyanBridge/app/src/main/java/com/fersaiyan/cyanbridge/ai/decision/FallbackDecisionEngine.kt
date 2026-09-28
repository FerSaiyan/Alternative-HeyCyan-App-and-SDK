package com.fersaiyan.cyanbridge.ai.decision

/**
 * Two-stage cascade: the fast primary (e.g. an embedding cosine gate) decides
 * whenever it accepts; the slower secondary (e.g. the single-token local LLM)
 * handles only primary abstentions or primary failures.
 *
 * A secondary decision is returned as-is, so downstream confidence handling
 * (including the brain's JSON-planner fallback) keeps working unchanged.
 */
class FallbackDecisionEngine(
    private val primary: LocalDecisionEngine,
    private val secondary: LocalDecisionEngine,
) : LocalDecisionEngine {
    override suspend fun choose(
        state: String,
        candidates: List<DecisionCandidate>,
        debugTag: String,
    ): LocalDecision {
        val first = runCatching {
            primary.choose(state, candidates, "$debugTag-fallback-primary")
        }.getOrNull()
        if (first != null && !first.abstained) return first
        return secondary.choose(state, candidates, "$debugTag-fallback-secondary")
    }
}
