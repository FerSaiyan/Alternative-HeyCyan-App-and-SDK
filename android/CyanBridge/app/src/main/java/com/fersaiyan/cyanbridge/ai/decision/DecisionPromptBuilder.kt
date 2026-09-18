package com.fersaiyan.cyanbridge.ai.decision

/**
 * Builds single-letter classification prompts.
 *
 * Design notes for reasoning / "thinking" models (Qwen3.5, DeepSeek, etc.):
 * - We explicitly instruct NO thinking, NO <think> blocks, single letter only.
 * - The parser ([DecisionOutputParser]) still tolerates reasoning preamble,
 *   because some models emit it anyway.
 */
object DecisionPromptBuilder {
    const val MAX_STATE_CHARS = 2000
    const val MAX_CANDIDATE_DESC_CHARS = 220

    fun buildClassificationPrompt(state: String, candidates: List<DecisionCandidate>): String {
        require(candidates.size in 2..8) { "Need 2..8 candidates, got ${candidates.size}" }
        val trimmedState = state.trim().take(MAX_STATE_CHARS)
        return buildString {
            appendLine("Classify the user's request. Reply with ONLY the single letter of the best option.")
            appendLine("No explanation. No thinking. No <think> blocks. No punctuation. Just one letter.")
            appendLine()
            appendLine("Options:")
            candidates.forEach { c ->
                appendLine("${c.label} = ${c.description.trim().take(MAX_CANDIDATE_DESC_CHARS)}")
            }
            appendLine()
            appendLine("User request:")
            appendLine(trimmedState.ifBlank { "(empty)" })
            appendLine()
            append("Answer:")
        }.trim()
    }

    fun buildLiveControlPrompt(transcript: String): String {
        return buildClassificationPrompt(
            state = transcript,
            candidates = LiveControlOptions.candidates(),
        )
    }

    fun buildUiActionPrompt(
        goal: String,
        screenSummary: String,
        candidates: List<DecisionCandidate>,
    ): String {
        require(candidates.size in 2..8) { "Need 2..8 UI candidates, got ${candidates.size}" }
        return buildString {
            appendLine("You operate the phone. Reply with ONLY the single letter of the next action.")
            appendLine("No explanation. No thinking. No <think> blocks. Just one letter.")
            appendLine()
            appendLine("Goal:")
            appendLine(goal.trim().take(500).ifBlank { "(no goal)" })
            appendLine()
            appendLine("Current screen:")
            appendLine(screenSummary.trim().take(3000).ifBlank { "(screen unreadable)" })
            appendLine()
            appendLine("Available actions:")
            candidates.forEach { c ->
                appendLine("${c.label} = ${c.description.trim().take(MAX_CANDIDATE_DESC_CHARS)}")
            }
            appendLine()
            append("Next action:")
        }.trim()
    }

    fun systemPromptForSingleLetter(): String =
        "You are a precise classifier. Always reply with exactly one capital letter (A, B, C, D, E, F, G or H) and nothing else. Never reason out loud."
}
