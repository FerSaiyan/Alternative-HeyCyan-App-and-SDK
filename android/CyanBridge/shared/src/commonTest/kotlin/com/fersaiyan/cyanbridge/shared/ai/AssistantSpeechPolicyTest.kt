package com.fersaiyan.cyanbridge.shared.ai

import kotlin.test.Test
import kotlin.test.assertEquals

class AssistantSpeechPolicyTest {
    @Test
    fun keepsConciseClarificationsAndReplacesToolDetailsLongTextAndJson() {
        val safe = "Please say exactly what you want me to do."
        listOf(
            "Which app should I open?" to "Which app should I open?",
            "Use click_coord at center:(50,90)" to safe,
            "x".repeat(200) to safe,
            "{\n\"x\": 1\n}" to safe,
        ).forEach { (spoken, expected) ->
            assertEquals(expected, AssistantSpeechPolicy.clarification(spoken), spoken)
        }
    }
}
