package com.fersaiyan.cyanbridge.integrations.knowledge

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

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

/** Selection-aware transformations used by the lightweight Markdown toolbar. */
object MarkdownEditorActions {
    fun wrap(
        value: TextFieldValue,
        prefix: String,
        suffix: String = prefix,
        placeholder: String,
    ): TextFieldValue {
        val start = minOf(value.selection.start, value.selection.end).coerceIn(0, value.text.length)
        val end = maxOf(value.selection.start, value.selection.end).coerceIn(start, value.text.length)
        val selected = value.text.substring(start, end)
        val replacementText = selected.ifBlank { placeholder }
        val replacement = prefix + replacementText + suffix
        val newText = value.text.replaceRange(start, end, replacement)
        val selection = if (selected.isBlank()) {
            TextRange(start + prefix.length, start + prefix.length + placeholder.length)
        } else {
            TextRange(start + replacement.length)
        }
        return TextFieldValue(newText, selection)
    }

    fun insert(value: TextFieldValue, insertion: String): TextFieldValue {
        val start = minOf(value.selection.start, value.selection.end).coerceIn(0, value.text.length)
        val end = maxOf(value.selection.start, value.selection.end).coerceIn(start, value.text.length)
        val newText = value.text.replaceRange(start, end, insertion)
        return TextFieldValue(newText, TextRange(start + insertion.length))
    }

    fun prefixCurrentLine(value: TextFieldValue, prefix: String): TextFieldValue {
        val cursor = minOf(value.selection.start, value.selection.end).coerceIn(0, value.text.length)
        val lineStart = value.text.lastIndexOf('\n', (cursor - 1).coerceAtLeast(0))
            .let { if (it < 0) 0 else it + 1 }
        val newText = value.text.replaceRange(lineStart, lineStart, prefix)
        val newStart = (value.selection.start + prefix.length).coerceAtMost(newText.length)
        val newEnd = (value.selection.end + prefix.length).coerceAtMost(newText.length)
        return TextFieldValue(newText, TextRange(newStart, newEnd))
    }
}
