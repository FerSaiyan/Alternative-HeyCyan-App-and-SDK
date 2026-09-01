package com.fersaiyan.cyanbridge.ai.live

import com.fersaiyan.cyanbridge.ai.vision.ImageQuestionDefaults
import com.fersaiyan.cyanbridge.ai.vision.ImageQuestionPromptResolver
import com.fersaiyan.cyanbridge.ai.vision.ImageQuestionRoute
import com.fersaiyan.cyanbridge.ai.vision.ImageQuestionSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiLiveLanguageTest {
    @Test
    fun `Live initialization cue follows app language`() {
        assertEquals("Initializing Live.", ImageQuestionDefaults.initializingLiveCueForLanguage("en-US"))
        assertEquals("Inicializando o Live.", ImageQuestionDefaults.initializingLiveCueForLanguage("pt-BR"))
        assertEquals("라이브를 초기화하고 있습니다.", ImageQuestionDefaults.initializingLiveCueForLanguage("ko"))
    }

    @Test
    fun `Korean default image question remains available to Gemini Live`() {
        val prompt = ImageQuestionPromptResolver.resolve(
            ImageQuestionSettings(
                appLanguageTag = "ko",
                defaultQuestion = ImageQuestionDefaults.questionForLanguage("ko"),
                usesBuiltInDefault = true,
            ),
            userQuestion = null,
        ).forRoute(ImageQuestionRoute.PRO_RELAY)

        assertTrue(prompt.contains("(ko)"))
        assertTrue(prompt.contains("이미지를 간단히 설명해 주세요"))
    }
}
