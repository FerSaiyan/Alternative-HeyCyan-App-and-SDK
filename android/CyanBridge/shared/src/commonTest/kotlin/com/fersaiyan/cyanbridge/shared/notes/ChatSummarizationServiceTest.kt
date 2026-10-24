package com.fersaiyan.cyanbridge.shared.notes

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ChatSummarizationServiceTest {
    @Test
    fun promptAsksForTheFixedSectionsAndReplyIsSplitIntoThem() = runTest {
        var prompt = ""
        val service = ChatSummarizationService { sent ->
            prompt = sent
            """
            Title: Launch sync
            Key Bullets:
            - Beta ships Friday
            * QA signed off
            Action Items:
            1. Ana writes notes
            Key Decisions:
            - Keep the old API
            Open Questions:
            - Pricing?
            """.trimIndent()
        }
        val summary = service.summarize(SummarizationRequest("We talked.", hintTitle = null))
        assertTrue(prompt.startsWith("Analyze the following meeting transcript"))
        assertTrue(prompt.contains("Use 5-10 concise bullet points"))
        assertTrue(prompt.endsWith("Transcript:\nWe talked."))
        assertEquals(
            StructuredSummary(
                title = "Launch sync",
                summaryBullets = listOf("Beta ships Friday", "QA signed off"),
                actionItems = listOf("Ana writes notes"),
                keyDecisions = listOf("Keep the old API"),
                openQuestions = listOf("Pricing?"),
            ),
            summary,
        )
    }

    @Test
    fun hintTitleWinsAndFreeTextBecomesOneBullet() = runTest {
        val summary = ChatSummarizationService { "Just prose." }.summarize(SummarizationRequest("t", hintTitle = "Standup"))
        assertEquals("Standup", summary.title)
        assertEquals(listOf("Just prose."), summary.summaryBullets)
    }

    @Test
    fun emptyTranscriptSkipsTheModelAndBlankRepliesFail() = runTest {
        val empty = ChatSummarizationService { error("not called") }.summarize(SummarizationRequest("  "))
        assertEquals("Empty Note", empty.title)
        assertFailsWith<IllegalStateException> { ChatSummarizationService { " " }.summarize(SummarizationRequest("t")) }
    }
}
