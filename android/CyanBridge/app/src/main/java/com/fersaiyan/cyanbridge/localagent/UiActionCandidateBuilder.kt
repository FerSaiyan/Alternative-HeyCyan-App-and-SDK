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
        /** Exact accessibility node selected by each candidate, when applicable. */
        val nodeIndices: List<Int?>,
        /** Human-readable goal spans the model may choose for type_text (no generation). */
        val typeSpans: List<String> = emptyList(),
    )

    private data class Option(
        val key: String,
        val description: String,
        val nodeIndex: Int? = null,
    )

    fun build(
        goal: String,
        observation: LocalAgentObservation,
        previousResult: String? = null,
    ): Built {
        val out = ArrayList<Option>()
        val nodes = observation.screenSnapshot?.nodes.orEmpty()
        val spans = extractTypeSpans(goal)

        // Opening the app is commonly the first step, before its controls are observable.
        // Keep it ahead of unrelated launcher/fixture nodes so it survives the A-H cap.
        extractTargetApp(goal)?.let { appName ->
            val packageName = observation.packageName.orEmpty().lowercase()
            val appToken = appName.lowercase().replace(Regex("[^a-z0-9]"), "")
            if (appToken.isNotBlank() && !packageName.replace(Regex("[^a-z0-9]"), "").contains(appToken)) {
                out += Option("open_app", "Open $appName")
            }
        }

        // Editable field -> focus/type affordance (span selection, not generation). Put this ahead
        // of text nodes because AutoInput's compact contract does not preserve actionability and
        // otherwise static headings can occupy the first labels. Once the target text is visible,
        // suppress duplicate typing so the next decision can submit/open a result.
        val targetSpan = spans.firstOrNull().orEmpty()
        val visibleText = buildString {
            append(observation.screenText.orEmpty())
            append(' ')
            append(observation.screenSnapshot?.textSummary.orEmpty())
            nodes.forEach { node ->
                append(' ')
                append(candidateLabel(node))
            }
        }
        val targetAlreadyVisible = targetSpan.length >= 3 &&
            containsNormalizedSpan(visibleText, targetSpan)
        if (!targetAlreadyVisible) {
            nodes.firstOrNull { it.isEditable || looksLikeEditableId(it.viewId) }?.let { n ->
                val hint = n.hintText
                    .ifBlank { n.text }
                    .ifBlank { n.viewId.substringAfterLast('/') }
                    .ifBlank { "field" }
                    .trim()
                    .take(30)
                out += Option("type_text", "Focus and type into \"$hint\" (node ${n.index})", n.index)
            }
        }

        // AutoInput's compact Tasker contract currently returns text/id/coordinates but does not
        // expose clickable/editable flags. Prefer control-like labels, declared clickable nodes,
        // and goal matches, while excluding machine-readable fixture/status markers.
        val goalKeywords = LocalAgentScreenSnapshot.extractGoalKeywords(goal)
        val readyForGroundedAnswer = looksReadyForGroundedAnswer(
            goal = goal,
            visibleText = visibleText,
            targetSpan = targetSpan,
        )
        if (readyForGroundedAnswer) {
            out += Option(
                "detailed_planner",
                "Use the detailed planner now to return the grounded final answer from this page",
            )
        }
        val clickables = nodes
            .filter {
                val label = candidateLabel(it)
                label.isNotBlank() &&
                    !it.isEditable &&
                    !looksLikeEditableId(it.viewId) &&
                    !looksLikeMachineMarker(label) &&
                    !looksLikeBrowserChrome(label)
            }
            .sortedWith(
                compareByDescending<LocalAgentScreenNode> {
                    candidatePriority(candidateLabel(it), targetSpan, goalKeywords)
                }
                    .thenByDescending { it.isClickable }
                    .thenByDescending { it.viewId.isNotBlank() }
                    .thenBy { it.index },
            )
            .take(4)
        clickables.forEach { n ->
            val label = candidateLabel(n).take(40)
            out += Option("click_node", "Tap \"$label\" (node ${n.index})", n.index)
        }
        // Generic navigation primitives (bounded output space).
        if (out.size < MAX_CANDIDATES - 2) out += Option("scroll", "Scroll down to reveal more content")
        if (out.size < MAX_CANDIDATES - 1) out += Option("press_back", "Press back")
        if (out.size < MAX_CANDIDATES && !readyForGroundedAnswer) {
            out += Option(
                "detailed_planner",
                "Use the detailed planner for another action or a grounded final answer",
            )
        }

        val capped = out.take(MAX_CANDIDATES)
        val labels = listOf("A", "B", "C", "D", "E", "F", "G", "H")
        val candidates = capped.mapIndexed { i, option -> DecisionCandidate(labels[i], option.description) }
        val keys = capped.map { it.key }
        val nodeIndices = capped.map { it.nodeIndex }
        return Built(candidates, keys, nodeIndices, spans)
    }

    /**
     * Goal-span extraction for type_text without generation, e.g.
     * "Search YouTube for Mega Lucario deck" -> ["Mega Lucario deck", "YouTube"].
     */
    fun extractTypeSpans(goal: String): List<String> {
        val g = goal.trim()
        if (g.isEmpty()) return emptyList()
        val spans = LinkedHashSet<String>()
        // Prefer a bounded search phrase over the full remainder of a multi-step goal.
        Regex(
            """(?i)\bsearch(?:\s+\w+)?\s+for\s+(.+?)(?=,|\bthen\b|\band\s+(?:open|play|tap|select|start)\b|$)""",
        ).find(g)?.let {
            spans += it.groupValues[1]
                .trim()
                .trimEnd('.', '!', '?')
                .trim('"', '\'')
                .take(80)
        }
        // Quoted spans are literal user text.
        Regex(""""([^"]+)"""").findAll(g).forEach { spans += it.groupValues[1].trim().take(80) }
        Regex("'([^']+)'").findAll(g).forEach { spans += it.groupValues[1].trim().take(80) }
        // "for X" / "por X" / "para X" tails are useful only when no more precise
        // search clause or quoted literal was found.
        if (spans.isEmpty()) {
            Regex("""(?i)\b(?:for|por|para|nach|pour|per)\s+(.+)$""").find(g)?.let {
                spans += it.groupValues[1].trim().trimEnd('.', '!', '?').take(80)
            }
        }
        spans += g.take(80)
        return spans.filter { it.isNotBlank() }.take(3)
    }

    fun extractTargetApp(goal: String): String? {
        val match = Regex(
            """(?i)^\s*(?:please\s+)?(?:open|launch|start|go\s+to)\s+([\p{L}\p{N}][\p{L}\p{N} .&+_-]*?)(?=\s*(?:[.,;]|\band\b|\bthen\b|\bto\b|$))""",
        ).find(goal) ?: return null
        return match.groupValues[1].trim().take(60).ifBlank { null }
    }

    private fun candidateLabel(node: LocalAgentScreenNode): String = node.text
        .ifBlank { node.contentDescription }
        .ifBlank { node.hintText }
        .ifBlank { node.viewId.substringAfterLast('/') }
        .trim()

    private fun looksLikeEditableId(viewId: String): Boolean {
        val id = viewId.substringAfterLast('/').lowercase()
        return EDITABLE_ID_MARKERS.any { marker -> id.contains(marker) }
    }

    private fun looksLikeMachineMarker(label: String): Boolean {
        val trimmed = label.trim()
        return trimmed.length >= 12 &&
            trimmed.none { it.isLowerCase() } &&
            trimmed.count { it == '_' } >= 2
    }

    private fun actionLabelScore(label: String): Int {
        val normalized = label.trim().lowercase().replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
        if (normalized in EXACT_ACTION_LABELS) return 3
        if (EXACT_ACTION_LABELS.any { action -> normalized.startsWith("$action ") }) return 2
        return 0
    }

    private fun candidatePriority(
        label: String,
        targetSpan: String,
        goalKeywords: List<String>,
    ): Int {
        val lower = label.lowercase()
        val targetMatch = targetSpan.length >= 3 && containsNormalizedSpan(label, targetSpan)
        val resultCue = lower.contains("first result")
        val keywordMatches = goalKeywords.distinct().count { keyword -> lower.contains(keyword) }
        return when {
            resultCue -> 1_000 + keywordMatches
            targetMatch -> 800 + keywordMatches
            else -> actionLabelScore(label) * 100 + keywordMatches
        }
    }

    private fun looksLikeBrowserChrome(label: String): Boolean {
        val lower = label.trim().lowercase()
        return lower == "open the context popup" ||
            lower == "open the home page" ||
            lower == "customize and control google chrome" ||
            lower.startsWith("switch or close tabs")
    }

    private fun containsNormalizedSpan(text: String, span: String): Boolean {
        fun normalize(value: String): String = value.lowercase()
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
        val normalizedSpan = normalize(span)
        return normalizedSpan.isNotBlank() && normalize(text).contains(normalizedSpan)
    }

    private fun looksReadyForGroundedAnswer(
        goal: String,
        visibleText: String,
        targetSpan: String,
    ): Boolean {
        if (!FINAL_ANSWER_GOAL.containsMatchIn(goal)) return false
        val lower = visibleText.lowercase()
        if (lower.contains("first result") || lower.contains("search results")) return false
        val targetKeywords = LocalAgentScreenSnapshot.extractGoalKeywords(targetSpan).distinct()
        val matchingKeywords = targetKeywords.count { keyword -> lower.contains(keyword) }
        return visibleText.length >= 80 && matchingKeywords >= minOf(2, targetKeywords.size)
    }

    private val EDITABLE_ID_MARKERS = listOf(
        "edit_text",
        "edittext",
        "input",
        "query",
        "search_box",
        "searchbox",
        "url_bar",
        "textfield",
        "text_field",
    )

    private val EXACT_ACTION_LABELS = setOf(
        "search", "play", "pause", "open", "next", "continue", "submit", "send",
        "go", "done", "ok", "allow", "confirm", "select", "save", "start",
    )
    private val FINAL_ANSWER_GOAL = Regex(
        """(?i)\b(summar(?:y|ize|ise)|what\s+(?:the\s+)?page\s+says|tell\s+me|read\s+(?:the\s+)?page|answer)\b""",
    )
}
