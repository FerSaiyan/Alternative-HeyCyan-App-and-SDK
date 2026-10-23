package com.fersaiyan.cyanbridge.ai.vision

import com.fersaiyan.cyanbridge.ai.image.ImageQuestionBroadcast
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageQuestionPromptResolverTest {

    @Test
    fun builtInDefaultIsPersianRegardlessOfAppLanguage() {
        assertEquals(
            "این تصویر را دقیق و جامع به زبان فارسی توصیف کن.",
            ImageQuestionDefaults.questionForLanguage("en"),
        )
        assertEquals(
            "این تصویر را دقیق و جامع به زبان فارسی توصیف کن.",
            ImageQuestionDefaults.questionForLanguage("pt-BR"),
        )
    }

    @Test
    fun noUserQuestionUsesPersianDefaultAndResponseInstruction() {
        val settings = builtInSettings(languageTag = "pt-BR")

        val prompt = ImageQuestionPromptResolver.resolve(settings, userQuestion = null).text

        assertTrue(prompt.startsWith("این تصویر را دقیق و جامع به زبان فارسی توصیف کن."))
        assertTrue(prompt.contains("همیشه و منحصراً به زبان فارسی (Farsi) پاسخ بده و توصیف کن."))
    }

    @Test
    fun responseSpeechUsesPersianLocale() {
        assertEquals("fa-IR", ImageQuestionDefaults.RESPONSE_LANGUAGE_TAG)
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
    fun legacyEnglishBuiltInDefaultUsesPersianDescription() {
        val prompt = ImageQuestionPromptResolver.resolve(
            settings = ImageQuestionSettings(
                appLanguageTag = "en",
                defaultQuestion = "Give me a concise description of the image",
                usesBuiltInDefault = false,
            ),
            userQuestion = null,
        ).text

        assertTrue(prompt.startsWith("این تصویر را دقیق و جامع به زبان فارسی توصیف کن."))
    }

    @Test
    fun changingAppLanguageDoesNotChangePersianDefaultOrOutputInstruction() {
        val english = ImageQuestionPromptResolver.resolve(builtInSettings("en"), null).text
        val spanish = ImageQuestionPromptResolver.resolve(builtInSettings("es"), null).text

        assertEquals(english, spanish)
        assertTrue(english.startsWith("این تصویر را دقیق و جامع به زبان فارسی توصیف کن."))
        assertTrue(english.contains("همیشه و منحصراً به زبان فارسی (Farsi) پاسخ بده و توصیف کن."))
    }

    @Test
    fun questionCueUsesTheSelectedAppLanguage() {
        assertEquals("Ask.", ImageQuestionDefaults.questionCueForLanguage("en"))
        assertEquals("Pergunte.", ImageQuestionDefaults.questionCueForLanguage("pt-BR"))
        assertEquals("질문하세요.", ImageQuestionDefaults.questionCueForLanguage("ko"))
    }

    @Test
    fun voiceListeningCueUsesTheSelectedAppLanguage() {
        assertEquals("I am listening.", ImageQuestionDefaults.listeningCueForLanguage("en"))
        assertEquals("Estou ouvindo.", ImageQuestionDefaults.listeningCueForLanguage("pt-BR"))
        assertEquals("Je vous écoute.", ImageQuestionDefaults.listeningCueForLanguage("fr"))
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
