package com.fersaiyan.cyanbridge.integrations.knowledge

import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets

/** Tolerant parser for account exports. No provider login/session tokens are used. */
object ExternalAiExportParser {

    data class Parsed(
        val source: KnowledgeSource,
        val documents: List<KnowledgeDocument>,
        val warnings: List<String> = emptyList(),
    )

    fun parseJson(bytes: ByteArray, filenameHint: String? = null): Parsed {
        val text = bytes.toString(StandardCharsets.UTF_8).trim()
        require(text.isNotBlank()) { "The selected export is empty." }
        val root: Any = when (text.first()) {
            '[' -> JSONArray(text)
            '{' -> JSONObject(text)
            else -> error("Expected a JSON export.")
        }
        val source = detectSource(root, filenameHint)
        return when (source) {
            KnowledgeSource.CHATGPT -> parseChatGpt(root)
            KnowledgeSource.CLAUDE -> parseClaude(root)
            else -> Parsed(source, parseGenericJson(root, source))
        }
    }

    private fun detectSource(root: Any, filenameHint: String?): KnowledgeSource {
        val hint = filenameHint.orEmpty().lowercase()
        if ("claude" in hint) return KnowledgeSource.CLAUDE
        if ("chatgpt" in hint || "conversation" in hint) {
            if (looksLikeChatGpt(root)) return KnowledgeSource.CHATGPT
        }
        if (looksLikeChatGpt(root)) return KnowledgeSource.CHATGPT
        if (looksLikeClaude(root)) return KnowledgeSource.CLAUDE
        return KnowledgeSource.IMPORT_INBOX
    }

    private fun looksLikeChatGpt(root: Any): Boolean {
        val first = firstObject(root) ?: return false
        return first.has("mapping") || first.has("conversation_id") && first.has("current_node")
    }

    private fun looksLikeClaude(root: Any): Boolean {
        val first = firstObject(root) ?: return false
        return first.has("chat_messages") || first.has("uuid") && (first.has("name") || first.has("summary"))
    }

    private fun firstObject(root: Any): JSONObject? = when (root) {
        is JSONArray -> (0 until root.length()).asSequence().mapNotNull { root.optJSONObject(it) }.firstOrNull()
        is JSONObject -> root.optJSONArray("conversations")?.optJSONObject(0) ?: root
        else -> null
    }

    private data class Turn(val role: String, val text: String, val tsMs: Long)

    private fun parseChatGpt(root: Any): Parsed {
        val conversations = arrayFromRoot(root, "conversations")
        val docs = mutableListOf<KnowledgeDocument>()
        val warnings = mutableListOf<String>()
        for (i in 0 until conversations.length()) {
            val conversation = conversations.optJSONObject(i) ?: continue
            val id = conversation.optString("id").ifBlank {
                conversation.optString("conversation_id").ifBlank { "chatgpt-$i" }
            }
            val title = conversation.optString("title").ifBlank { "ChatGPT conversation ${i + 1}" }
            val mapping = conversation.optJSONObject("mapping")
            if (mapping == null) {
                warnings += "$title had no mapping and was skipped."
                continue
            }
            val turns = mutableListOf<Turn>()
            val keys = mapping.keys()
            while (keys.hasNext()) {
                val node = mapping.optJSONObject(keys.next()) ?: continue
                val message = node.optJSONObject("message") ?: continue
                val author = message.optJSONObject("author")?.optString("role").orEmpty()
                if (author !in setOf("user", "assistant", "system", "tool")) continue
                val content = chatGptContentText(message.optJSONObject("content"))
                if (content.isBlank()) continue
                val seconds = message.optDouble("create_time", 0.0)
                turns += Turn(author, content, if (seconds > 0) (seconds * 1000.0).toLong() else 0L)
            }
            val sorted = turns.sortedWith(compareBy<Turn> { if (it.tsMs == 0L) Long.MAX_VALUE else it.tsMs })
            if (sorted.isEmpty()) continue
            val updatedAt = sorted.maxOfOrNull { it.tsMs }?.takeIf { it > 0 }
                ?: ((conversation.optDouble("update_time", 0.0) * 1000.0).toLong()).takeIf { it > 0 }
                ?: System.currentTimeMillis()
            docs += KnowledgeDocument(
                source = KnowledgeSource.CHATGPT,
                sourceId = id,
                title = title,
                text = turnsToMarkdown(title, "ChatGPT", sorted),
                updatedAtMs = updatedAt,
                userAuthoredText = sorted.filter { it.role == "user" }.joinToString("\n\n") { it.text },
            )
        }
        return Parsed(KnowledgeSource.CHATGPT, docs, warnings)
    }

    private fun chatGptContentText(content: JSONObject?): String {
        if (content == null) return ""
        val parts = content.optJSONArray("parts") ?: return content.optString("text")
        return (0 until parts.length()).mapNotNull { index ->
            when (val value = parts.opt(index)) {
                is String -> value
                is JSONObject -> value.optString("text").takeIf { it.isNotBlank() }
                else -> null
            }
        }.joinToString("\n").trim()
    }

    private fun parseClaude(root: Any): Parsed {
        val conversations = arrayFromRoot(root, "conversations")
        val docs = mutableListOf<KnowledgeDocument>()
        val warnings = mutableListOf<String>()
        for (i in 0 until conversations.length()) {
            val conversation = conversations.optJSONObject(i) ?: continue
            val id = conversation.optString("uuid").ifBlank {
                conversation.optString("id").ifBlank { "claude-$i" }
            }
            val title = conversation.optString("name").ifBlank {
                conversation.optString("title").ifBlank { "Claude conversation ${i + 1}" }
            }
            val messages = conversation.optJSONArray("chat_messages")
                ?: conversation.optJSONArray("messages")
                ?: JSONArray()
            val turns = mutableListOf<Turn>()
            for (j in 0 until messages.length()) {
                val message = messages.optJSONObject(j) ?: continue
                val rawRole = message.optString("sender").ifBlank { message.optString("role") }.lowercase()
                val role = when (rawRole) {
                    "human", "user" -> "user"
                    "assistant", "claude" -> "assistant"
                    else -> rawRole.ifBlank { "assistant" }
                }
                val body = claudeMessageText(message)
                if (body.isBlank()) continue
                turns += Turn(role, body, parseClaudeTimestamp(message))
            }
            if (turns.isEmpty()) {
                warnings += "$title had no readable chat messages and was skipped."
                continue
            }
            val updatedAt = turns.maxOfOrNull { it.tsMs }?.takeIf { it > 0 } ?: System.currentTimeMillis()
            docs += KnowledgeDocument(
                source = KnowledgeSource.CLAUDE,
                sourceId = id,
                title = title,
                text = turnsToMarkdown(title, "Claude", turns),
                updatedAtMs = updatedAt,
                userAuthoredText = turns.filter { it.role == "user" }.joinToString("\n\n") { it.text },
            )
        }
        return Parsed(KnowledgeSource.CLAUDE, docs, warnings)
    }

    private fun claudeMessageText(message: JSONObject): String {
        val text = message.optString("text")
        if (text.isNotBlank()) return text.trim()
        val content = message.opt("content")
        return when (content) {
            is String -> content.trim()
            is JSONArray -> (0 until content.length()).mapNotNull { i ->
                when (val part = content.opt(i)) {
                    is String -> part
                    is JSONObject -> part.optString("text").takeIf { it.isNotBlank() }
                    else -> null
                }
            }.joinToString("\n").trim()
            else -> ""
        }
    }

    private fun parseClaudeTimestamp(message: JSONObject): Long {
        val numeric = message.optLong("created_at", 0L)
        if (numeric > 10_000_000_000L) return numeric
        if (numeric > 0L) return numeric * 1000L
        // ISO timestamps are left unordered rather than adding a java.time API requirement here.
        return 0L
    }

    private fun parseGenericJson(root: Any, source: KnowledgeSource): List<KnowledgeDocument> {
        val text = when (root) {
            is JSONArray -> root.toString(2)
            is JSONObject -> root.toString(2)
            else -> root.toString()
        }
        return listOf(
            KnowledgeDocument(
                source = source,
                sourceId = "generic-${text.hashCode()}",
                title = "Imported JSON",
                text = text,
                updatedAtMs = System.currentTimeMillis(),
            )
        )
    }

    private fun arrayFromRoot(root: Any, objectKey: String): JSONArray = when (root) {
        is JSONArray -> root
        is JSONObject -> root.optJSONArray(objectKey) ?: JSONArray().put(root)
        else -> JSONArray()
    }

    private fun turnsToMarkdown(title: String, provider: String, turns: List<Turn>): String = buildString {
        appendLine("# $title")
        appendLine()
        appendLine("_Imported from $provider. Assistant statements are context, not confirmed user facts._")
        turns.forEach { turn ->
            appendLine()
            appendLine(if (turn.role == "user") "## User" else "## Assistant")
            appendLine(turn.text.trim())
        }
    }.trim()
}
