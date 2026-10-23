package com.fersaiyan.cyanbridge.localmodels.templates

import com.fersaiyan.cyanbridge.localmodels.settings.LocalGenerationSettings

data class PromptMessage(
    val role: String,
    val content: String,
)

data class PromptTemplate(
    val id: String,
    val label: String,
    val description: String,
    val render: (systemPrompt: String, messages: List<PromptMessage>) -> String,
)

object PromptTemplateRegistry {
    val templates: List<PromptTemplate> = listOf(
        PromptTemplate(
            id = "qwen_chat",
            label = "Qwen chat",
            description = "Qwen/ChatML-style turn formatting.",
            render = { systemPrompt, messages ->
                buildString {
                    append("<|im_start|>system\n")
                    append(systemPrompt.effectiveSystemPrompt())
                    append("\n<|im_end|>\n")
                    messages.forEach { msg ->
                        val role = msg.role.lowercase()
                        val wireRole = when (role) {
                            "assistant", "model" -> "assistant"
                            "system" -> "system"
                            else -> "user"
                        }
                        append("<|im_start|>")
                        append(wireRole)
                        append("\n")
                        append(msg.content.trim())
                        append("\n<|im_end|>\n")
                    }
                    append("<|im_start|>assistant\n")
                }
            },
        ),
        PromptTemplate(
            id = "gemma_it",
            label = "Gemma instruct",
            description = "Gemma instruction turn tags.",
            render = { systemPrompt, messages ->
                buildString {
                    append("<start_of_turn>system\n")
                    append(systemPrompt.effectiveSystemPrompt())
                    append("<end_of_turn>\n")
                    messages.forEach { msg ->
                        val role = msg.role.lowercase()
                        val wireRole = when (role) {
                            "assistant", "model" -> "model"
                            "system" -> "system"
                            else -> "user"
                        }
                        append("<start_of_turn>")
                        append(wireRole)
                        append("\n")
                        append(msg.content.trim())
                        append("<end_of_turn>\n")
                    }
                    append("<start_of_turn>model\n")
                }
            },
        ),
        PromptTemplate(
            id = "generic_chatml",
            label = "Generic ChatML",
            description = "Generic ChatML format for unknown chat-tuned models.",
            render = { systemPrompt, messages ->
                buildString {
                    append("<|im_start|>system\n")
                    append(systemPrompt.effectiveSystemPrompt())
                    append("\n<|im_end|>\n")
                    messages.forEach { msg ->
                        val role = when (msg.role.lowercase()) {
                            "assistant", "model" -> "assistant"
                            "system" -> "system"
                            else -> "user"
                        }
                        append("<|im_start|>")
                        append(role)
                        append("\n")
                        append(msg.content.trim())
                        append("\n<|im_end|>\n")
                    }
                    append("<|im_start|>assistant\n")
                }
            },
        ),
        PromptTemplate(
            id = "raw_completion",
            label = "Raw completion",
            description = "Plain transcript completion, no chat tags.",
            render = { systemPrompt, messages ->
                buildString {
                    append("System: ")
                    append(systemPrompt.effectiveSystemPrompt())
                    append("\n\n")
                    messages.forEach { msg ->
                        val role = when (msg.role.lowercase()) {
                            "assistant", "model" -> "Assistant"
                            "system" -> "System"
                            else -> "User"
                        }
                        append(role)
                        append(": ")
                        append(msg.content.trim())
                        append("\n")
                    }
                    append("Assistant: ")
                }
            },
        ),
    )

    fun findById(id: String?): PromptTemplate? {
        if (id.isNullOrBlank()) return null
        return templates.firstOrNull { it.id == id }
    }

    fun renderPrompt(
        templateId: String,
        systemPrompt: String,
        messages: List<PromptMessage>,
    ): String {
        val template = findById(templateId) ?: findById("generic_chatml")!!
        return template.render(systemPrompt, messages)
    }

    private fun String.effectiveSystemPrompt(): String =
        trim().ifBlank { LocalGenerationSettings.DEFAULT_SYSTEM_PROMPT }
}
