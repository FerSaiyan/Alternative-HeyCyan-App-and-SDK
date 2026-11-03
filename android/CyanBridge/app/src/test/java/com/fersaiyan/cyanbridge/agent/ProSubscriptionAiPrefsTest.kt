package com.fersaiyan.cyanbridge.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProSubscriptionAiPrefsTest {
    @Test
    fun freeAlwaysUsesGeminiLiveRegardlessOfPersistedProModel() {
        assertTrue(
            ProSubscriptionAiPrefs.shouldUseGeminiLiveForQuestions(
                isProActive = false,
                questionsModel = "deepseek/deepseek-v4-flash-vision-exp",
            ),
        )
    }

    @Test
    fun activeProCanChooseAnotherVisionModel() {
        assertTrue(
            ProSubscriptionAiPrefs.shouldUseGeminiLiveForQuestions(
                isProActive = true,
                questionsModel = "google/gemini-3.1-flash-live-preview",
            ),
        )
        // The server catalog's Live id (picked from the Questions list) also routes to Live.
        assertTrue(
            ProSubscriptionAiPrefs.shouldUseGeminiLiveForQuestions(
                isProActive = true,
                questionsModel = "google/gemini-3.8-live",
            ),
        )
        assertFalse(
            ProSubscriptionAiPrefs.shouldUseGeminiLiveForQuestions(
                isProActive = true,
                questionsModel = "deepseek/deepseek-v4-flash-vision-exp",
            ),
        )
        assertFalse(
            ProSubscriptionAiPrefs.shouldUseGeminiLiveForQuestions(
                isProActive = true,
                questionsModel = "google/gemini-3.1-flash-lite",
            ),
        )
    }
}
