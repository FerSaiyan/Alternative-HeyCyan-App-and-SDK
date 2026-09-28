package com.fersaiyan.cyanbridge.ai.live

import com.fersaiyan.cyanbridge.ai.decision.AssistantDecisionOptions
import com.fersaiyan.cyanbridge.ai.decision.DecisionCandidate
import com.fersaiyan.cyanbridge.ai.decision.LocalDecision
import com.fersaiyan.cyanbridge.ai.decision.SingleTokenDecisionEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiLiveControlRouterTest {
    private fun fakeEngine(letter: String) = SingleTokenDecisionEngine(generate = { _, _ -> letter })

    @Test fun `accumulates fragments without classifying`() {
        val router = GeminiLiveControlRouter(engineProvider = { fakeEngine("B") })
        router.appendTranscript("That's")
        router.appendTranscript("all, stop")
        println("JEV_CI buffered='${router.bufferedText()}'")
        assertTrue(router.bufferedText().contains("That's"))
        // Heuristic path alone already detects END; model path tested below.
        val action = router.classifyHeuristicNow()
        println("JEV_CI heuristic action=$action")
        assertEquals(LiveControlAction.END_LIVE, action)
    }

    @Test fun `portuguese end phrase detected heuristically`() {
        val router = GeminiLiveControlRouter()
        router.appendTranscript("Obrigado, pode encerrar.")
        val action = router.classifyHeuristicNow()
        println("JEV_CI pt end action=$action buf='${router.bufferedText()}'")
        assertEquals(LiveControlAction.END_LIVE, action)
    }

    @Test fun `spanish french german chinese end phrases`() {
        val cases = mapOf(
            "Termine la conversación, gracias." to LiveControlAction.END_LIVE,
            "Merci, tu peux arrêter maintenant." to LiveControlAction.END_LIVE,
            "Danke, das war's. Tschüss." to LiveControlAction.END_LIVE,
            "结束对话，谢谢。" to LiveControlAction.END_LIVE,
            "That's everything, thanks!" to LiveControlAction.END_LIVE,
        )
        cases.forEach { (text, expected) ->
            val router = GeminiLiveControlRouter()
            router.appendTranscript(text)
            val got = router.classifyHeuristicNow()
            println("JEV_CI live-i18n text='$text' -> $got")
            assertEquals("text=$text", expected, got)
        }
    }

    @Test fun `normal chat continues`() {
        val router = GeminiLiveControlRouter()
        router.appendTranscript("Tell me more about black holes.")
        val action = router.classifyHeuristicNow()
        println("JEV_CI continue action=$action")
        assertEquals(LiveControlAction.CONTINUE, action)
    }

    @Test fun `model path classifies at utterance end`() = runBlocking {
        val router = GeminiLiveControlRouter(engineProvider = { fakeEngine("B") })
        // Use a phrase the heuristic does NOT catch, forcing the model path.
        router.appendTranscript("I am done here, please wrap up this call for me.")
        val action = router.classifyAtUtteranceEnd()
        println("JEV_CI model-path action=$action last=${router.lastDecision()}")
        // Fake always returns B; router must map to END_LIVE given high pseudo-confidence.
        assertEquals(LiveControlAction.END_LIVE, action)
    }

    @Test fun `low confidence stays in live`() = runBlocking {
        val lowConfEngine = object : com.fersaiyan.cyanbridge.ai.decision.LocalDecisionEngine {
            override suspend fun choose(
                state: String,
                candidates: List<DecisionCandidate>,
                debugTag: String,
            ): LocalDecision = LocalDecision(1, "B", 0.4f, floatArrayOf(0.3f, 0.4f, 0.3f), "B")
        }
        val router = GeminiLiveControlRouter(engineProvider = { lowConfEngine })
        router.appendTranscript("I am done here, please wrap up this call for me.")
        val action = router.classifyAtUtteranceEnd()
        println("JEV_CI lowconf action=$action")
        assertEquals(LiveControlAction.CONTINUE, action)
    }

    @Test fun `blank buffer continues`() = runBlocking {
        val router = GeminiLiveControlRouter(engineProvider = { fakeEngine("B") })
        assertEquals(LiveControlAction.CONTINUE, router.classifyAtUtteranceEnd())
    }
}
