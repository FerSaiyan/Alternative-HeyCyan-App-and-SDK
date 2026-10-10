package com.fersaiyan.cyanbridge.localmodels.templates

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
                    append(if (systemPrompt.isBlank()) "You are a helpful assistant." else systemPrompt.trim())
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
                    if (systemPrompt.isNotBlank()) {
                        append("<start_of_turn>system\n")
                        append(systemPrompt.trim())
                        append("<end_of_turn>\n")
                    }
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
            id = "gemma4_it",
            label = "Gemma 4 instruct",
            description = "Gemma 4 turn tags (<|turn>role ... <turn|>), as in Google's chat template.",
            render = { systemPrompt, messages ->
                buildString {
                    if (systemPrompt.isNotBlank()) {
                        append("<|turn>system\n")
                        append(systemPrompt.trim())
                        append("<turn|>\n")
                    }
                    messages.forEach { msg ->
                        val wireRole = when (msg.role.lowercase()) {
                            "assistant", "model" -> "model"
                            "system" -> "system"
                            else -> "user"
                        }
                        append("<|turn>")
                        append(wireRole)
                        append("\n")
                        append(msg.content.trim())
                        append("<turn|>\n")
                    }
                    append("<|turn>model\n")
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
                    append(if (systemPrompt.isBlank()) "You are a helpful assistant." else systemPrompt.trim())
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
                    if (systemPrompt.isNotBlank()) {
                        append("System: ")
                        append(systemPrompt.trim())
                        append("\n\n")
                    }
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

    /** Template for a model without a catalog entry or saved override, guessed from its file name. */
    fun inferForFileName(fileName: String): String? {
        val name = fileName.lowercase()
        return when {
            "gemma-4" in name || "gemma4" in name -> "gemma4_it"
            "gemma" in name -> "gemma_it"
            "qwen" in name -> "qwen_chat"
            else -> null
        }
    }

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
}
