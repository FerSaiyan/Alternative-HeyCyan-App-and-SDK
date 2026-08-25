package com.fersaiyan.cyanbridge.localmodels.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalGenerationSettingsTest {
    @Test
    fun everyProfileStartsWithEditableSpeechFirstPrompt() {
        LocalModelPerformanceProfile.entries.forEach { profile ->
            val settings = LocalGenerationSettings.defaultsFor(entry = null, profile = profile)
            assertEquals(LocalGenerationSettings.DEFAULT_SYSTEM_PROMPT, settings.systemPromptOverride)
        }
    }

    @Test
    fun defaultPromptRequestsShortUsefulOpeningWithoutHardCodingAnAnswer() {
        val prompt = LocalGenerationSettings.DEFAULT_SYSTEM_PROMPT
        assertTrue(prompt.contains("at most 8 words"))
        assertTrue(prompt.contains("most useful answer first"))
        assertTrue(prompt.contains("Avoid filler"))
    }
}
