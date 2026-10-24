package com.fersaiyan.cyanbridge.ai.localagent

import com.fersaiyan.cyanbridge.shared.json.JsonSyntaxException
import com.fersaiyan.cyanbridge.shared.json.optInt
import com.fersaiyan.cyanbridge.shared.json.optJsonArray
import com.fersaiyan.cyanbridge.shared.json.optJsonObject
import com.fersaiyan.cyanbridge.shared.json.optString
import com.fersaiyan.cyanbridge.shared.json.optStringOrNull
import com.fersaiyan.cyanbridge.shared.json.parseJsonObject
import com.fersaiyan.cyanbridge.shared.json.toOrgJsonString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * LocalAgent brain contract.
 *
 * JSON is read with the shared org.json-compatible reader, so it is as lenient as before.
 *
 * The contract is designed for LLM calls where we must:
 *  - provide a strict JSON schema in the prompt
 *  - extract JSON even if the model wraps it in code fences / extra text
 *  - parse into strongly typed actions
 *  - surface clear, actionable errors when the response is malformed
 */
object LocalAgentProtocol {

    const val CURRENT_VERSION: Int = 1

    /**
     * Output schema the brain MUST follow.
     *
     * Note: This is human/LLM-facing JSON Schema-like documentation.
     * We validate in code (see [parseBrainResponse]).
     */
    val BRAIN_RESPONSE_JSON_SCHEMA: String = """
        {
          "title": "LocalAgentBrainResponse",
          "type": "object",
          "additionalProperties": false,
          "required": ["version"],
          "properties": {
            "version": {"type": "integer", "enum": [1]},
            "assistant_message": {"type": "string", "description": "What to say to the user (optional)."},
            "actions": {
              "type": "array",
              "description": "Actions for the app to execute, in order.",
              "items": {
                "type": "object",
                "additionalProperties": true,
                "required": ["type"],
                "properties": {
                  "type": {
                    "type": "string",
                    "enum": [
                      "noop",
                      "speak",
                      "toast",
                      "start_meeting_capture",
                      "stop_meeting_capture",
                      "open_screen",
                      "broadcast_intent"
                    ]
                  },
                  "text": {"type": "string"},
                  "timer_seconds": {"type": "integer", "minimum": 1},
                  "screen": {"type": "string", "enum": ["home", "settings", "recordings", "chats"]},
                  "action": {"type": "string", "description": "Android Intent action string"},
                  "extras": {"type": "object", "additionalProperties": {"type": "string"}}
                }
              }
            },
            "debug": {
              "type": "object",
              "description": "Optional debug info for logs. Never include secrets.",
              "additionalProperties": true
            }
          }
        }
    """.trimIndent()

    data class LocalAgentPrompt(
        val system: String,
        val user: String,
    )

    /**
     * Observation we send to the brain.
     * Keep this small, deterministic, and safe to log.
     */
    data class LocalAgentObservation(
        val user_message: String,
        val app_context: Map<String, String> = emptyMap(),
    ) {
        fun toJson(): JsonObject = buildJsonObject {
            put("version", CURRENT_VERSION)
            put("user_message", user_message)
            putJsonObject("app_context") {
                for ((k, v) in app_context) put(k, v)
            }
        }
    }

    sealed interface LocalAgentAction {
        val type: String
    }

    data class NoOpAction(
        val reason: String? = null,
    ) : LocalAgentAction {
        override val type: String = "noop"
    }

    data class SpeakAction(
        val text: String,
    ) : LocalAgentAction {
        override val type: String = "speak"
    }

    data class ToastAction(
        val text: String,
    ) : LocalAgentAction {
        override val type: String = "toast"
    }

    data class StartMeetingCaptureAction(
        val timerSeconds: Int? = null,
    ) : LocalAgentAction {
        override val type: String = "start_meeting_capture"
    }

    data class StopMeetingCaptureAction(
        override val type: String = "stop_meeting_capture",
    ) : LocalAgentAction

    data class OpenScreenAction(
        val screen: Screen,
    ) : LocalAgentAction {
        override val type: String = "open_screen"

        enum class Screen {
            home,
            settings,
            recordings,
            chats,
        }
    }

    data class BroadcastIntentAction(
        val action: String,
        val extras: Map<String, String> = emptyMap(),
    ) : LocalAgentAction {
        override val type: String = "broadcast_intent"
    }

    /**
     * If the brain returns an unknown/unmodeled action type, we preserve it.
     * The caller may choose to ignore or log.
     */
    data class UnknownAction(
        override val type: String,
        val raw: JsonObject,
    ) : LocalAgentAction

    data class LocalAgentBrainResponse(
        val version: Int,
        val assistantMessage: String?,
        val actions: List<LocalAgentAction>,
        val debug: JsonObject?,
    )

    open class LocalAgentProtocolException(message: String, cause: Throwable? = null) : Exception(message, cause)

    class LocalAgentJsonExtractionException(message: String) : LocalAgentProtocolException(message)

    class LocalAgentJsonParseException(message: String, cause: Throwable? = null) : LocalAgentProtocolException(message, cause)

    class LocalAgentSchemaViolationException(message: String) : LocalAgentProtocolException(message)

    /**
     * Build the (system, user) prompt pair for a chat-completions style API.
     */
    fun buildPrompt(observation: LocalAgentObservation): LocalAgentPrompt {
        val system = buildString {
            appendLine("You are the CyanBridge LocalAgent brain.")
            appendLine("You MUST respond with a single JSON object and nothing else (no markdown, no code fences, no commentary).")
            appendLine("Your JSON MUST follow this schema:")
            appendLine(BRAIN_RESPONSE_JSON_SCHEMA)
            appendLine()
            appendLine("Rules:")
            appendLine("- If no action is required, return actions: [{\"type\":\"noop\"}] and optionally assistant_message.")
            appendLine("- Never invent app state; only act using the observation and app_context.")
            appendLine("- Keep assistant_message concise.")
            appendLine("- Do not include secrets in debug.")
        }

        val user = buildString {
            appendLine("Observation (JSON):")
            appendLine(observation.toJson().toOrgJsonString(2))
        }

        return LocalAgentPrompt(system = system.trim(), user = user.trim())
    }

    /**
     * Parse a raw LLM response into a typed [LocalAgentBrainResponse].
     *
     * This is robust to common formatting issues:
     *  - wrapping in ```json code fences
     *  - leading/trailing prose (we extract the first JSON object)
     */
    fun parseBrainResponse(raw: String): LocalAgentBrainResponse {
        val jsonText = extractJsonObjectText(raw)

        val obj = try {
            parseJsonObject(jsonText)
        } catch (e: JsonSyntaxException) {
            throw LocalAgentJsonParseException(
                message = "Brain response is not valid JSON: ${e.message}. Extracted=${jsonText.preview()}",
                cause = e,
            )
        }

        val version = obj.optInt("version", -1)
        if (version != CURRENT_VERSION) {
            throw LocalAgentSchemaViolationException(
                "Brain response version mismatch. Expected=$CURRENT_VERSION got=$version. Raw=${jsonText.preview()}"
            )
        }

        val assistantMessage = obj.optStringOrNull("assistant_message")?.trim()?.takeIf { it.isNotBlank() }

        val actionsJson = obj.optJsonArray("actions")
        val actions = if (actionsJson != null) {
            parseActions(actionsJson)
        } else {
            emptyList()
        }

        if (assistantMessage == null && actions.isEmpty()) {
            throw LocalAgentSchemaViolationException(
                "Brain response must include at least one of assistant_message or actions. Raw=${jsonText.preview()}"
            )
        }

        val debug = obj.optJsonObject("debug")

        return LocalAgentBrainResponse(
            version = version,
            assistantMessage = assistantMessage,
            actions = actions,
            debug = debug,
        )
    }

    private fun parseActions(arr: JsonArray): List<LocalAgentAction> {
        val out = ArrayList<LocalAgentAction>(arr.size)
        for (i in arr.indices) {
            val item = arr[i]
            if (item !is JsonObject) {
                throw LocalAgentSchemaViolationException("actions[$i] must be an object")
            }
            val type = item.optStringOrNull("type")?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: throw LocalAgentSchemaViolationException("actions[$i] missing required field: type")

            val action = when (type) {
                "noop" -> NoOpAction(reason = item.optStringOrNull("reason")?.trim()?.takeIf { it.isNotBlank() })

                "speak" -> {
                    val text = item.optString("text", "").trim()
                    if (text.isBlank()) throw LocalAgentSchemaViolationException("actions[$i].text required for type=speak")
                    SpeakAction(text)
                }

                "toast" -> {
                    val text = item.optString("text", "").trim()
                    if (text.isBlank()) throw LocalAgentSchemaViolationException("actions[$i].text required for type=toast")
                    ToastAction(text)
                }

                "start_meeting_capture" -> {
                    val timer = if (item.containsKey("timer_seconds")) item.optInt("timer_seconds", -1) else -1
                    val timerSeconds = when {
                        timer <= 0 -> null
                        else -> timer
                    }
                    StartMeetingCaptureAction(timerSeconds = timerSeconds)
                }

                "stop_meeting_capture" -> StopMeetingCaptureAction()

                "open_screen" -> {
                    val screenStr = item.optString("screen", "").trim()
                    if (screenStr.isBlank()) throw LocalAgentSchemaViolationException("actions[$i].screen required for type=open_screen")
                    val screen = runCatching { OpenScreenAction.Screen.valueOf(screenStr) }.getOrNull()
                        ?: throw LocalAgentSchemaViolationException("actions[$i].screen invalid: $screenStr")
                    OpenScreenAction(screen)
                }

                "broadcast_intent" -> {
                    val action = item.optString("action", "").trim()
                    if (action.isBlank()) throw LocalAgentSchemaViolationException("actions[$i].action required for type=broadcast_intent")

                    val extras = item.optJsonObject("extras")?.let { extrasObj ->
                        val map = LinkedHashMap<String, String>()
                        for (k in extrasObj.keys) {
                            val v = extrasObj.optStringOrNull(k)
                            if (v != null) map[k] = v
                        }
                        map
                    } ?: emptyMap()

                    BroadcastIntentAction(action = action, extras = extras)
                }

                else -> UnknownAction(type = type, raw = item)
            }

            out.add(action)
        }

        return out
    }

    private fun extractJsonObjectText(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) throw LocalAgentJsonExtractionException("Brain response was empty")

        // Prefer fenced JSON blocks.
        val fenced = FENCED_JSON_REGEX.find(trimmed)
        if (fenced != null) {
            val inner = fenced.groupValues.getOrNull(1)?.trim().orEmpty()
            if (inner.startsWith("{")) return inner
        }

        // Otherwise, extract the first JSON object by scanning for the first '{' and matching braces.
        val firstBrace = trimmed.indexOf('{')
        if (firstBrace < 0) {
            throw LocalAgentJsonExtractionException(
                "Brain response did not contain a JSON object '{...}'. Raw=${trimmed.preview()}"
            )
        }

        val extracted = extractBalancedBraces(trimmed.substring(firstBrace))
            ?: throw LocalAgentJsonExtractionException(
                "Brain response contained '{' but we could not find the matching '}'. Raw=${trimmed.preview()}"
            )

        return extracted
    }

    private fun extractBalancedBraces(textStartingWithBrace: String): String? {
        // Minimal state machine to find the closing brace while respecting JSON strings.
        var depth = 0
        var inString = false
        var escape = false

        for (i in textStartingWithBrace.indices) {
            val c = textStartingWithBrace[i]

            if (inString) {
                if (escape) {
                    escape = false
                } else {
                    when (c) {
                        '\\' -> escape = true
                        '"' -> inString = false
                    }
                }
                continue
            }

            when (c) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) {
                        return textStartingWithBrace.substring(0, i + 1)
                    }
                }
            }
        }

        return null
    }

    private fun String.preview(maxChars: Int = 400): String {
        val s = this.replace("\n", "\\n")
        return if (s.length <= maxChars) s else s.take(maxChars) + "…"
    }

    private val FENCED_JSON_REGEX = Regex("""```(?:json)?\s*([\s\S]*?)\s*```""", RegexOption.IGNORE_CASE)
}
