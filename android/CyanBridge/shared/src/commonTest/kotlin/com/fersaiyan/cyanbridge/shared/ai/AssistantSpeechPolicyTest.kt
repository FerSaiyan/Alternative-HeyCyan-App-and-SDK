package com.fersaiyan.cyanbridge.shared.ai

import kotlin.test.assertEquals
import kotlin.test.Test

class AssistantSpeechPolicyTest {
    @Test
    fun keepsConciseClarification() {
        assertEquals(
            "Which app should I open?",
            AssistantSpeechPolicy.clarification("Which app should I open?"),
        )
    }

    @Test
    fun replacesToolDetailsWithSafeClarification() {
        assertEquals(
            "Please say exactly what you want me to do.",
            AssistantSpeechPolicy.clarification("Use click_coord at center:(50,90)"),
        )
    }

    @Test
    fun capsExcessiveClassifierSpeech() {
        assertEquals(
            "Please say exactly what you want me to do.",
            AssistantSpeechPolicy.clarification("x".repeat(200)),
        )
    }

    @Test
    fun jsonLikeTextIsReplaced() {
        assertEquals(
            "Please say exactly what you want me to do.",
            AssistantSpeechPolicy.clarification("{\n\"x\": 1\n}"),
        )
    }
}
