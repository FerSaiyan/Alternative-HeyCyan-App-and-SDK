package com.fersaiyan.cyanbridge.diagnostics

import android.content.Context
import android.os.Process
import com.fersaiyan.cyanbridge.shared.diagnostics.DiagnosticEvent
import com.fersaiyan.cyanbridge.shared.diagnostics.DiagnosticEventLog
import com.fersaiyan.cyanbridge.shared.diagnostics.DiagnosticEvents
import com.fersaiyan.cyanbridge.shared.diagnostics.DiagnosticLevel
import com.fersaiyan.cyanbridge.shared.diagnostics.DiagnosticSubsystem
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Keeps the Java stack and the recent events of an uncaught crash, so the next support report can
 * say what happened (ApplicationExitInfo has no Java stack). The previous default handler (the
 * platform's, or an SDK's) still runs afterwards, so crash behaviour is unchanged. The single file
 * in no_backup/ is replaced by the next crash; nothing reads it at startup.
 */
object CrashRecorder {
    private const val FILE = "diagnostics/last_crash.json"
    private const val MAX_EVENTS = 120

    @Volatile private var installed = false

    fun install(context: Context) {
        if (installed) return
        installed = true
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { save(appContext, thread, error) }
            if (previous != null) previous.uncaughtException(thread, error) else Process.killProcess(Process.myPid())
        }
    }

    private fun save(context: Context, thread: Thread, error: Throwable) {
        DiagnosticEvents.error(DiagnosticSubsystem.APP, "uncaught exception", mapOf("thread" to thread.name), error)
        val json = JSONObject()
            .put("t", System.currentTimeMillis())
            .put("pid", Process.myPid())
            .put("thread", thread.name)
            .put("stack", DiagnosticEventLog.stackOf(error))
            .put("events", encode(DiagnosticEvents.log.snapshot().takeLast(MAX_EVENTS)))
        val file = File(context.noBackupFilesDir, FILE)
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(json.toString())
        tmp.renameTo(file)
    }

    fun readSaved(context: Context): SavedCrash? = runCatching {
        val file = File(context.noBackupFilesDir, FILE)
        if (!file.isFile) return null
        val json = JSONObject(file.readText())
        SavedCrash(
            timeMs = json.getLong("t"),
            pid = json.optInt("pid"),
            stack = json.optString("stack"),
            events = decode(json.optJSONArray("events") ?: JSONArray()),
        )
    }.getOrNull()

    internal fun encode(events: List<DiagnosticEvent>): JSONArray = JSONArray().apply {
        events.forEach { e ->
            put(
                JSONObject()
                    .put("seq", e.seq).put("t", e.timeMs).put("s", e.subsystem.name).put("l", e.level.name)
                    .put("m", e.message).put("f", JSONObject(e.fields)).put("x", e.stack ?: JSONObject.NULL)
                    .put("r", e.repeats).put("lt", e.lastTimeMs),
            )
        }
    }

    internal fun decode(array: JSONArray): List<DiagnosticEvent> = (0 until array.length()).mapNotNull { i ->
        runCatching {
            val o = array.getJSONObject(i)
            val fields = o.optJSONObject("f")?.let { f -> f.keys().asSequence().associateWith { f.optString(it) } }.orEmpty()
            DiagnosticEvent(
                seq = o.optLong("seq"),
                timeMs = o.getLong("t"),
                subsystem = DiagnosticSubsystem.valueOf(o.getString("s")),
                level = DiagnosticLevel.valueOf(o.getString("l")),
                message = o.optString("m"),
                fields = fields,
                stack = if (o.isNull("x")) null else o.optString("x"),
                repeats = o.optInt("r"),
                lastTimeMs = o.optLong("lt", o.getLong("t")),
            )
        }.getOrNull()
    }
}

/**
 * The glasses link as the UI last reported it (every family goes through
 * DeviceConnectionCoordinator.updateConnectionStatus). Records a BLE event on each change.
 */
object GlassesConnectionDiagnostics {
    @Volatile private var connected: Boolean? = null
    @Volatile private var family: String = "unknown"
    @Volatile private var sinceMs: Long = 0L

    fun onStatus(family: String, connected: Boolean) {
        if (this.connected == connected && this.family == family) return
        val previous = this.connected
        this.connected = connected
        this.family = family
        sinceMs = System.currentTimeMillis()
        DiagnosticEvents.info(
            DiagnosticSubsystem.BLE,
            if (connected) "glasses connected" else "glasses disconnected",
            buildMap {
                put("family", family)
                if (previous != null) put("was", if (previous) "connected" else "disconnected")
            },
        )
    }

    fun describe(@Suppress("UNUSED_PARAMETER") context: Context): String {
        val state = connected ?: return "unknown (no status reported in this process)"
        val seconds = (System.currentTimeMillis() - sinceMs) / 1000
        return "${if (state) "connected" else "disconnected"} ($family, for ${seconds}s)"
    }
}
