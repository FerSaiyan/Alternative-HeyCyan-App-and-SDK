package com.fersaiyan.cyanbridge.localagent

import com.fersaiyan.cyanbridge.shared.json.asOptString
import com.fersaiyan.cyanbridge.shared.json.optBoolean
import com.fersaiyan.cyanbridge.shared.json.optJsonArray
import com.fersaiyan.cyanbridge.shared.json.optJsonObject
import com.fersaiyan.cyanbridge.shared.json.optLong
import com.fersaiyan.cyanbridge.shared.json.optString
import com.fersaiyan.cyanbridge.shared.json.parseJsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * Small, deliberately closed Telegram command surface. Plain chat messages are never treated as
 * phone-control requests, and the caller must verify the configured chat ID before dispatching.
 */
object LocalAgentTelegramProtocol {

    sealed interface Command {
        data class Task(val goal: String) : Command
        data object Status : Command
        data object Stop : Command
        data object ReadScreen : Command
        data object Help : Command
    }

    data class Update(
        val updateId: Long,
        val chatId: String?,
        val text: String?,
    )

    fun normalizeChatId(raw: String?): String? {
        val clean = raw?.trim().orEmpty()
        if (!CHAT_ID_REGEX.matches(clean)) return null
        return clean.toLongOrNull()?.toString()
    }

    /** Telegram tokens are path-safe only after this conservative structural validation. */
    fun isValidBotToken(token: String?): Boolean =
        BOT_TOKEN_REGEX.matches(token?.trim().orEmpty())

    fun isAllowedChat(configuredChatId: String?, incomingChatId: String?): Boolean {
        val configured = normalizeChatId(configuredChatId)
        val incoming = normalizeChatId(incomingChatId)
        return configured != null && configured == incoming
    }

    fun parseCommand(text: String?): Command? {
        val input = text?.trim().orEmpty()
        if (input.isBlank() || input.length > MAX_COMMAND_CHARS || !input.startsWith('/')) return null

        val separator = input.indexOfFirst(Char::isWhitespace)
        val rawVerb = if (separator < 0) input else input.substring(0, separator)
        // Without a configured bot username, accepting /task@any_name could execute a command
        // intended for another bot in a user-configured group.
        if ('@' in rawVerb) return null
        val verb = rawVerb.lowercase()
        val argument = if (separator < 0) "" else input.substring(separator).trim()

        return when (verb) {
            "/task", "/run" -> argument
                .takeIf { it.isNotBlank() && it.length <= MAX_GOAL_CHARS }
                ?.let(Command::Task)
            "/status" -> argument.takeIf { it.isBlank() }?.let { Command.Status }
            "/stop" -> argument.takeIf { it.isBlank() }?.let { Command.Stop }
            "/read" -> argument.takeIf { it.isBlank() }?.let { Command.ReadScreen }
            "/help", "/start" -> argument.takeIf { it.isBlank() }?.let { Command.Help }
            else -> null
        }
    }

    fun parseUpdates(responseBody: String): List<Update> {
        val response = parseJsonObject(responseBody)
        if (!response.optBoolean("ok", false)) {
            throw IllegalStateException("Telegram API rejected the polling request")
        }
        val result = response.optJsonArray("result") ?: JsonArray(emptyList())
        return buildList {
            for (element in result) {
                val item = element as? JsonObject ?: continue
                val updateId = item.optLong("update_id", -1L)
                if (updateId >= 0L) {
                    val message = item.optJsonObject("message")
                    val chatId = normalizeChatId(message?.optJsonObject("chat")?.get("id")?.asOptString())
                    val text = message?.optString("text", "")?.trim()?.takeIf { it.isNotBlank() }
                    add(Update(updateId = updateId, chatId = chatId, text = text))
                }
            }
        }
    }

    fun nextOffset(updateId: Long): Long =
        if (updateId == Long.MAX_VALUE) Long.MAX_VALUE else (updateId + 1L).coerceAtLeast(0L)

    const val HELP_TEXT =
        "CyanBridge commands: /task <request>, /status, /read, /stop. " +
            "Actions still follow CyanBridge's on-phone approval settings."

    private const val MAX_COMMAND_CHARS = 1_200
    private const val MAX_GOAL_CHARS = 800
    private val CHAT_ID_REGEX = Regex("-?[0-9]{1,19}")
    private val BOT_TOKEN_REGEX = Regex("[0-9]{6,20}:[A-Za-z0-9_-]{20,128}")
}
