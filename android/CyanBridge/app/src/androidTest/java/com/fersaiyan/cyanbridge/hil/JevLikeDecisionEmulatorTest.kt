package com.fersaiyan.cyanbridge.hil

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fersaiyan.cyanbridge.ai.decision.AssistantDecisionOptions
import com.fersaiyan.cyanbridge.ai.decision.DecisionMath
import com.fersaiyan.cyanbridge.ai.decision.DecisionOutputParser
import com.fersaiyan.cyanbridge.ai.decision.DecisionPromptBuilder
import com.fersaiyan.cyanbridge.ai.decision.LiveControlOptions
import com.fersaiyan.cyanbridge.ai.decision.SingleTokenDecisionEngine
import com.fersaiyan.cyanbridge.ai.live.GeminiLiveControlRouter
import com.fersaiyan.cyanbridge.ai.live.LiveControlAction
import com.fersaiyan.cyanbridge.ai.router.AssistantRequestRouter
import com.fersaiyan.cyanbridge.ai.router.AssistantRequest
import com.fersaiyan.cyanbridge.ai.router.AssistantRequestSource
import com.fersaiyan.cyanbridge.ai.router.AssistantIntent
import com.fersaiyan.cyanbridge.localagent.LocalAgentNodeBounds
import com.fersaiyan.cyanbridge.localagent.LocalAgentObservation
import com.fersaiyan.cyanbridge.localagent.LocalAgentScreenNode
import com.fersaiyan.cyanbridge.localagent.LocalAgentScreenSnapshot
import com.fersaiyan.cyanbridge.localagent.UiActionCandidateBuilder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Emulator-layer HIL for the Jev-like local agent.
 *
 * Runs on the local-PC emulator via tools/hil/run_instrumentation.sh with NO
 * model, NO Tasker, NO network: every decision uses an injected fake
 * single-letter generator. This validates the full wiring (router -> decision
 * engine -> live control -> UI candidates) on-device in CI with verbose logs.
 */
@RunWith(AndroidJUnit4::class)
class JevLikeDecisionEmulatorTest {

    @Test fun decisionMath_softmax_sumsToOne_onDevice() {
        val probs = DecisionMath.softmax(floatArrayOf(2.4f, -0.7f, 8.9f, 0.4f))
        println("JEV_HIL softmax=${probs.joinToString(",")} sum=${probs.sum()}")
        assertEquals(1f, probs.sum(), 1e-5f)
        assertTrue("LOCAL_AGENT logit must dominate", probs[2] > 0.99f)
    }

    @Test fun parser_toleratesThinkPreamble_onDevice() {
        val candidates = AssistantDecisionOptions.candidates()
        val parsed = DecisionOutputParser.parse(
            "<think>A é responder, C é operar o celular. Escolho C.</think> Answer: C",
            candidates,
        )
        println("JEV_HIL parsed=${parsed.label} cleaned='${parsed.cleanedForLog}'")
        assertEquals("C", parsed.label)
    }

    @Test fun router_portugueseCommand_routesToUiTask_onDevice() = runBlocking {
        val router = AssistantRequestRouter(
            decisionEngineProvider = {
                SingleTokenDecisionEngine(generate = { _, userPrompt ->
                    println("JEV_HIL router prompt chars=${userPrompt.length}")
                    "C"
                })
            },
        )
        val decision = router.tryDecisionEngine(
            AssistantRequest("Abra o Spotify e toque minha playlist de academia.", AssistantRequestSource.GLASSES_VOICE)
        )
        println("JEV_HIL pt route=$decision")
        assertEquals(AssistantIntent.EXECUTE_UI_TASK, decision?.intent)
    }

    @Test fun liveControl_endPhrases_multilingual_onDevice() = runBlocking {
        val cases = mapOf(
            "Okay thanks, that's everything." to LiveControlAction.END_LIVE,
            "Obrigado, pode encerrar." to LiveControlAction.END_LIVE,
            "Merci, tu peux arrêter maintenant." to LiveControlAction.END_LIVE,
            "结束对话。" to LiveControlAction.END_LIVE,
            "Tell me more about black holes." to LiveControlAction.CONTINUE,
        )
        cases.forEach { (text, expected) ->
            val router = GeminiLiveControlRouter()
            router.appendTranscript(text)
            val got = router.classifyHeuristicNow()
            println("JEV_HIL live text='$text' -> $got (expected $expected)")
            assertEquals("text=$text", expected, got)
        }
        // Model path at utterance boundary (fake returns B = END).
        val router = GeminiLiveControlRouter(
            engineProvider = { SingleTokenDecisionEngine(generate = { _, _ -> "B" }) },
        )
        router.appendTranscript("I am done here, please wrap up this call for me.")
        val action = router.classifyAtUtteranceEnd()
        println("JEV_HIL live model-path action=$action")
        assertEquals(LiveControlAction.END_LIVE, action)
    }

    @Test fun uiCandidates_boundedAndLabeled_onDevice() {
        val obs = LocalAgentObservation(
            createdAtMs = 1L,
            packageName = "com.google.android.youtube",
            screenText = "YouTube",
            screenSnapshot = LocalAgentScreenSnapshot(
                packageName = "com.google.android.youtube",
                textSummary = "YouTube home",
                nodes = listOf(
                    LocalAgentScreenNode(12, 0, "Search", "", className = "Button", viewId = "", isClickable = true, isEditable = false, isScrollable = false, bounds = LocalAgentNodeBounds(0, 0, 100, 50)),
                    LocalAgentScreenNode(18, 0, "", "", hintText = "Search YouTube", className = "EditText", viewId = "", isClickable = true, isEditable = true, isScrollable = false, bounds = LocalAgentNodeBounds(0, 60, 500, 120)),
                ),
            ),
        )
        val built = UiActionCandidateBuilder.build("Play the latest Linus Tech Tips video", obs)
        println("JEV_HIL ui candidates=${built.candidates.map { "${it.label}=${it.description}" }} spans=${built.typeSpans}")
        assertTrue(built.candidates.size in 2..8)
        assertTrue(built.candidates.any { it.description.contains("Finish", ignoreCase = true) })
        val prompt = DecisionPromptBuilder.buildUiActionPrompt("Play LTT video", "YouTube home", built.candidates)
        println("JEV_HIL ui prompt chars=${prompt.length}")
        assertTrue(prompt.contains("ONLY the single letter"))
        assertEquals(LiveControlOptions.candidates().size, 3)
    }
}
