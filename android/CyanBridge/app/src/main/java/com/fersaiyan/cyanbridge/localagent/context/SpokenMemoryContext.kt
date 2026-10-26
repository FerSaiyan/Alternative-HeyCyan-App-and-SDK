package com.fersaiyan.cyanbridge.localagent.context

import android.content.Context
import com.fersaiyan.cyanbridge.localagent.memory.LocalAgentMemoryStore

/**
 * Compact memory for glasses spoken questions, sent as the recording alone: there is no question text to
 * search memory with, so this is the query-free core (user facts, today's confirmed facts, today's summary).
 * Seeded templates ("- Name: ", "(Generate from Settings …)") and headings are dropped, so a user without
 * memory adds nothing to the prompt. Kept small for 4k-context local models (~250 tokens at the cap).
 */
object SpokenMemoryContext {
    const val HEADER = "What you know about the user (use it only when their question needs it):"
    const val MAX_CHARS = 1_000

    fun build(context: Context, date: String): String = runCatching {
        compact(
            userFacts = LocalAgentMemoryStore.readText(LocalAgentMemoryStore.userFactsFile(context)),
            confirmedDailyFacts = LocalAgentMemoryStore.readText(LocalAgentMemoryStore.confirmedDailyFactsFileForDate(context, date)),
            dailySummary = LocalAgentMemoryStore.readText(LocalAgentMemoryStore.dailySummaryFileForDate(context, date)),
        )
    }.getOrDefault("")

    fun compact(userFacts: String, confirmedDailyFacts: String, dailySummary: String, maxChars: Int = MAX_CHARS): String {
        val lines = facts(userFacts) + facts(confirmedDailyFacts).map { "Today: $it" } + summary(dailySummary)
        if (lines.isEmpty()) return ""
        val out = StringBuilder(HEADER)
        for (line in lines) {
            val next = "\n- $line"
            if (out.length + next.length > maxChars) break
            out.append(next)
        }
        return if (out.length == HEADER.length) "" else out.toString()
    }

    /** Non-empty entries of a facts file: bullet or plain lines, without headings or "- Key: " placeholders. */
    private fun facts(markdown: String): List<String> = markdown.lines().mapNotNull { raw ->
        val line = raw.trim()
        if (line.isEmpty() || line.startsWith("#")) return@mapNotNull null
        val text = line.removePrefix("- ").removePrefix("* ").trim()
        text.takeUnless { it.isEmpty() || it == "-" || it.endsWith(":") }
    }

    private fun summary(markdown: String): List<String> {
        if (markdown.contains("(Generate from Settings", ignoreCase = true)) return emptyList()
        val text = markdown.lines().filterNot { it.trim().startsWith("#") }.joinToString(" ").replace(Regex("\\s+"), " ").trim()
        return if (text.isEmpty()) emptyList() else listOf("Today's summary: ${text.take(300)}")
    }
}
