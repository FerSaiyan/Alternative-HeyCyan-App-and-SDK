package com.fersaiyan.cyanbridge.localmodels.templates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PromptTemplateRegistryTest {
    @Test
    fun gemma4UsesGooglesTurnTags() {
        val prompt = PromptTemplateRegistry.renderPrompt(
            "gemma4_it",
            "Be brief.",
            listOf(PromptMessage("user", "<cyanbridge_media>\nWhat is this?"), PromptMessage("assistant", "A bus."), PromptMessage("user", "Color?")),
        )
        assertEquals(
            "<|turn>system\nBe brief.<turn|>\n" +
                "<|turn>user\n<cyanbridge_media>\nWhat is this?<turn|>\n" +
                "<|turn>model\nA bus.<turn|>\n" +
                "<|turn>user\nColor?<turn|>\n" +
                "<|turn>model\n",
            prompt,
        )
    }

    @Test
    fun importedModelTemplatesAreInferredFromTheFileName() {
        assertEquals("gemma4_it", PromptTemplateRegistry.inferForFileName("gemma-4-E2B-it-Q4_0.gguf"))
        assertEquals("gemma_it", PromptTemplateRegistry.inferForFileName("gemma-3-1b-it-Q4_K_M.gguf"))
        assertEquals("qwen_chat", PromptTemplateRegistry.inferForFileName("Qwen3.5-0.8B-Q4_0.gguf"))
        assertNull(PromptTemplateRegistry.inferForFileName("mistral-7b.gguf"))
    }
}
