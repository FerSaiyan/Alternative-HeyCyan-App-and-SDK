package com.fersaiyan.cyanbridge.ai.summarization

import android.content.Context
import com.fersaiyan.cyanbridge.ai.router.CliRelayClient
import com.fersaiyan.cyanbridge.shared.notes.StructuredSummary
import com.fersaiyan.cyanbridge.shared.notes.SummarizationRequest
import com.fersaiyan.cyanbridge.shared.notes.SummarizationService

/**
 * AI-powered summarization service that delegates to local or cloud LLM models
 * via CliRelayClient based on user preferences.
 */
class AiSummarizationService(
    private val context: Context,
) : SummarizationService {

    override suspend fun summarize(request: SummarizationRequest): StructuredSummary {
        // RAG profile NONE: meeting notes summarize only this transcript.
        val transcript = request.transcript.trim()
        if (transcript.isBlank()) {
            return StructuredSummary(
                title = request.hintTitle ?: "Empty Note",
                summaryBullets = listOf("(No content to summarize)"),
                actionItems = emptyList(),
                keyDecisions = emptyList(),
                openQuestions = emptyList(),
            )
        }

        val prompt = buildString {
            append("Analyze the following meeting transcript and generate factual Markdown-ready notes.\n")
            request.hintTitle?.let { append("Title context: $it\n") }
            append("Use ${request.minSummaryBullets}-${request.maxSummaryBullets} concise bullet points for the key summary. ")
            append("Do not invent details, speakers, decisions, or tasks.\n")
            append("Respond with:\n")
            append("Title: <concise summary title>\n")
            append("Key Bullets:\n- <bullet 1>\n- <bullet 2>\n")
            append("Action Items:\n- <action 1>\n")
            append("Key Decisions:\n- <decision 1>\n")
            append("Open Questions:\n- <question 1>\n\n")
            append("Transcript:\n$transcript")
        }

        val response = CliRelayClient.chat(
            context = context,
            chatId = "ai-summarization",
            prompt = prompt,
            messages = listOf(mapOf("role" to "user", "content" to prompt)),
        ).getOrElse { error ->
            throw IllegalStateException("Meeting summary model request failed", error)
        }.trim()
        check(response.isNotBlank()) { "Meeting summary model returned an empty response" }
        return parseResponse(response, request.hintTitle, transcript)
    }

    private fun parseResponse(response: String, hintTitle: String?, transcript: String): StructuredSummary {
        val lines = response.lines().map { it.trim() }
        var title = hintTitle ?: ""
        val bullets = mutableListOf<String>()
        val actions = mutableListOf<String>()
        val decisions = mutableListOf<String>()
        val questions = mutableListOf<String>()

        var currentSection = ""
        for (line in lines) {
            when {
                line.startsWith("Title:", ignoreCase = true) -> {
                    if (title.isBlank()) {
                        title = line.substringAfter(":").trim()
                    }
                }
                line.startsWith("Key Bullets:", ignoreCase = true) || line.startsWith("Summary:", ignoreCase = true) -> {
                    currentSection = "bullets"
                }
                line.startsWith("Action Items:", ignoreCase = true) || line.startsWith("Actions:", ignoreCase = true) -> {
                    currentSection = "actions"
                }
                line.startsWith("Key Decisions:", ignoreCase = true) || line.startsWith("Decisions:", ignoreCase = true) -> {
                    currentSection = "decisions"
                }
                line.startsWith("Open Questions:", ignoreCase = true) || line.startsWith("Questions:", ignoreCase = true) -> {
                    currentSection = "questions"
                }
                line.startsWith("-") || line.startsWith("*") || line.matches(Regex("^\\d+\\..*")) -> {
                    val item = line.replaceFirst(Regex("^[-*\\d.]+\\s*"), "").trim()
                    if (item.isNotBlank()) {
                        when (currentSection) {
                            "bullets" -> bullets.add(item)
                            "actions" -> actions.add(item)
                            "decisions" -> decisions.add(item)
                            "questions" -> questions.add(item)
                            else -> bullets.add(item)
                        }
                    }
                }
            }
        }

        if (title.isBlank()) {
            title = hintTitle ?: transcript.take(50).ifBlank { "Meeting Summary" }
        }
        if (bullets.isEmpty()) {
            bullets.add(response.take(200))
        }

        return StructuredSummary(
            title = title,
            summaryBullets = bullets,
            actionItems = actions,
            keyDecisions = decisions,
            openQuestions = questions,
        )
    }
}
