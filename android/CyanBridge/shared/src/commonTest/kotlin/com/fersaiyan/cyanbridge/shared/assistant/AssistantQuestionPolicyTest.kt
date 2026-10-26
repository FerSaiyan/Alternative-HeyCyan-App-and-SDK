package com.fersaiyan.cyanbridge.shared.assistant

import com.fersaiyan.cyanbridge.shared.ai.GlassesAssistantRoute
import com.fersaiyan.cyanbridge.shared.glasses.AiWakeWordRoute
import com.fersaiyan.cyanbridge.shared.settings.AgentProviderType
import com.fersaiyan.cyanbridge.shared.settings.QuestionRoutingMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

class AssistantQuestionPolicyTest {
    @Test
    fun wakeAndProviderRouting() {
        val hears = { true }
        val deaf = { false }
        val unasked = { fail("not needed for this route") }
        assertEquals(WakeQuestion.SPOKEN_DECISION, AssistantQuestionRouting.wakeQuestion(AiWakeWordRoute.VOICE_QUESTION, QuestionRoutingMode.SMART, hears))
        assertEquals(WakeQuestion.SPOKEN_DECISION, AssistantQuestionRouting.wakeQuestion(AiWakeWordRoute.VOICE_QUESTION, QuestionRoutingMode.SHADOW, hears))
        assertEquals(WakeQuestion.VOICE_QUESTION, AssistantQuestionRouting.wakeQuestion(AiWakeWordRoute.VOICE_QUESTION, QuestionRoutingMode.SMART, deaf))
        // Classic never asks whether the model hears audio.
        assertEquals(WakeQuestion.VOICE_QUESTION, AssistantQuestionRouting.wakeQuestion(AiWakeWordRoute.VOICE_QUESTION, QuestionRoutingMode.CLASSIC, unasked))
        assertEquals(WakeQuestion.IMAGE_QUESTION, AssistantQuestionRouting.wakeQuestion(AiWakeWordRoute.IMAGE_QUESTION, QuestionRoutingMode.SMART, hears))

        assertTrue(AssistantQuestionRouting.questionAudioGoesToModel(GlassesAssistantRoute.LOCAL, hears, unasked))
        assertFalse(AssistantQuestionRouting.questionAudioGoesToModel(GlassesAssistantRoute.PRO, unasked, deaf))
        assertFalse(AssistantQuestionRouting.questionAudioGoesToModel(GlassesAssistantRoute.TASKER_EXTERNAL_UI, unasked, unasked))

        assertEquals(AgentProviderType.PRO_SUBSCRIPTION, AssistantQuestionRouting.spokenQuestionProvider(GlassesAssistantRoute.PRO, hears))
        assertEquals(AgentProviderType.LOCAL_AGENT, AssistantQuestionRouting.spokenQuestionProvider(GlassesAssistantRoute.PRO, deaf))
        assertEquals(AgentProviderType.LOCAL_AGENT, AssistantQuestionRouting.spokenQuestionProvider(GlassesAssistantRoute.LOCAL, unasked))

        val pro = { AgentProviderType.PRO_SUBSCRIPTION }
        val tasker = { AgentProviderType.TASKER }
        assertEquals(AgentProviderType.PRO_SUBSCRIPTION, AssistantQuestionRouting.imageQuestionProvider(GlassesAssistantRoute.LOCAL, true, pro, unasked))
        assertEquals(AgentProviderType.LOCAL_AGENT, AssistantQuestionRouting.imageQuestionProvider(GlassesAssistantRoute.LOCAL, false, unasked, unasked))
        assertEquals(AgentProviderType.TASKER, AssistantQuestionRouting.imageQuestionProvider(GlassesAssistantRoute.TASKER_EXTERNAL_UI, false, unasked, tasker))
        assertEquals(null, AssistantQuestionRouting.imageQuestionProvider(GlassesAssistantRoute.PHONE_ASSISTANT, false, unasked, unasked))
        assertEquals(null, AssistantQuestionRouting.liveCandidateProvider(GlassesAssistantRoute.PHONE_ASSISTANT, unasked))
    }

    @Test
    fun visionRepliesNeverReadOutErrors() {
        assertEquals(VisionReply("Quota used up", "Quota used up"), VisionReplyPolicy.relayFailure(true, "Quota used up", "429"))
        assertEquals(
            VisionReply(VisionReplyPolicy.ANALYSIS_FAILED, "Vision error: timeout"),
            VisionReplyPolicy.relayFailure(false, "localized", "timeout"),
        )
        assertEquals(VisionReply(VisionReplyPolicy.EMPTY_REPLY), VisionReplyPolicy.relayReply("  ", detectVisionFailure = true))
        assertEquals(
            VisionReply(VisionReplyPolicy.ANALYSIS_FAILED, VisionReplyPolicy.VISION_FAILED_TOAST),
            VisionReplyPolicy.relayReply("Please upload the image again.", detectVisionFailure = true),
        )
        assertEquals(VisionReply("Please upload the image again."), VisionReplyPolicy.relayReply("Please upload the image again.", false))
        assertEquals(VisionReply("A red mug."), VisionReplyPolicy.relayReply(" A red mug. ", true))
        assertEquals(VisionReplyPolicy.NO_ANSWER, VisionReplyPolicy.spoken(""))
    }

    @Test
    fun webAnswerPlans() {
        fun web(answer: String? = null, infoBox: String? = null, snippets: List<String> = emptyList(), google: Boolean = false, failure: String? = null) =
            WebSearchOutcome("E", google, answer, infoBox, snippets, failure, offline = failure == "NETWORK", blocked = false, elapsedMs = 0)
        val cases = listOf(
            Triple(web(answer = "A", infoBox = "Box", snippets = listOf("1", "2", "3")), false, WebAnswerPlan.FromModel(null, listOf("1", "2"), "Box")),
            Triple(web(answer = "A", google = true), false, WebAnswerPlan.ReadOut("A")),
            Triple(web(answer = "A"), true, WebAnswerPlan.ReadOut("A")),
            Triple(web(answer = "A"), false, WebAnswerPlan.FromModel("A", emptyList(), null)),
            Triple(web(snippets = listOf("1")), false, WebAnswerPlan.FromModel(null, listOf("1"), null)),
            Triple(web(), false, WebAnswerPlan.Fixed(nothingFound = true, offline = false)),
            Triple(web(failure = "NETWORK"), false, WebAnswerPlan.Fixed(nothingFound = false, offline = true)),
            Triple(null, false, WebAnswerPlan.Fixed(nothingFound = false, offline = false)),
        )
        for ((outcome, english, expected) in cases) assertEquals(expected, WebAnswerPolicy.plan(outcome, english), "$outcome english=$english")
    }

    @Test
    fun memorySelectionAndPromptOrder() {
        val items = listOf("- Dentist on Friday at 3pm", "* Buy coffee beans", "Call mom", "Call mom", " ")
        assertEquals(listOf("Dentist on Friday at 3pm"), AssistantMemorySelection.selectRelevant(items, "When is the dentist?", 3))
        assertEquals(listOf("Dentist on Friday at 3pm", "Buy coffee beans"), AssistantMemorySelection.selectRelevant(items, "what is this", 3))
        assertEquals(listOf("dentist", "friday"), AssistantMemorySelection.tokenize("The dentist, Friday! the"))

        val spoken = AssistantSystemPrompt.build(
            memoryContext = "MEMORY", spokenQuestion = true,
            responseLanguageInstruction = { fail("text-only") }, spokenQuestionInstruction = { "SPOKEN" },
            decisionRoutes = "ROUTES", extraInstruction = "EXTRA", spokenMemory = { "CORE" }, currentContext = { "NOW" },
        )
        assertEquals("MEMORY\n\nSPOKEN\n\nROUTES\n\nEXTRA\n\nCORE\n\nNOW", spoken)
        val text = AssistantSystemPrompt.build(
            memoryContext = "", spokenQuestion = false,
            responseLanguageInstruction = { "LANG" }, spokenQuestionInstruction = { fail("spoken only") },
            decisionRoutes = null, extraInstruction = " ", spokenMemory = { fail("spoken only") }, currentContext = { "NOW" },
        )
        assertEquals("LANG\n\nNOW", text)
    }

    @Test
    fun followUpsAndTheConversationWindow() {
        assertTrue(SpokenFollowUpPolicy.listensForFollowUp(true, SpokenRoute.SEARCH))
        assertFalse(SpokenFollowUpPolicy.listensForFollowUp(true, SpokenRoute.PHONE))
        assertFalse(SpokenFollowUpPolicy.listensForFollowUp(false, SpokenRoute.ANSWER))

        SpokenConversation.reset()
        assertFalse(SpokenConversation.canContinue(now = 1_000L))
        SpokenConversation.turnEnded(now = 1_000L)
        assertTrue(SpokenConversation.canContinue(now = 1_000L + SpokenConversation.CONTINUE_WITHIN_MS - 1))
        assertFalse(SpokenConversation.canContinue(now = 1_000L + SpokenConversation.CONTINUE_WITHIN_MS))
        SpokenConversation.pendingNote = "note"
        assertEquals("note", SpokenConversation.takeNote())
        assertEquals(null, SpokenConversation.takeNote())
        SpokenConversation.reset()
    }
}
