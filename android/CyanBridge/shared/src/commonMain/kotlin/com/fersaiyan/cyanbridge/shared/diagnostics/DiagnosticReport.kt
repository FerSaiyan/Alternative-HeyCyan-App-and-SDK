package com.fersaiyan.cyanbridge.shared.diagnostics

import com.fersaiyan.cyanbridge.shared.format.formatLocalDateTime
import com.fersaiyan.cyanbridge.shared.platform.platformUtcOffsetMinutes
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Why a report is sent. Serialized as [code]. */
enum class DiagnosticTrigger(val code: String) {
    VOLUNTARY("voluntary"),
    ERROR_PROMPT("error_prompt"),
    PRO_RESTORE("pro_restore"),
    FIRMWARE_REQUEST("firmware_request"),
}

/** How the previous process ended (Android ApplicationExitInfo, plus our own crash record). */
data class DiagnosticLastExit(
    val pid: Int?,
    val reason: String,
    val timeMs: Long,
    val description: String? = null,
    val importance: Int? = null,
    val pssKb: Long? = null,
    val rssKb: Long? = null,
    /** Java stack trace saved by the crash handler. */
    val stack: String? = null,
    /** ANR trace (main thread first) or native tombstone excerpt. */
    val trace: String? = null,
    /** That process's own logcat lines, when the log buffer still holds them. */
    val logcatTail: String? = null,
    /** The event buffer saved by the crash handler. */
    val events: List<DiagnosticEvent> = emptyList(),
)

data class DiagnosticReportInput(
    val trigger: DiagnosticTrigger,
    val issueType: String,
    /** The app's own description (error prompts) or the user's text (voluntary reports). */
    val description: String,
    /** What the user typed, if anything. */
    val userNote: String?,
    /** Given by the user for a reply; sent as its own field, never inside the logs. */
    val contactEmail: String?,
    val requestMetadata: String?,
    val appVersion: String,
    val versionCode: Long,
    val buildType: String,
    /** Section -> key -> value: app, device, glasses, ai, settings, pro, resources, permissions. */
    val header: Map<String, Map<String, String>>,
    /** Older free-text diagnostics (automation, Meta DAT, MYVU, the caller's extra info). */
    val extraDiagnostics: String,
    val events: List<DiagnosticEvent>,
    val eventWindowMs: Long,
    val lastExit: DiagnosticLastExit?,
    /** This process's logcat, already filtered by PID and per-tag caps. */
    val logcat: String,
    val createdAtMs: Long,
    /** Host the report goes to, for the preview. */
    val destination: String,
)

/**
 * A support report ready to send to `POST /logs/submit`. [json] keeps the legacy text fields
 * (`logs`, `device_info`, …) that servers before report version 2 store, and adds the structured
 * v2 fields (`header`, `events`, `last_exit`, …). [preview] is exactly the text the user is shown.
 */
class DiagnosticReport(val json: JsonObject, val preview: String, val redactionCount: Int) {
    val serialized: String by lazy { json.toString() }
}

/** Builds [DiagnosticReport]s: redaction, size budgets, legacy text plus structured fields. */
object DiagnosticReportComposer {
    const val REPORT_VERSION = 2
    const val MAX_DESCRIPTION = 2_000
    const val MAX_USER_NOTE = 1_000
    const val MAX_DEVICE_INFO = 12_000
    const val MAX_LOGS = 118_000
    const val MAX_EVENTS_TEXT = 48_000
    const val MAX_EXIT_TEXT = 14_000
    const val MAX_EVENTS = 600
    const val MAX_EVENTS_JSON = 90_000
    const val MAX_EXIT_EVENTS = 120
    const val MAX_TRACE = 16_000
    const val MAX_LOGCAT_TAIL = 16_000

    fun compose(input: DiagnosticReportInput): DiagnosticReport {
        var redactions = 0
        fun clean(text: String?): String? {
            if (text.isNullOrBlank()) return text?.takeIf { it.isNotEmpty() }
            val result = DiagnosticRedactor.redact(text)
            redactions += result.count
            return result.text
        }
        fun cleanEvent(event: DiagnosticEvent) = event.copy(
            message = clean(event.message).orEmpty(),
            fields = event.fields.mapValues { clean(it.value).orEmpty() },
            stack = clean(event.stack),
        )

        val description = clean(input.description).orEmpty().trim().take(MAX_DESCRIPTION)
        val userNote = clean(input.userNote)?.trim()?.take(MAX_USER_NOTE)?.takeIf { it.isNotEmpty() }
        val requestMetadata = clean(input.requestMetadata)?.trim()?.takeIf { it.isNotEmpty() }
        val header = input.header.mapValues { (_, section) -> section.mapValues { clean(it.value).orEmpty().take(300) } }
        val events = fitEvents(input.events.takeLast(MAX_EVENTS).map(::cleanEvent))
        val exit = input.lastExit?.let { exit ->
            exit.copy(
                description = clean(exit.description)?.take(1_000),
                stack = clean(exit.stack)?.take(DiagnosticEventLog.MAX_STACK_CHARS * 2),
                trace = clean(exit.trace)?.take(MAX_TRACE),
                logcatTail = clean(exit.logcatTail)?.takeLast(MAX_LOGCAT_TAIL),
                events = exit.events.takeLast(MAX_EXIT_EVENTS).map(::cleanEvent),
            )
        }
        val logcat = clean(input.logcat).orEmpty()
        val extra = clean(input.extraDiagnostics).orEmpty().trim()

        val headerText = renderHeader(header)
        val exitSummary = exit?.let { renderExitSummary(it) }.orEmpty()
        val deviceInfo = buildString {
            append(headerText)
            if (exitSummary.isNotEmpty()) append("\n").append(exitSummary)
            if (extra.isNotEmpty()) {
                append("\n== Other diagnostics ==\n")
                append(extra.take((MAX_DEVICE_INFO - length - 40).coerceAtLeast(0)))
            }
        }.take(MAX_DEVICE_INFO)

        val eventsText = renderEvents(events).takeLast(MAX_EVENTS_TEXT)
        val exitText = exit?.let { renderExitDetail(it) }?.take(MAX_EXIT_TEXT).orEmpty()
        val logs = buildString {
            append("=== Recent events: ${events.size} (last ${input.eventWindowMs / 60_000} min, plus earlier errors) ===\n")
            append(eventsText.ifEmpty { "(none)\n" })
            if (exitText.isNotEmpty()) append("\n=== Last crash / ANR ===\n").append(exitText).append('\n')
            append("\n=== Logcat: this app's process only, redacted ===\n")
            append(logcat.takeLast((MAX_LOGS - length - 2).coerceAtLeast(0)))
        }

        val json = buildJsonObject {
            put("issue_type", input.issueType.take(120))
            put("description", description.ifEmpty { userNote ?: "No description provided" })
            put("logs", logs)
            put("device_info", deviceInfo)
            put("app_version", input.appVersion)
            input.contactEmail?.trim()?.takeIf { it.isNotEmpty() }?.let { put("contact_email", it) }
            requestMetadata?.let { put("request_metadata", it) }
            put("report_version", REPORT_VERSION)
            put("trigger", input.trigger.code)
            userNote?.let { put("user_note", it) }
            put("app_build", buildJsonObject {
                put("version_code", input.versionCode)
                put("build_type", input.buildType)
            })
            put("header", JsonObject(header.mapValues { (_, section) -> JsonObject(section.mapValues { JsonPrimitive(it.value) }) }))
            put("events", eventsJson(events))
            put("event_window_ms", input.eventWindowMs)
            put("last_exit", exit?.let(::exitJson) ?: JsonNull)
            put("redaction", buildJsonObject {
                put("version", DiagnosticRedactor.VERSION)
                put("count", redactions)
            })
        }

        val preview = buildString {
            append("Sent to CyanBridge support (${input.destination}), ${formatLocalDateTime(input.createdAtMs)}\n")
            append("Issue: ${input.issueType}\n")
            if (description.isNotEmpty()) append("Description: $description\n")
            userNote?.takeIf { it != description }?.let { append("Your note: $it\n") }
            input.contactEmail?.takeIf { it.isNotBlank() }?.let { append("Contact email (for a reply): $it\n") }
            append("\n").append(deviceInfo).append("\n\n").append(logs)
        }
        return DiagnosticReport(json, preview, redactions)
    }

    /** Drops the oldest non-error events until the serialized list fits [MAX_EVENTS_JSON]. */
    internal fun fitEvents(events: List<DiagnosticEvent>): List<DiagnosticEvent> {
        val kept = events.toMutableList()
        var size = kept.sumOf { estimatedJsonSize(it) }
        while (size > MAX_EVENTS_JSON && kept.isNotEmpty()) {
            val index = kept.indexOfFirst { it.level != DiagnosticLevel.ERROR }.takeIf { it >= 0 } ?: 0
            size -= estimatedJsonSize(kept.removeAt(index))
        }
        return kept
    }

    private fun estimatedJsonSize(event: DiagnosticEvent): Int =
        48 + event.message.length + (event.stack?.length ?: 0) + event.fields.entries.sumOf { it.key.length + it.value.length + 6 }

    private fun eventsJson(events: List<DiagnosticEvent>): JsonArray = buildJsonArray {
        events.forEach { add(eventJson(it)) }
    }

    private fun eventJson(event: DiagnosticEvent): JsonElement = buildJsonObject {
        put("t", event.timeMs)
        put("s", event.subsystem.code)
        put("l", event.level.code)
        put("m", event.message)
        if (event.fields.isNotEmpty()) put("f", JsonObject(event.fields.mapValues { JsonPrimitive(it.value) }))
        event.stack?.let { put("x", it) }
        if (event.repeats > 0) put("r", event.repeats)
    }

    private fun exitJson(exit: DiagnosticLastExit): JsonObject = buildJsonObject {
        exit.pid?.let { put("pid", it) }
        put("reason", exit.reason)
        put("t", exit.timeMs)
        exit.description?.let { put("description", it) }
        exit.importance?.let { put("importance", it) }
        exit.pssKb?.let { put("pss_kb", it) }
        exit.rssKb?.let { put("rss_kb", it) }
        exit.stack?.let { put("stack", it) }
        exit.trace?.let { put("trace", it) }
        exit.logcatTail?.let { put("logcat_tail", it) }
        if (exit.events.isNotEmpty()) put("events", eventsJson(exit.events))
    }

    internal fun renderHeader(header: Map<String, Map<String, String>>): String = buildString {
        header.forEach { (section, values) ->
            append("== ").append(section.replaceFirstChar { it.uppercase() }).append(" ==\n")
            values.forEach { (key, value) -> append(key).append(": ").append(value).append('\n') }
        }
    }

    private fun renderExitSummary(exit: DiagnosticLastExit) = buildString {
        append("== Last exit ==\n")
        append("reason: ${exit.reason}\n")
        append("time: ${formatLocalDateTime(exit.timeMs)}\n")
        exit.description?.let { append("description: $it\n") }
        exit.stack?.lineSequence()?.firstOrNull { it.isNotBlank() }?.let { append("exception: $it\n") }
    }

    private fun renderExitDetail(exit: DiagnosticLastExit) = buildString {
        append("${exit.reason} at ${formatLocalDateTime(exit.timeMs)} (pid ${exit.pid ?: "?"})\n")
        exit.description?.let { append(it).append('\n') }
        exit.stack?.let { append("-- stack --\n").append(it).append('\n') }
        if (exit.events.isNotEmpty()) append("-- events before it --\n").append(renderEvents(exit.events))
        exit.trace?.let { append("-- trace --\n").append(it).append('\n') }
        exit.logcatTail?.let { append("-- its logcat --\n").append(it).append('\n') }
    }

    internal fun renderEvents(events: List<DiagnosticEvent>): String = buildString {
        events.forEach { event ->
            append(timeOfDay(event.timeMs)).append(' ').append(event.level.code).append(' ')
            append(event.subsystem.code.padEnd(8)).append(' ').append(event.message)
            event.fields.forEach { (key, value) -> append(' ').append(key).append('=').append(value) }
            if (event.repeats > 0) append(" (x").append(event.repeats + 1).append(", last ").append(timeOfDay(event.lastTimeMs)).append(')')
            append('\n')
            event.stack?.let { stack -> stack.lineSequence().take(12).forEach { append("    ").append(it).append('\n') } }
        }
    }

    /** `HH:mm:ss.SSS` in the device's time zone. */
    fun timeOfDay(epochMs: Long): String {
        val local = epochMs + platformUtcOffsetMinutes(epochMs) * 60_000L
        val ms = local.mod(86_400_000L)
        fun two(v: Long) = v.toString().padStart(2, '0')
        return "${two(ms / 3_600_000)}:${two(ms / 60_000 % 60)}:${two(ms / 1_000 % 60)}.${(ms % 1_000).toString().padStart(3, '0')}"
    }
}
