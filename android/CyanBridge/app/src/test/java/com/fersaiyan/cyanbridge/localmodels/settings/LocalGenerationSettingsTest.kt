package com.fersaiyan.cyanbridge.localmodels.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalGenerationSettingsTest {
    @Test
    fun everyProfileStartsWithThePersianOnlySystemPrompt() {
        LocalModelPerformanceProfile.entries.forEach { profile ->
            val settings = LocalGenerationSettings.defaultsFor(entry = null, profile = profile)
            assertEquals(LocalGenerationSettings.DEFAULT_SYSTEM_PROMPT, settings.systemPromptOverride)
        }
    }

    @Test
    fun defaultPromptRequiresPersianResponses() {
        assertEquals(
            "You are a helpful assistant. Always answer and describe exclusively in Persian (Farsi).",
            LocalGenerationSettings.DEFAULT_SYSTEM_PROMPT,
        )
    }

    @Test
    fun previousDefaultsMigrateWithoutReplacingCustomPrompts() {
        listOf(
            LocalGenerationSettings.LEGACY_EIGHT_WORD_SYSTEM_PROMPT,
            LocalGenerationSettings.PREVIOUS_DEFAULT_SYSTEM_PROMPT,
        ).forEach { previousPrompt ->
            assertEquals(
                LocalGenerationSettings.DEFAULT_SYSTEM_PROMPT,
                LocalGenerationSettings.migrateDefaultSystemPrompt(previousPrompt),
            )
        }
        assertEquals("Custom prompt", LocalGenerationSettings.migrateDefaultSystemPrompt("Custom prompt"))
    }
}
