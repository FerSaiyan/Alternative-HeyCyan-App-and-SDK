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
}
