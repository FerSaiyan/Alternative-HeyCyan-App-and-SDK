package com.fersaiyan.cyanbridge.localagent

import com.fersaiyan.cyanbridge.shared.json.JsonSyntaxException
import com.fersaiyan.cyanbridge.shared.json.optBoolean
import com.fersaiyan.cyanbridge.shared.json.optDouble
import com.fersaiyan.cyanbridge.shared.json.optInt
import com.fersaiyan.cyanbridge.shared.json.optJsonObject
import com.fersaiyan.cyanbridge.shared.json.optLong
import com.fersaiyan.cyanbridge.shared.json.optString
import com.fersaiyan.cyanbridge.shared.json.optStringOrNull
import com.fersaiyan.cyanbridge.shared.json.parseJsonObject
import kotlinx.serialization.json.JsonObject

object LocalAgentUiControlProtocol {
    const val CURRENT_VERSION: Int = 1

    data class Prompt(
        val system: String,
        val user: String,
    )

    data class StepContext(
        val goal: String,
        val observation: LocalAgentObservation,
        val stepIndex: Int,
        val maxSteps: Int,
        val previousActionResult: String? = null,
        val consecutiveFailures: Int = 0,
    )

    sealed interface Action {
        val type: String
    }

    data object NoOp : Action {
        override val type: String = "noop"
    }

    data class Wait(
        val ms: Long,
    ) : Action {
        override val type: String = "wait"
    }

    data class ClickText(
        val text: String,
    ) : Action {
        override val type: String = "click_text"
    }

    data class ClickCoord(
        val x: Int,
        val y: Int,
    ) : Action {
        override val type: String = "click_coord"
    }

    data class TypeText(
        val text: String,
        val hint: String? = null,
    ) : Action {
        override val type: String = "type_text"
    }

    data object PressEnter : Action {
        override val type: String = "press_enter"
    }

    data class Scroll(
        val direction: Direction,
    ) : Action {
        override val type: String = "scroll"
    }

    data object PressBack : Action {
        override val type: String = "press_back"
    }

    data object PressHome : Action {
        override val type: String = "press_home"
    }

    data class OpenApp(
        val appName: String,
    ) : Action {
        override val type: String = "open_app"
    }

    data class Swipe(
        val startX: Int,
        val startY: Int,
        val endX: Int,
        val endY: Int,
        val durationMs: Long = 300L,
    ) : Action {
        override val type: String = "swipe"
    }

    data class LongPress(
        val x: Int,
        val y: Int,
        val durationMs: Long = 1000L,
    ) : Action {
        override val type: String = "long_press"
    }

    data object OpenNotifications : Action {
        override val type: String = "open_notifications"
    }

    data object OpenRecents : Action {
        override val type: String = "open_recents"
    }

    data class MakeCall(
        val number: String,
    ) : Action {
        override val type: String = "make_call"
    }

    data class SendSms(
        val number: String,
        val message: String,
    ) : Action {
        override val type: String = "send_sms"
    }

    data class SendEmail(
        val to: String,
        val subject: String,
        val body: String,
    ) : Action {
        override val type: String = "send_email"
    }

    data class SetAlarm(
        val hour: Int,
        val minute: Int,
        val label: String? = null,
    ) : Action {
        override val type: String = "set_alarm"
    }

    data object OpenContacts : Action {
        override val type: String = "open_contacts"
    }

    data object ToggleWifi : Action {
        override val type: String = "toggle_wifi"
    }

    data object ToggleBluetooth : Action {
        override val type: String = "toggle_bluetooth"
    }

    data object ToggleFlashlight : Action {
        override val type: String = "toggle_flashlight"
    }

    data object ReadScreenAloud : Action {
        override val type: String = "read_screen_aloud"
    }

    data class Finish(
        val message: String? = null,
    ) : Action {
        override val type: String = "finish"
    }

    enum class Direction {
        up,
        down,
    }

    data class Decision(
        val version: Int,
        val reasoning: String?,
        val action: Action,
        val isComplete: Boolean,
    )

    open class ProtocolException(message: String, cause: Throwable? = null) : Exception(message, cause)

    class JsonExtractionException(message: String) : ProtocolException(message)

    class JsonParseException(message: String, cause: Throwable? = null) : ProtocolException(message, cause)

    class SchemaViolationException(message: String) : ProtocolException(message)

    fun buildPrompt(context: StepContext): Prompt {
        val system = TASK_SYSTEM_PROMPT

        val user = buildString {
            appendLine("TASK: ${context.goal.trim()}")
            appendLine()
            appendLine("CURRENT SCREEN TEXT DUMP:")
            val snapshot = context.observation.screenSnapshot
            val screenText = if (snapshot != null) {
                snapshot.toCompressedPromptText(context.goal)
            } else {
                context.observation.screenText.orEmpty().ifBlank { "(screen unreadable)" }
            }
            appendLine(screenText)
            context.previousActionResult?.trim()?.takeIf { it.isNotEmpty() }?.let {
                appendLine()
                appendLine("PREVIOUS ACTION RESULT: $it")
            }
            if (context.consecutiveFailures >= 3) {
                appendLine()
                appendLine("WARNING: You have failed ${context.consecutiveFailures} times in a row with the same approach. You MUST try a completely different action.")
            }
            appendLine()
            appendLine("Step ${context.stepIndex}/${context.maxSteps}. Look at the text dump and coordinates. What is the next action?")
        }.trim()

        return Prompt(system = system, user = user)
    }

    fun parseDecision(raw: String): Decision {
        val jsonText = extractJsonObjectText(raw)
        val obj = try {
            parseJsonObject(jsonText)
        } catch (e: JsonSyntaxException) {
            throw JsonParseException(
                message = "UI-control response is not valid JSON: ${e.message}. Extracted=${jsonText.preview()}",
                cause = e,
            )
        }

        val actionStr = obj.optStringOrNull("action")?.trim()?.lowercase()
            ?: throw SchemaViolationException("Missing required field: action")
        val resolvedAction = ACTION_ALIASES[actionStr] ?: actionStr
        val params = obj.optJsonObject("params") ?: JsonObject(emptyMap())
        val action = parseAction(resolvedAction, params)

        return Decision(
            version = CURRENT_VERSION,
            reasoning = obj.optStringOrNull("reasoning")?.trim()?.takeIf { it.isNotBlank() },
            action = action,
            isComplete = obj.optBoolean("is_complete", false),
        )
    }

    private fun parseAction(type: String, params: JsonObject): Action {
        return when (type) {
            "noop" -> NoOp
            "wait" -> Wait(ms = params.optLong("ms", 500L).coerceAtLeast(0L))
            "click_text" -> {
                val text = params.optString("text", "").trim()
                    .ifBlank { params.optString("label", "").trim() }
                    .ifBlank { params.optString("element", "").trim() }
                    .ifBlank { params.optString("target", "").trim() }
                if (text.isBlank()) throw SchemaViolationException("params.text is required for click_text")
                ClickText(text)
            }
            "click_coord", "click_at" -> {
                val x = params.optDouble("x", Double.NaN)
                val y = params.optDouble("y", Double.NaN)
                if (!x.isFinite() || !y.isFinite()) {
                    throw SchemaViolationException("params.x and params.y are required for click_coord")
                }
                ClickCoord(x = x.toInt(), y = y.toInt())
            }
            "type_text" -> {
                val text = params.optString("text", "").trim()
                    .ifBlank { params.optString("content", "").trim() }
                    .ifBlank { params.optString("value", "").trim() }
                    .ifBlank { params.optString("input", "").trim() }
                    .ifBlank { params.optString("query", "").trim() }
                if (text.isBlank()) {
                    val fallback = params.keys.asSequence()
                        .mapNotNull { key -> params.optStringOrNull(key)?.trim()?.takeIf { v -> v.isNotBlank() } }
                        .firstOrNull()
                    if (fallback != null) {
                        TypeText(text = fallback, hint = params.optStringOrNull("hint")
                            ?.trim()?.takeIf { it.isNotBlank() }
                            ?: params.optStringOrNull("field_hint")?.trim()?.takeIf { it.isNotBlank() })
                    } else {
                        throw SchemaViolationException("params.text is required for type_text")
                    }
                } else {
                    TypeText(text = text, hint = params.optStringOrNull("hint")
                        ?.trim()?.takeIf { it.isNotBlank() }
                        ?: params.optStringOrNull("field_hint")?.trim()?.takeIf { it.isNotBlank() })
                }
            }
            "press_enter" -> PressEnter
            "scroll" -> {
                val direction = params.optString("direction", "").trim()
                val parsed = runCatching { Direction.valueOf(direction) }.getOrNull()
                    ?: throw SchemaViolationException("params.direction must be 'up' or 'down' for scroll")
                Scroll(parsed)
            }
            "press_back" -> PressBack
            "press_home" -> PressHome
            "open_notifications" -> OpenNotifications
            "open_recents" -> OpenRecents
            "open_app" -> {
                val appName = params.optString("app_name", "").trim()
                    .ifBlank { params.optString("package_name", "").trim() }
                    .ifBlank { params.optString("app", "").trim() }
                    .ifBlank { params.optString("application", "").trim() }
                    .ifBlank { params.optString("name", "").trim() }
                if (appName.isBlank()) throw SchemaViolationException("params.app_name is required for open_app")
                OpenApp(appName)
            }
            "swipe" -> {
                val sx = params.optDouble("start_x", params.optDouble("startX", Double.NaN))
                val sy = params.optDouble("start_y", params.optDouble("startY", Double.NaN))
                val ex = params.optDouble("end_x", params.optDouble("endX", Double.NaN))
                val ey = params.optDouble("end_y", params.optDouble("endY", Double.NaN))
                if (!sx.isFinite() || !sy.isFinite() || !ex.isFinite() || !ey.isFinite()) {
                    throw SchemaViolationException("start_x, start_y, end_x, end_y are required for swipe")
                }
                Swipe(
                    startX = sx.toInt(), startY = sy.toInt(),
                    endX = ex.toInt(), endY = ey.toInt(),
                    durationMs = params.optLong("duration_ms", 300L).coerceIn(50L, 5000L),
                )
            }
            "long_press" -> {
                val x = params.optDouble("x", Double.NaN)
                val y = params.optDouble("y", Double.NaN)
                if (!x.isFinite() || !y.isFinite()) {
                    throw SchemaViolationException("params.x and params.y are required for long_press")
                }
                LongPress(x = x.toInt(), y = y.toInt(), durationMs = params.optLong("duration_ms", 1000L).coerceIn(300L, 5000L))
            }
            "make_call" -> {
                val number = params.optString("number", "").trim()
                    .ifBlank { params.optString("phone_number", "").trim() }
                    .ifBlank { params.optString("phone", "").trim() }
                    .ifBlank { params.optString("contact_name", "").trim() }
                if (number.isBlank()) throw SchemaViolationException("params.number is required for make_call")
                MakeCall(number)
            }
            "send_sms" -> {
                val number = params.optString("number", "").trim()
                    .ifBlank { params.optString("phone_number", "").trim() }
                    .ifBlank { params.optString("phone", "").trim() }
                    .ifBlank { params.optString("contact_name", "").trim() }
                val message = params.optString("message", "").trim()
                if (number.isBlank()) throw SchemaViolationException("params.number is required for send_sms")
                if (message.isBlank()) throw SchemaViolationException("params.message is required for send_sms")
                SendSms(number, message)
            }
            "send_email" -> {
                val to = params.optString("to", "").trim()
                if (to.isBlank()) throw SchemaViolationException("params.to is required for send_email")
                SendEmail(to = to, subject = params.optStringOrNull("subject").orEmpty(), body = params.optStringOrNull("body").orEmpty())
            }
            "set_alarm" -> {
                val hour = params.optInt("hour", -1)
                val minute = params.optInt("minute", -1)
                if (hour !in 0..23 || minute !in 0..59) {
                    throw SchemaViolationException("params.hour (0-23) and params.minute (0-59) are required for set_alarm")
                }
                SetAlarm(hour = hour, minute = minute, label = params.optStringOrNull("label")?.trim()?.takeIf { it.isNotBlank() })
            }
            "open_contacts" -> OpenContacts
            "toggle_wifi" -> ToggleWifi
            "toggle_bluetooth" -> ToggleBluetooth
            "toggle_flashlight" -> ToggleFlashlight
            "read_screen_aloud" -> ReadScreenAloud
            "finish", "done" -> Finish(message = params.optStringOrNull("message")?.trim()?.takeIf { it.isNotBlank() })
            else -> throw SchemaViolationException("Unsupported action type: $type")
        }
    }

    private fun extractJsonObjectText(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) throw JsonExtractionException("UI-control response was empty")

        val fenced = FENCED_JSON_REGEX.find(trimmed)
        if (fenced != null) {
            val inner = fenced.groupValues.getOrNull(1)?.trim().orEmpty()
            if (inner.startsWith("{")) return inner
        }

        val firstBrace = trimmed.indexOf('{')
        if (firstBrace >= 0) {
            val candidate = trimmed.substring(firstBrace)
            extractBalancedBraces(candidate)?.let { return it }

            // Truncated JSON — close the unfinished string/object when possible.
            val attempt = closeTruncatedJson(candidate)
            try {
                if (attempt != null) {
                    parseJsonObject(attempt)
                    return attempt
                }
            } catch (_: JsonSyntaxException) {
                // fall through
            }
        }

        throw JsonExtractionException("UI-control response did not contain a valid JSON object. Raw=${trimmed.preview()}")
    }

    private fun extractBalancedBraces(textStartingWithBrace: String): String? {
        var depth = 0
        var inString = false
        var escape = false

        for (i in textStartingWithBrace.indices) {
            val c = textStartingWithBrace[i]
            if (inString) {
                if (escape) {
                    escape = false
                } else if (c == '\\') {
                    escape = true
                } else if (c == '"') {
                    inString = false
                }
                continue
            }

            when (c) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return textStartingWithBrace.substring(0, i + 1)
                    if (depth < 0) return null
                }
            }
        }
        return null
    }

    private fun closeTruncatedJson(textStartingWithBrace: String): String? {
        var depth = 0
        var inString = false
        var escape = false

        for (c in textStartingWithBrace) {
            if (inString) {
                if (escape) {
                    escape = false
                } else if (c == '\\') {
                    escape = true
                } else if (c == '"') {
                    inString = false
                }
                continue
            }

            when (c) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth < 0) return null
                }
            }
        }

        if (depth <= 0) return null
        return buildString(textStartingWithBrace.length + depth + 1) {
            append(textStartingWithBrace)
            if (inString) append('"')
            repeat(depth) { append('}') }
        }
    }

    private fun String.preview(maxChars: Int = 400): String {
        val singleLine = replace("\n", "\\n")
        return if (singleLine.length <= maxChars) singleLine else singleLine.take(maxChars) + "…"
    }

    private val ACTION_ALIASES = mapOf(
        "click" to "click_text",
        "click_element" to "click_text",
        "tap" to "click_text",
        "tap_text" to "click_text",
        "type" to "type_text",
        "type_on_screen" to "type_text",
        "input" to "type_text",
        "input_text" to "type_text",
        "enter_text" to "type_text",
        "scroll_screen" to "scroll",
        "swipe_screen" to "swipe",
        "long_click" to "long_press",
        "back" to "press_back",
        "go_back" to "press_back",
        "home" to "press_home",
        "go_home" to "press_home",
        "notifications" to "open_notifications",
        "notification" to "open_notifications",
        "recents" to "open_recents",
        "recent_apps" to "open_recents",
        "launch_app" to "open_app",
        "start_app" to "open_app",
        "call" to "make_call",
        "phone_call" to "make_call",
        "sms" to "send_sms",
        "text_message" to "send_sms",
        "email" to "send_email",
        "alarm" to "set_alarm",
        "read_aloud" to "read_screen_aloud",
        "read_screen" to "read_screen_aloud",
        "complete" to "finish",
        "stop" to "finish",
    )

    private val TASK_SYSTEM_PROMPT = """
You are a phone automation agent. You are given a TASK and the current SCREEN content.
You must decide what single action to take next to accomplish the task.

Respond with ONLY a JSON object (no markdown, no code fences):
{"action": "action_name", "params": {"key": "value"}, "reasoning": "why", "is_complete": false}

Available actions:
- click_text: {"text": "exact text to click"} - Click an element by its visible text
- click_at: {"x": 540, "y": 960} - Click at screen coordinates (use bounds from screen dump)
- type_text: {"text": "hello", "field_hint": "optional hint"} - Type into the focused/first edit field
- press_enter: {} - Press the Enter/Search key on the keyboard to submit a search/form
- scroll: {"direction": "down"} - Scroll down/up on the current view
- swipe: {"startX": 540, "startY": 2000, "endX": 540, "endY": 500} - Swipe from start to end coordinates
- press_back: {} - Press the back button
- press_home: {} - Press the home button
- open_app: {"app_name": "WhatsApp"} - Open an app
- wait: {} - Wait a moment for content to load
- finish: {} - Task is complete

Rules:
- You will receive a TEXT DUMP of the accessibility tree containing exact text strings and center coordinates.
- ALWAYS use the text dump to decide your next action.
- If you need to click something, prefer using click_text. If the element does not have text, use click_at with the coordinates provided in the text dump.
- When typing in a search box, you MUST click it first, wait a step, and THEN type.
- After typing a search query, use press_enter once. If the screen does not change, click the exact visible suggestion text.
- Never scroll or swipe more than three times in a row.
- Set is_complete=true ONLY when the task is fully done.
- If stuck after 3 attempts, set is_complete=true and explain in reasoning.
- Keep reasoning very brief (1 sentence).
- Elements marked with * match the current task goal.
""".trimIndent()

    private val FENCED_JSON_REGEX =
        Regex("""```(?:json)?\s*([\s\S]*?)\s*```""", RegexOption.IGNORE_CASE)
}
