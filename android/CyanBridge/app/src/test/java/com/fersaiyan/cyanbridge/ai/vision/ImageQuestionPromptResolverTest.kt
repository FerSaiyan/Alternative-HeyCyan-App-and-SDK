package com.fersaiyan.cyanbridge.ai.vision

import com.fersaiyan.cyanbridge.ai.image.ImageQuestionBroadcast
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageQuestionPromptResolverTest {

    @Test
    fun noUserQuestionUsesLocalizedBuiltInDefault() {
        val settings = builtInSettings(languageTag = "pt-BR")

        val prompt = ImageQuestionPromptResolver.resolve(settings, userQuestion = null).text

        assertTrue(prompt.startsWith(ImageQuestionDefaults.questionForLanguage("pt-BR")))
        assertTrue(prompt.contains("Answer only in Portuguese (pt-BR)."))
    }

    @Test
    fun userQuestionOmitsDefaultQuestion() {
        val defaultQuestion = ImageQuestionDefaults.questionForLanguage("en")

        val prompt = ImageQuestionPromptResolver.resolve(
            settings = ImageQuestionSettings(
                appLanguageTag = "en",
                defaultQuestion = defaultQuestion,
                usesBuiltInDefault = true,
            ),
            userQuestion = "What does the sign say?",
        ).text

        assertTrue(prompt.startsWith("What does the sign say?"))
        assertFalse(prompt.contains(defaultQuestion))
    }

    @Test
    fun customDefaultQuestionIsPreserved() {
        val customQuestion = "Read every menu price and allergy warning."

        val prompt = ImageQuestionPromptResolver.resolve(
            settings = ImageQuestionSettings(
                appLanguageTag = "en",
                defaultQuestion = customQuestion,
                usesBuiltInDefault = false,
            ),
            userQuestion = null,
        ).text

        assertTrue(prompt.startsWith(customQuestion))
    }

    @Test
    fun changingAppLanguageChangesBuiltInDefaultAndOutputInstruction() {
        val english = ImageQuestionPromptResolver.resolve(builtInSettings("en"), null).text
        val spanish = ImageQuestionPromptResolver.resolve(builtInSettings("es"), null).text

        assertTrue(english.startsWith(ImageQuestionDefaults.questionForLanguage("en")))
        assertTrue(spanish.startsWith(ImageQuestionDefaults.questionForLanguage("es")))
        assertTrue(english.contains("Answer only in English (en)."))
        assertTrue(spanish.contains("Answer only in Spanish (es)."))
    }

    @Test
    fun everyImageRouteReceivesTheSameResolvedPrompt() {
        val resolved = ImageQuestionPromptResolver.resolve(
            settings = builtInSettings("de"),
            userQuestion = "What is blocking the doorway?",
        )

        val relayPrompt = resolved.forRoute(ImageQuestionRoute.PRO_RELAY)
        val localPrompt = resolved.forRoute(ImageQuestionRoute.LOCAL_GEMMA)
        val taskerPrompt = ImageQuestionBroadcast.Payload(
            type = ImageQuestionBroadcast.TYPE_IMAGE,
            question = resolved.forRoute(ImageQuestionRoute.TASKER_GEMINI),
        ).extras()[ImageQuestionBroadcast.EXTRA_QUESTION]

        assertEquals(resolved.text, relayPrompt)
        assertEquals(relayPrompt, localPrompt)
        assertEquals(relayPrompt, taskerPrompt)
    }

    private fun builtInSettings(languageTag: String) = ImageQuestionSettings(
        appLanguageTag = languageTag,
        defaultQuestion = ImageQuestionDefaults.questionForLanguage(languageTag),
        usesBuiltInDefault = true,
    )
}
