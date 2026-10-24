package com.fersaiyan.cyanbridge.integrations.knowledge

/** Editable representation of a Markdown note managed inside the vault's CyanBridge/ folder. */
data class ObsidianManagedDraft(
    val title: String,
    val tags: String,
    val body: String,
    val createdAt: String? = null,
)

object ObsidianMarkdownCodec {
    fun parse(fileName: String, markdown: String): ObsidianManagedDraft {
        val normalized = markdown.replace("\r\n", "\n")
        var content = normalized
        var createdAt: String? = null
        var tags = ""

        if (normalized.startsWith("---\n")) {
            val end = normalized.indexOf("\n---\n", startIndex = 4)
            if (end >= 0) {
                val frontMatter = normalized.substring(4, end)
                frontMatter.lineSequence().forEach { line ->
                    val key = line.substringBefore(':', "").trim().lowercase()
                    val value = line.substringAfter(':', "").trim()
                    when (key) {
                        "created" -> createdAt = value.trim('"').takeIf { it.isNotBlank() }
                        "tags" -> tags = parseTags(value)
                    }
                }
                content = normalized.substring(end + "\n---\n".length)
            }
        }

        val lines = content.lines().toMutableList()
        while (lines.firstOrNull()?.isBlank() == true) lines.removeAt(0)
        val fallbackTitle = fileName.removeSuffix(".md")
        val title = lines.firstOrNull()?.takeIf { it.startsWith("# ") }?.removePrefix("# ")?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: fallbackTitle
        if (lines.firstOrNull()?.startsWith("# ") == true) lines.removeAt(0)
        while (lines.firstOrNull()?.isBlank() == true) lines.removeAt(0)

        return ObsidianManagedDraft(
            title = title,
            tags = tags,
            body = lines.joinToString("\n").trimEnd(),
            createdAt = createdAt,
        )
    }

    fun render(draft: ObsidianManagedDraft, now: String): String {
        val tags = normalizeTags(draft.tags)
        return buildString {
            appendLine("---")
            appendLine("source: cyanbridge")
            appendLine("created: \"${draft.createdAt ?: now}\"")
            appendLine("updated: \"$now\"")
            if (tags.isNotEmpty()) appendLine("tags: [${tags.joinToString(", ")}]")
            appendLine("---")
            appendLine()
            appendLine("# ${draft.title.trim()}")
            appendLine()
            append(draft.body.trimEnd())
            appendLine()
        }
    }

    fun normalizeTags(raw: String): List<String> = raw
        .split(',', ' ', '\n', '\t')
        .asSequence()
        .map { it.trim().removePrefix("#") }
        .filter { it.isNotBlank() }
        .map { tag -> tag.replace(Regex("[^A-Za-z0-9_/-]"), "-").trim('-') }
        .filter { it.isNotBlank() }
        .distinct()
        .take(30)
        .toList()

    private fun parseTags(value: String): String {
        val stripped = value.trim().removePrefix("[").removeSuffix("]")
        return normalizeTags(stripped).joinToString(", ")
    }
}
