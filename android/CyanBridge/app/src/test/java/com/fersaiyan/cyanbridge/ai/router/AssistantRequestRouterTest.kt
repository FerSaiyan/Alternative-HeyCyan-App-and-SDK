package com.fersaiyan.cyanbridge.ai.router

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AssistantRequestRouterTest {
    private val router = AssistantRequestRouter()

    @Test
    fun `imperative request routes to UI task`() {
        val decision = router.classifyHeuristically(
            AssistantRequest("Open Spotify and play my liked songs", AssistantRequestSource.GLASSES_VOICE)
        )

        assertEquals(AssistantIntent.EXECUTE_UI_TASK, decision?.intent)
        assertEquals("Open Spotify and play my liked songs", decision?.normalizedGoal)
    }

    @Test
    fun `courteous command routes to UI task`() {
        val decision = router.classifyHeuristically(
            AssistantRequest("Can you open Settings for me?", AssistantRequestSource.GLASSES_VOICE)
        )

        assertEquals(AssistantIntent.EXECUTE_UI_TASK, decision?.intent)
    }

    @Test
    fun `read current app request routes to UI task`() {
        val decision = router.classifyHeuristically(
            AssistantRequest("Read my WhatsApp messages", AssistantRequestSource.GLASSES_VOICE)
        )

        assertEquals(AssistantIntent.EXECUTE_UI_TASK, decision?.intent)
    }

    @Test
    fun `informational how question stays a normal question`() {
        val decision = router.classifyHeuristically(
            AssistantRequest("How do I open Bluetooth settings?", AssistantRequestSource.GLASSES_VOICE)
        )

        assertEquals(AssistantIntent.ANSWER_QUESTION, decision?.intent)
    }

    @Test
    fun `visual question requests image analysis`() {
        val decision = router.classifyHeuristically(
            AssistantRequest("What am I looking at?", AssistantRequestSource.GLASSES_VOICE)
        )

        assertEquals(AssistantIntent.ANALYZE_IMAGE, decision?.intent)
    }

    @Test
    fun `ambiguous request defers to model classifier`() {
        val decision = router.classifyHeuristically(
            AssistantRequest("I need some help with Spotify", AssistantRequestSource.GLASSES_VOICE)
        )

        assertNull(decision)
    }

    @Test
    fun `parses fenced classifier response`() {
        val decision = router.parseDecision(
            """
                ```json
                {"intent":"EXECUTE_UI_TASK","confidence":0.94,"goal":"Open Spotify","clarification":null}
                ```
            """.trimIndent()
        )

        assertEquals(AssistantIntent.EXECUTE_UI_TASK, decision.intent)
        assertEquals(0.94, decision.confidence, 0.001)
        assertEquals("Open Spotify", decision.normalizedGoal)
    }
}
