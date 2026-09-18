package com.fersaiyan.cyanbridge.ai.decision

/**
 * Parses a model's single-letter answer robustly.
 *
 * Must tolerate reasoning/thinking models that ignore the "single letter only"
 * instruction and emit e.g.:
 * - "<think>...analysis...</think> C"
 * - "Let me think... The user wants Spotify, so Answer: C"
 * - "``` C ```", "(C)", "C.", "option c", "c"
 * - Multilingual preamble in PT/ES/FR/DE/ZH before the letter.
 *
 * Strategy: strip known reasoning envelopes, then take the LAST standalone
 * A-H letter (reasoning models usually conclude with the answer).
 */
object DecisionOutputParser {
    private val THINK_BLOCK = Regex("""(?is)<think>.*?</think>""")
    private val THINKING_BLOCK = Regex("""(?is)<thinking>.*?</thinking>""")
    private val FENCED_BLOCK = Regex("""(?is)```(?:json|text)?\s*([\s\S]*?)\s*```""")
    private val STANDALONE_LETTER = Regex("""(?i)(?:^|[\s:(\["'“”‘’\-])([A-H])(?:[\s.\-)}\]"'“”‘’,;:!?]|$)""")

    data class Parsed(val index: Int, val label: String, val cleanedForLog: String)

    fun parse(raw: String, candidates: List<DecisionCandidate>): Parsed {
        val cleaned = stripReasoningEnvelopes(raw)
        val validLabels = candidates.map { it.label }.toSet()
        val matches = STANDALONE_LETTER.findAll(" $cleaned ").toList()
        // Prefer the last match: reasoning preamble may mention letters early
        // ("A means answer, B means image...") while the final answer is last.
        for (m in matches.asReversed()) {
            val letter = m.groupValues[1].uppercase()
            if (letter in validLabels) {
                val idx = candidates.indexOfFirst { it.label == letter }
                return Parsed(idx, letter, cleaned.take(300))
            }
        }
        throw IllegalArgumentException(
            "No valid option letter ${validLabels} in model output: '${cleaned.take(200)}'"
        )
    }

    fun stripReasoningEnvelopes(raw: String): String {
        var s = raw
        s = THINK_BLOCK.replace(s, " ")
        s = THINKING_BLOCK.replace(s, " ")
        // Unclosed <think> (truncated streaming): drop only the opening tag, keep
        // the inner text searchable. Nuking to end-of-string would delete a valid
        // trailing answer such as "<think>reasoning... C".
        s = Regex("""(?is)<think>""").replace(s, " ")
        s = Regex("""(?is)<thinking>""").replace(s, " ")
        s = Regex("""(?is)</think>""").replace(s, " ")
        s = Regex("""(?is)</thinking>""").replace(s, " ")
        FENCED_BLOCK.find(s)?.let { m ->
            val inner = m.groupValues.getOrNull(1)?.trim().orEmpty()
            if (inner.isNotBlank()) s = inner
        }
        return s.replace(Regex("""\s+"""), " ").trim()
    }
}
