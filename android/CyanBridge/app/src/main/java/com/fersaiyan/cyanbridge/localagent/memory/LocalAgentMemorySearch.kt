package com.fersaiyan.cyanbridge.localagent.memory

import android.content.Context
import com.fersaiyan.cyanbridge.integrations.knowledge.ImportedKnowledgeIndex
import com.fersaiyan.cyanbridge.memoryvault.MemorySearchOrchestrator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * Backward-compatible entry point for local memory retrieval.
 *
 * Existing callers keep using this API while internals are orchestrated by
 * the privacy-aware memory vault search layer.
 */
object LocalAgentMemorySearch {
    private const val DEFAULT_FACT_LOOKBACK_DAYS: Int = 7
    private const val DEFAULT_TOP_FACTS: Int = 6
    private const val DEFAULT_TOP_SUMMARY_LINES: Int = 5

    fun buildRelevantMemoryBlock(
        context: Context,
        queryText: String,
        date: String,
        lookbackDaysFacts: Int = DEFAULT_FACT_LOOKBACK_DAYS,
        topFacts: Int = DEFAULT_TOP_FACTS,
        topSummaryLines: Int = DEFAULT_TOP_SUMMARY_LINES,
        maxChars: Int = 1400,
        ragProfile: RagProfile = RagProfile.FULL,
    ): String {
        if (ragProfile == RagProfile.NONE) return ""
        return runCatching {
            runBlocking(Dispatchers.IO) {
                val importedAllowed = ragProfile == RagProfile.FULL &&
                    ImportedKnowledgeIndex.mayInjectIntoCurrentPrompt(context)
                // Reserve explicit room for imported RAG when it is eligible. Without this split,
                // the legacy memory block can consume maxChars before imported context is appended.
                val importedBudget = if (importedAllowed) (maxChars * 0.36f).toInt().coerceAtLeast(420) else 0
                val coreBudget = (maxChars - importedBudget).coerceAtLeast(600).coerceAtMost(maxChars)

                val core = MemorySearchOrchestrator.buildRelevantMemoryBlock(
                    context = context,
                    queryText = queryText,
                    date = date,
                    params = MemorySearchOrchestrator.SearchParams(
                        lookbackDaysFacts = lookbackDaysFacts,
                        topFacts = topFacts,
                        topSummaryLines = topSummaryLines,
                        topScreenHits = 3,
                        maxChars = coreBudget,
                    ),
                )
                // Inbound integrations are intentionally local-only at prompt time.
                // ImportedKnowledgeIndex returns an empty block whenever a relay/cloud
                // provider is selected, so private Obsidian/ChatGPT/Claude material is
                // never silently forwarded to an external AI service.
                val imported = if (importedAllowed) {
                    ImportedKnowledgeIndex.relevantBlock(
                        context = context,
                        query = queryText,
                        maxChars = importedBudget,
                    )
                } else {
                    ""
                }
                listOf(core, imported)
                    .filter { it.isNotBlank() }
                    .joinToString("\n\n")
                    .let { combined ->
                        if (combined.length <= maxChars) combined else combined.take(maxChars).trimEnd() + "…"
                    }
            }
        }.getOrDefault("")
    }
}
