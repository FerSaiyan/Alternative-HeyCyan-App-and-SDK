package com.fersaiyan.cyanbridge.ai.router

import com.fersaiyan.cyanbridge.ai.decision.DecisionCandidate
import com.fersaiyan.cyanbridge.ai.decision.LocalDecision
import com.fersaiyan.cyanbridge.ai.decision.LocalDecisionEngine
import com.fersaiyan.cyanbridge.ai.decision.SingleTokenDecisionEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AssistantRequestRouterJevTest {
    private fun engineFor(letter: String): LocalDecisionEngine =
        SingleTokenDecisionEngine(generate = { _, _ -> letter })

    @Test fun `portuguese operate request maps to UI task via decision engine`() = runBlocking {
        val router = AssistantRequestRouter(decisionEngineProvider = { engineFor("C") })
        val decision = router.tryDecisionEngine(
            AssistantRequest("Abra o Spotify e toque minha playlist de academia.", AssistantRequestSource.GLASSES_VOICE)
        )
        println("JEV_CI pt route=$decision")
        assertEquals(AssistantIntent.EXECUTE_UI_TASK, decision?.intent)
    }

    @Test fun `low confidence execute degrades to clarify`() {
        val router = AssistantRequestRouter()
        val low = LocalDecision(2, "C", 0.5f, floatArrayOf(0.2f, 0.1f, 0.5f, 0.2f), "C")
        val mapped = router.mapDecisionToRouting(low, "do something vague")
        println("JEV_CI lowconf mapped=$mapped")
        assertEquals(AssistantIntent.CLARIFY, mapped.intent)
    }

    @Test fun `high confidence execute keeps goal`() {
        val router = AssistantRequestRouter()
        val high = LocalDecision(2, "C", 0.97f, floatArrayOf(0.01f, 0.01f, 0.97f, 0.01f), "C")
        val mapped = router.mapDecisionToRouting(high, "Open Spotify")
        println("JEV_CI highconf mapped=$mapped")
        assertEquals(AssistantIntent.EXECUTE_UI_TASK, mapped.intent)
        assertEquals("Open Spotify", mapped.normalizedGoal)
    }

    @Test fun `no engine returns null and legacy path still works`() = runBlocking {
        val router = AssistantRequestRouter(decisionEngineProvider = null)
        assertNull(router.tryDecisionEngine(AssistantRequest("hello", AssistantRequestSource.CHAT)))
        // Legacy heuristic still catches the imperative English form.
        val legacy = router.classifyHeuristically(
            AssistantRequest("Open Spotify and play my liked songs", AssistantRequestSource.GLASSES_VOICE)
        )
        println("JEV_CI legacy=$legacy")
        assertEquals(AssistantIntent.EXECUTE_UI_TASK, legacy?.intent)
    }

    @Test fun `deterministic blank and image checks stay primary`() {
        val router = AssistantRequestRouter(decisionEngineProvider = { engineFor("C") })
        val blank = router.classifyHeuristicallyDeterministic(AssistantRequest("   ", AssistantRequestSource.CHAT))
        println("JEV_CI blank=$blank")
        assertEquals(AssistantIntent.CLARIFY, blank?.intent)
        val img = router.classifyHeuristicallyDeterministic(
            AssistantRequest("hello", AssistantRequestSource.GLASSES_VOICE, imageAttached = true)
        )
        assertEquals(AssistantIntent.ANALYZE_IMAGE, img?.intent)
    }
}
