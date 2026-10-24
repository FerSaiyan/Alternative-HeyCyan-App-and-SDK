package com.fersaiyan.cyanbridge.shared.devices

import com.fersaiyan.cyanbridge.shared.settings.LocalizedPromptDefaults
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocalizedPromptDefaultsTest {
    @Test
    fun defaultsFollowTheLanguageTag() {
        assertEquals("Dê-me uma descrição concisa da imagem", LocalizedPromptDefaults.imageQuestion("pt-BR"))
        assertEquals("Give me a concise description of the image", LocalizedPromptDefaults.imageQuestion("en-US"))
        assertEquals("Give me a concise description of the image", LocalizedPromptDefaults.imageQuestion("nl"))
        assertTrue(LocalizedPromptDefaults.systemPrompt("pt").startsWith("Você é o assistente"))
        assertTrue(LocalizedPromptDefaults.systemPrompt("zh-Hans-CN").startsWith("你是"))
    }

    @Test
    fun onlyStockTextsCountAsDefaults() {
        assertTrue(LocalizedPromptDefaults.isDefaultImageQuestion(LocalizedPromptDefaults.imageQuestion("en")))
        assertTrue(LocalizedPromptDefaults.isDefaultSystemPrompt(LocalizedPromptDefaults.systemPrompt("ru")))
        assertTrue(LocalizedPromptDefaults.isDefaultImageQuestion(""))
        assertFalse(LocalizedPromptDefaults.isDefaultImageQuestion("Leia todos os números da imagem"))
    }
}
