package com.fersaiyan.cyanbridge.localagent

import com.fersaiyan.cyanbridge.ai.decision.DecisionCandidate

/**
 * Builds a bounded A-H candidate list from the current observation.
 *
 * Mirrors the mobile-jev pattern: enumerate legal actions BEFORE inference so
 * the model only picks a letter instead of generating JSON. Generation stays
 * reserved for NEED_TEXT (open-ended bodies, messages) handled by the existing
 * JSON planner fallback.
 */
object UiActionCandidateBuilder {
    const val MAX_CANDIDATES = 8

    data class Built(
        val candidates: List<DecisionCandidate>,
        /** Maps candidate index -> LocalAgentUiControlProtocol.Action factory key. */
        val keys: List<String>,
        /** Human-readable goal spans the model may choose for type_text (no generation). */
        val typeSpans: List<String> = emptyList(),
    )

    fun build(
        goal: String,
        observation: LocalAgentObservation,
        previousResult: String? = null,
    ): Built {
        val out = ArrayList<Pair<String, String>>()
        val nodes = observation.screenSnapshot?.nodes.orEmpty()

        // Prefer clickable nodes with visible text (up to 4), like jev-ultrafast's
        // indexed browser elements.
        val clickables = nodes.filter { it.isClickable && (it.text.isNotBlank() || it.contentDescription.isNotBlank()) }
            .take(4)
        clickables.forEach { n ->
            val label = n.text.ifBlank { n.contentDescription }.trim().take(40)
            out += "click_text" to "Tap \"$label\" (node ${n.index})"
        }
        // Editable field -> focus/type affordance (span selection, not generation).
        nodes.firstOrNull { it.isEditable }?.let { n ->
            val hint = n.hintText.ifBlank { n.text.ifBlank { "field" } }.trim().take(30)
            out += "type_text" to "Type into \"$hint\" (node ${n.index})"
        }
        // Generic navigation primitives (bounded output space).
        if (out.size < MAX_CANDIDATES - 2) out += "scroll" to "Scroll down to reveal more content"
        if (out.size < MAX_CANDIDATES - 1) out += "press_back" to "Press back"
        if (out.size < MAX_CANDIDATES) out += "finish" to "Finish task (goal achieved or impossible)"

        val capped = out.take(MAX_CANDIDATES)
        val labels = listOf("A", "B", "C", "D", "E", "F", "G", "H")
        val candidates = capped.mapIndexed { i, (_, desc) -> DecisionCandidate(labels[i], desc) }
        val keys = capped.map { (key, _) -> key }
        val spans = extractTypeSpans(goal)
        return Built(candidates, keys, spans)
    }

    /**
     * Goal-span extraction for type_text without generation, e.g.
     * "Search YouTube for Mega Lucario deck" -> ["Mega Lucario deck", "YouTube"].
     */
    fun extractTypeSpans(goal: String): List<String> {
        val g = goal.trim()
        if (g.isEmpty()) return emptyList()
        val spans = LinkedHashSet<String>()
        // "for X" / "por X" / "para X" tails are usually the query.
        Regex("""(?i)\b(?:for|por|para|nach|pour|per)\s+(.+)$""").find(g)?.let {
            spans += it.groupValues[1].trim().trimEnd('.', '!', '?').take(80)
        }
        // Quoted spans are literal user text.
        Regex(""""([^"]+)"""").findAll(g).forEach { spans += it.groupValues[1].trim().take(80) }
        spans += g.take(80)
        return spans.filter { it.isNotBlank() }.take(3)
    }
}
