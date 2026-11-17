package com.fersaiyan.cyanbridge.shared.diagnostics

import com.fersaiyan.cyanbridge.shared.platform.platformCurrentTimeMillis
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/** Where a diagnostic event comes from. Each subsystem has its own share of the ring buffer. */
enum class DiagnosticSubsystem(val code: String) {
    APP("APP"),
    BLE("BLE"),
    SYNC("SYNC"),
    QUESTION("QUESTION"),
    MODEL("MODEL"),
    LIVE("LIVE"),
    META_DAT("META_DAT"),
    MENTRA("MENTRA"),
    PRO("PRO"),
    AUDIO("AUDIO"),
    UI("UI"),
}

enum class DiagnosticLevel(val code: String) { INFO("I"), WARN("W"), ERROR("E") }

/**
 * One structured event. [message] and [fields] must carry facts (states, reasons, counts, model
 * ids), never user content: no transcripts, questions, answers, message text, photos or tokens.
 * They are redacted again when a report is built. [repeats] counts identical events folded into this one.
 */
data class DiagnosticEvent(
    val seq: Long,
    val timeMs: Long,
    val subsystem: DiagnosticSubsystem,
    val level: DiagnosticLevel,
    val message: String,
    val fields: Map<String, String> = emptyMap(),
    val stack: String? = null,
    val repeats: Int = 0,
    /** When the last folded repeat happened; [timeMs] stays the first occurrence. */
    val lastTimeMs: Long = timeMs,
)

/**
 * In-memory ring buffer of recent [DiagnosticEvent]s across subsystems, for support reports.
 *
 * - At most [maxTotal] events; each subsystem keeps at most [perSubsystemCap] of them, so one noisy
 *   subsystem (Meta DAT, for example) cannot evict the others.
 * - Within a subsystem, info and warning events are evicted before errors.
 * - An event equal to the subsystem's previous one (same level, message and fields) within
 *   [foldWindowMs] only increments that event's [DiagnosticEvent.repeats].
 * - When the buffer is full, the oldest evictable event of the largest subsystem goes.
 */
class DiagnosticEventLog(
    val maxTotal: Int = DEFAULT_MAX_TOTAL,
    val perSubsystemCap: Int = DEFAULT_PER_SUBSYSTEM_CAP,
    private val foldWindowMs: Long = 120_000L,
    private val clock: () -> Long = ::platformCurrentTimeMillis,
) {
    private val lock = SynchronizedObject()
    private val lanes = LinkedHashMap<DiagnosticSubsystem, ArrayDeque<DiagnosticEvent>>()
    private var size = 0
    private var nextSeq = 1L

    fun record(
        subsystem: DiagnosticSubsystem,
        level: DiagnosticLevel,
        message: String,
        fields: Map<String, String> = emptyMap(),
        throwable: Throwable? = null,
    ) {
        val now = clock()
        val cleanMessage = message.take(MAX_MESSAGE_CHARS)
        val cleanFields = if (fields.isEmpty()) emptyMap() else fields.entries.take(MAX_FIELDS)
            .associate { (key, value) -> key.take(40) to value.take(MAX_FIELD_CHARS) }
        val stack = throwable?.let { stackOf(it) }
        synchronized(lock) {
            val lane = lanes.getOrPut(subsystem) { ArrayDeque() }
            val last = lane.lastOrNull()
            if (last != null && stack == null && last.level == level && last.message == cleanMessage &&
                last.fields == cleanFields && now - last.lastTimeMs <= foldWindowMs
            ) {
                lane[lane.lastIndex] = last.copy(repeats = last.repeats + 1, lastTimeMs = now)
                return
            }
            lane.addLast(DiagnosticEvent(nextSeq++, now, subsystem, level, cleanMessage, cleanFields, stack))
            size++
            if (lane.size > perSubsystemCap) evictFrom(lane)
            while (size > maxTotal) {
                val largest = lanes.values.maxByOrNull { it.size } ?: break
                evictFrom(largest)
            }
        }
    }

    /** All buffered events, oldest first. */
    fun snapshot(): List<DiagnosticEvent> = synchronized(lock) {
        lanes.values.flatten().sortedBy { it.seq }
    }

    /** Events newer than [sinceMs], plus every buffered error (errors are kept regardless of age). */
    fun recent(sinceMs: Long): List<DiagnosticEvent> =
        snapshot().filter { it.lastTimeMs >= sinceMs || it.level == DiagnosticLevel.ERROR }

    fun clear() = synchronized(lock) {
        lanes.clear()
        size = 0
    }

    private fun evictFrom(lane: ArrayDeque<DiagnosticEvent>) {
        val index = lane.indexOfFirst { it.level != DiagnosticLevel.ERROR }.takeIf { it >= 0 } ?: 0
        lane.removeAt(index)
        size--
    }

    companion object {
        const val DEFAULT_MAX_TOTAL = 500
        const val DEFAULT_PER_SUBSYSTEM_CAP = 150
        const val MAX_MESSAGE_CHARS = 300
        const val MAX_FIELD_CHARS = 200
        const val MAX_FIELDS = 12
        const val MAX_STACK_CHARS = 4_000

        /** The exception chain, at most [MAX_STACK_CHARS]; messages are redacted. */
        fun stackOf(throwable: Throwable): String =
            DiagnosticRedactor.redact(throwable.stackTraceToString()).text.take(MAX_STACK_CHARS)
    }
}

/** The app-wide event buffer. Recording is cheap and never throws. */
object DiagnosticEvents {
    val log = DiagnosticEventLog()

    fun record(level: DiagnosticLevel, subsystem: DiagnosticSubsystem, message: String, fields: Map<String, String> = emptyMap()) =
        safely { log.record(subsystem, level, message, fields) }

    fun info(subsystem: DiagnosticSubsystem, message: String, fields: Map<String, String> = emptyMap()) =
        safely { log.record(subsystem, DiagnosticLevel.INFO, message, fields) }

    fun warn(subsystem: DiagnosticSubsystem, message: String, fields: Map<String, String> = emptyMap(), throwable: Throwable? = null) =
        safely { log.record(subsystem, DiagnosticLevel.WARN, message, fields, throwable) }

    fun error(subsystem: DiagnosticSubsystem, message: String, fields: Map<String, String> = emptyMap(), throwable: Throwable? = null) =
        safely { log.record(subsystem, DiagnosticLevel.ERROR, message, fields, throwable) }

    /**
     * Records a warning or error logged through PlatformLogger (shared code), in the subsystem
     * its tag suggests. The message is redacted when a report is built.
     */
    fun fromLog(tag: String, level: DiagnosticLevel, message: String, throwable: Throwable?) =
        safely { log.record(subsystemForTag(tag), level, "$tag: $message", emptyMap(), throwable) }

    fun subsystemForTag(tag: String): DiagnosticSubsystem {
        val t = tag.lowercase()
        return TAG_RULES.firstOrNull { (keys, _) -> keys.any { it in t } }?.second ?: DiagnosticSubsystem.APP
    }

    private val TAG_RULES = listOf(
        listOf("dat:", "meta") to DiagnosticSubsystem.META_DAT,
        listOf("mentra", "miniapp") to DiagnosticSubsystem.MENTRA,
        listOf("live") to DiagnosticSubsystem.LIVE,
        listOf("sync", "p2p", "download", "wifi", "media") to DiagnosticSubsystem.SYNC,
        listOf("question", "aihijack", "spoken", "assistant", "route", "decision") to DiagnosticSubsystem.QUESTION,
        listOf("model", "litert", "llama", "inference", "localchat") to DiagnosticSubsystem.MODEL,
        listOf("ble", "bluetooth", "gatt", "eyevue", "myvu", "tunebuds", "memomind", "glasses", "pair") to DiagnosticSubsystem.BLE,
        listOf("relay", "prosub", "proaccount", "proquestion", "billing") to DiagnosticSubsystem.PRO,
        listOf("audio", "mic", "sco", "tts", "speech") to DiagnosticSubsystem.AUDIO,
    )

    private inline fun safely(block: () -> Unit) {
        try {
            block()
        } catch (_: Throwable) {
            // Diagnostics must never break the feature that records them.
        }
    }
}
