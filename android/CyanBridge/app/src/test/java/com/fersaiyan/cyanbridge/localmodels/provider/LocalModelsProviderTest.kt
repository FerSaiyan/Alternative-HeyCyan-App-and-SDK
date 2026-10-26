package com.fersaiyan.cyanbridge.localmodels.provider

import com.fersaiyan.cyanbridge.localmodels.templates.PromptMessage
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalModelsProviderTest {

    @Test
    fun multimodalPromptKeepsConfiguredAndRuntimeSystemInstructions() {
        val prompt = buildMultimodalPrompt(
            configuredSystemPrompt = "Always answer in Russian.",
            messages = listOf(
                PromptMessage("System", "Keep answers brief."),
                PromptMessage("User", "Describe this image."),
            ),
        )

        assertEquals("Always answer in Russian.\n\nKeep answers brief.", prompt.systemInstruction)
        assertEquals("Describe this image.", prompt.userRequest)
    }

    @Test
    fun spokenQuestionUserTurnIsTheAttachmentAlone() {
        val prompt = buildMultimodalPrompt(
            configuredSystemPrompt = "Be brief.",
            messages = listOf(
                PromptMessage("System", "You are the voice assistant in the user's smart glasses."),
                PromptMessage("User", ""),
            ),
        )

        assertEquals("Be brief.\n\nYou are the voice assistant in the user's smart glasses.", prompt.systemInstruction)
        assertEquals("", prompt.userRequest)
    }
}
