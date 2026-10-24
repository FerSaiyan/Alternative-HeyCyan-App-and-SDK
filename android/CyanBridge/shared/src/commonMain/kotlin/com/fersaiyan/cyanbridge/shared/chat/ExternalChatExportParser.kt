package com.fersaiyan.cyanbridge.shared.chat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/**
 * Parses ChatGPT and Claude account exports (conversations.json) into chat
 * threads. Platform-neutral port of the Android ExternalAiExportParser.
 */
object ExternalChatExportParser {
    enum class Provider(val label: String) { CHATGPT("ChatGPT"), CLAUDE("Claude") }

    data class Turn(val role: String, val text: String, val timestampMs: Long)

    data class Conversation(val id: String, val title: String, val turns: List<Turn>)

    fun parse(text: String, provider: Provider): List<Conversation> {
        val root = Json.parseToJsonElement(text.trim())
        val conversations = when (root) {
            is JsonArray -> root
            is JsonObject -> (root["conversations"] as? JsonArray) ?: JsonArray(listOf(root))
            else -> JsonArray(emptyList())
        }
        return conversations.mapIndexedNotNull { index, element ->
            val conversation = element as? JsonObject ?: return@mapIndexedNotNull null
            when (provider) {
                Provider.CHATGPT -> parseChatGpt(conversation, index)
                Provider.CLAUDE -> parseClaude(conversation, index)
            }
        }
    }

    private fun parseChatGpt(conversation: JsonObject, index: Int): Conversation? {
        val id = conversation.string("id") ?: conversation.string("conversation_id") ?: "chatgpt-$index"
        val title = conversation.string("title") ?: "ChatGPT conversation ${index + 1}"
        val mapping = conversation["mapping"] as? JsonObject ?: return null
        val turns = mapping.values.mapNotNull { node ->
            val message = (node as? JsonObject)?.get("message") as? JsonObject ?: return@mapNotNull null
            val role = (message["author"] as? JsonObject)?.string("role").orEmpty()
            if (role != "user" && role != "assistant") return@mapNotNull null
            val content = chatGptContent(message["content"] as? JsonObject)
            if (content.isBlank()) return@mapNotNull null
            val seconds = (message["create_time"] as? JsonPrimitive)?.doubleOrNull ?: 0.0
            Turn(role, content, (seconds * 1000).toLong())
        }.sortedBy { if (it.timestampMs == 0L) Long.MAX_VALUE else it.timestampMs }
        return turns.takeIf { it.isNotEmpty() }?.let { Conversation(id, title, it) }
    }

    private fun chatGptContent(content: JsonObject?): String {
        if (content == null) return ""
        val parts = content["parts"] as? JsonArray ?: return content.string("text").orEmpty()
        return parts.mapNotNull { part ->
            when (part) {
                is JsonPrimitive -> part.contentOrNull
                is JsonObject -> part.string("text")
                else -> null
            }
        }.joinToString("\n").trim()
    }

    private fun parseClaude(conversation: JsonObject, index: Int): Conversation? {
        val id = conversation.string("uuid") ?: conversation.string("id") ?: "claude-$index"
        val title = conversation.string("name") ?: conversation.string("title") ?: "Claude conversation ${index + 1}"
        val messages = (conversation["chat_messages"] ?: conversation["messages"]) as? JsonArray ?: return null
        val turns = messages.mapNotNull { element ->
            val message = element as? JsonObject ?: return@mapNotNull null
            val role = when ((message.string("sender") ?: message.string("role")).orEmpty().lowercase()) {
                "human", "user" -> "user"
                else -> "assistant"
            }
            val body = claudeText(message)
            if (body.isBlank()) return@mapNotNull null
            val created = (message["created_at"] as? JsonPrimitive)?.longOrNull ?: 0L
            Turn(role, body, if (created in 1..10_000_000_000L) created * 1000 else created)
        }
        return turns.takeIf { it.isNotEmpty() }?.let { Conversation(id, title, it) }
    }

    private fun claudeText(message: JsonObject): String {
        message.string("text")?.takeIf { it.isNotBlank() }?.let { return it.trim() }
        return when (val content = message["content"]) {
            is JsonPrimitive -> content.contentOrNull.orEmpty().trim()
            is JsonArray -> content.mapNotNull { part ->
                when (part) {
                    is JsonPrimitive -> part.contentOrNull
                    is JsonObject -> part.string("text")
                    else -> null
                }
            }.joinToString("\n").trim()
            else -> ""
        }
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
}
