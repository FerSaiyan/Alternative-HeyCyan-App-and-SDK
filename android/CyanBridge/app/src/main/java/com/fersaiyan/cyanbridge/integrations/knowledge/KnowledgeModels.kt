package com.fersaiyan.cyanbridge.integrations.knowledge

enum class KnowledgeSource(val wire: String, val label: String) {
    CHATGPT("chatgpt", "ChatGPT"),
    CLAUDE("claude", "Claude"),
    OBSIDIAN("obsidian", "Obsidian"),
    IMPORT_INBOX("import_inbox", "Import inbox"),
    SHARED_TEXT("shared_text", "Shared text");

    companion object {
        fun fromWire(value: String?): KnowledgeSource =
            entries.firstOrNull { it.wire == value } ?: IMPORT_INBOX
    }
}

data class KnowledgeDocument(
    val source: KnowledgeSource,
    val sourceId: String,
    val title: String,
    val text: String,
    val updatedAtMs: Long,
    /** User-authored turns only. Never promote assistant output to a user fact. */
    val userAuthoredText: String = "",
)

data class KnowledgeImportResult(
    val source: KnowledgeSource,
    val documentsImported: Int,
    val chunksIndexed: Int,
    val warnings: List<String> = emptyList(),
) {
    fun summary(): String = buildString {
        append(source.label)
        append(": ")
        append(documentsImported)
        append(if (documentsImported == 1) " document" else " documents")
        append(", ")
        append(chunksIndexed)
        append(if (chunksIndexed == 1) " searchable chunk" else " searchable chunks")
        if (warnings.isNotEmpty()) append(" · ${warnings.size} warning(s)")
    }
}
