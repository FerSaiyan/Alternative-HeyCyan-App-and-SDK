package com.fersaiyan.cyanbridge.localmodels.templates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test
    fun qwen_template_renders_turns_and_assistant_suffix() {
        val prompt = PromptTemplateRegistry.renderPrompt(
            templateId = "qwen_chat",
            systemPrompt = "You are concise.",
            messages = listOf(
                PromptMessage("User", "Hello"),
                PromptMessage("Assistant", "Hi"),
            ),
        )

        assertTrue(prompt.contains("<|im_start|>system"))
        assertTrue(prompt.contains("Hello"))
        assertTrue(prompt.endsWith("<|im_start|>assistant\n"))
    }

    @Test
    fun raw_template_falls_back_to_plain_transcript() {
        val prompt = PromptTemplateRegistry.renderPrompt(
            templateId = "raw_completion",
            systemPrompt = "",
            messages = listOf(PromptMessage("User", "Write one line")),
        )

        assertTrue(prompt.contains("User: Write one line"))
        assertTrue(prompt.endsWith("Assistant: "))
    }
}
