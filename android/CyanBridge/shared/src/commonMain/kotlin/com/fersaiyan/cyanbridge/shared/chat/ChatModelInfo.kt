package com.fersaiyan.cyanbridge.shared.chat

enum class ChatProviderKind {
    /** A model on the phone. */
    LOCAL,

    /** The Local provider pointed at an OpenAI-compatible server. */
    REMOTE_OPENAI,
    RELAY,
    OTHER,
}

/** The provider chats go to and its selected model ([modelName] null: none installed / not shown). */
data class ChatModelInfo(val kind: ChatProviderKind, val modelName: String? = null) {
    val usesLocalModels: Boolean get() = kind == ChatProviderKind.LOCAL || kind == ChatProviderKind.REMOTE_OPENAI
    val localModelMissing: Boolean get() = kind == ChatProviderKind.LOCAL && modelName == null

    fun badge(status: String): String? = when (kind) {
        ChatProviderKind.REMOTE_OPENAI -> "Remote model: $modelName ($status)"
        ChatProviderKind.LOCAL -> modelName?.let { "Local model: $it ($status)" } ?: "Local model: none installed"
        ChatProviderKind.RELAY -> "Relay model: ${modelName.orEmpty().ifBlank { "auto" }}"
        ChatProviderKind.OTHER -> modelName
    }

    val changeModelLabel: String
        get() = when (kind) {
            ChatProviderKind.REMOTE_OPENAI -> "Configure remote model"
            ChatProviderKind.LOCAL -> "Change local model"
            ChatProviderKind.RELAY -> "Change relay AI model"
            ChatProviderKind.OTHER -> "Change AI model"
        }
}
