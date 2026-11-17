package com.fersaiyan.cyanbridge.diagnostics

import android.Manifest
import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.os.StatFs
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.fersaiyan.cyanbridge.BuildConfig
import com.fersaiyan.cyanbridge.agent.LocalAgentPrefs
import com.fersaiyan.cyanbridge.agent.ProSubscriptionPrefs
import com.fersaiyan.cyanbridge.agent.ProSubscriptionServerPrefs
import com.fersaiyan.cyanbridge.ai.router.AiProviderPrefs
import com.fersaiyan.cyanbridge.ai.router.QuestionRoutingPrefs
import com.fersaiyan.cyanbridge.analytics.AnalyticsPreferences
import com.fersaiyan.cyanbridge.devices.DeviceProfileStore
import com.fersaiyan.cyanbridge.localmodels.remote.RemoteOpenAiPrefs
import com.fersaiyan.cyanbridge.localmodels.session.LocalChatSessionManager
import com.fersaiyan.cyanbridge.localmodels.settings.LocalModelSettingsRepository
import com.fersaiyan.cyanbridge.localmodels.storage.LocalModelStorageRepository
import com.fersaiyan.cyanbridge.shared.diagnostics.DiagnosticEvent
import com.fersaiyan.cyanbridge.shared.diagnostics.DiagnosticLastExit
import com.fersaiyan.cyanbridge.shared.format.formatLocalDateTime
import com.fersaiyan.cyanbridge.shared.prefs.AppPrefs
import com.fersaiyan.cyanbridge.shared.prefs.AssistantPrefs
import com.fersaiyan.cyanbridge.shared.prefs.ProPrefs
import com.fersaiyan.cyanbridge.shared.prefs.get
import com.fersaiyan.cyanbridge.ui.MyApplication
import com.fersaiyan.cyanbridge.ui.localization.AppLanguagePreferences
import java.io.File
import java.net.URI
import java.util.Locale
import java.util.TimeZone

/**
 * Android side of support reports: the context header, the previous process's exit (crash, ANR,
 * low memory…) and this process's logcat. Everything here runs only when a report is built,
 * never at startup. Values go through the shared redactor before they leave the phone.
 *
 * Deliberately left out: the vision-assistance answer and screen-reader state (they reveal a
 * disability), account emails, tokens, and anything the user said, wrote or photographed.
 */
object DiagnosticsCollector {
    /** Lines read from logcat before the per-tag caps. */
    private const val LOGCAT_READ_LINES = 6_000
    /** Lines kept after the caps (newest). */
    const val LOGCAT_KEEP_LINES = 1_500
    /** At most this many lines per tag (all `DAT:*` tags count as one), so one noisy SDK cannot crowd out the rest. */
    const val LOGCAT_PER_TAG_CAP = 250
    private const val PREVIOUS_PROCESS_LINES = 400

    data class Logcat(val text: String, val readLines: Int, val keptLines: Int, val topTags: List<Pair<String, Int>>)

    fun header(context: Context, logcat: Logcat?): LinkedHashMap<String, Map<String, String>> {
        val header = LinkedHashMap<String, Map<String, String>>()
        header["app"] = section { app(context) }
        header["device"] = section { device() }
        header["glasses"] = section { glasses(context) }
        header["ai"] = section { ai(context) }
        header["settings"] = section { settings(context) }
        header["pro"] = section { pro(context) }
        header["resources"] = section { resources(context) }
        header["permissions"] = section { permissions(context) }
        if (logcat != null) {
            header["logcat"] = linkedMapOf(
                "lines_read" to logcat.readLines.toString(),
                "lines_kept" to logcat.keptLines.toString(),
                "top_tags" to logcat.topTags.joinToString(", ") { "${it.first}=${it.second}" },
            )
        }
        return header
    }

    /** Runs [fill], recording a failure instead of losing the whole section. */
    private fun section(fill: LinkedHashMap<String, String>.() -> Unit): Map<String, String> {
        val values = LinkedHashMap<String, String>()
        runCatching { values.fill() }.onFailure { values["error"] = "${it.javaClass.simpleName}: ${it.message}" }
        return values
    }

    private fun LinkedHashMap<String, String>.app(context: Context) {
        val pm = context.packageManager
        val info = pm.getPackageInfo(context.packageName, 0)
        put("version", "${info.versionName} (${info.longVersionCode}, ${BuildConfig.BUILD_TYPE})")
        put("package", context.packageName)
        put("installer", runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                pm.getInstallSourceInfo(context.packageName).installingPackageName
            } else {
                @Suppress("DEPRECATION") pm.getInstallerPackageName(context.packageName)
            }
        }.getOrNull() ?: "unknown (sideload or adb)")
        put("installed", formatLocalDateTime(info.firstInstallTime))
        put("updated", formatLocalDateTime(info.lastUpdateTime))
        put("process_uptime_s", ((SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()) / 1000).toString())
        put("meta_dat_sdk", if (BuildConfig.META_DAT_DEVELOPER_CONFIGURATION) "developer configuration" else "registered app id")
        put("app_language", AppLanguagePreferences.selected(context).name)
        put("locale", Locale.getDefault().toLanguageTag())
        put("utc_offset_min", (TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60_000).toString())
    }

    private fun LinkedHashMap<String, String>.device() {
        put("model", "${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
        put("android", "${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}, patch ${Build.VERSION.SECURITY_PATCH})")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) put("soc", "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}")
        put("hardware", Build.HARDWARE)
        put("abis", Build.SUPPORTED_ABIS.joinToString(","))
        put("emulator", (Build.FINGERPRINT.contains("generic") || Build.HARDWARE.contains("ranchu") || Build.MODEL.contains("sdk_gphone")).toString())
    }

    private fun LinkedHashMap<String, String>.glasses(context: Context) {
        val profile = DeviceProfileStore.loadLastSelected(context)
        put("family", profile?.selectedClass?.name ?: "none selected")
        profile?.let {
            put("detected_class", it.detectedClass.name)
            put("user_overridden", it.userOverridden.toString())
            it.advertisedName?.let { name -> put("ble_name", name) }
            put("address", it.macAddress)
        }
        val app = context.applicationContext as? MyApplication
        app?.hardwareVersion?.takeIf { it.isNotBlank() }?.let { put("hardware_version", it) }
        app?.firmwareVersion?.takeIf { it.isNotBlank() }?.let { put("firmware_version", it) }
        put("connection", GlassesConnectionDiagnostics.describe(context))
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        put("bluetooth_on", (adapter?.isEnabled == true).toString())
    }

    private fun LinkedHashMap<String, String>.ai(context: Context) {
        put("provider", LocalAgentPrefs.getProviderType(context).name)
        put("relay_host", hostOf(AiProviderPrefs.getRelayBaseUrl(context)))
        val remote = RemoteOpenAiPrefs.isActive(context)
        put("local_route", if (remote) "remote OpenAI-compatible server" else "on-device")
        if (remote) {
            put("remote_model", RemoteOpenAiPrefs.getModel(context))
            put("remote_host", hostOf(RemoteOpenAiPrefs.getBaseUrl(context)))
        }
        val model = runCatching { LocalModelStorageRepository.resolveSelectedModel(context) }.getOrNull()
        put("local_model", model?.displayName ?: "none")
        if (model != null) {
            runCatching { LocalModelSettingsRepository.getForModel(context, model.id) }.getOrNull()?.let { s ->
                put("local_runtime", s.modelRuntime.name)
                put("local_backend_selected", s.computeBackend.name)
                put("local_threads_ctx", "${s.cpuThreads} threads, ctx ${s.contextSize}, max ${s.maxTokens}")
            }
        }
        put("local_model_loaded", LocalChatSessionManager.diagnosticsSummary())
        put("pro_questions_model", ProPrefs.Ai.questionsModel[context] ?: "default")
        put("pro_requests_model", ProPrefs.Ai.requestsModel[context])
        put("live_route", if (AssistantPrefs.GeminiLive.proEconomy[context]) "economy" else "private")
    }

    private fun LinkedHashMap<String, String>.settings(context: Context) {
        put("question_routing", QuestionRoutingPrefs.mode(context).name)
        put("web_search_engine", QuestionRoutingPrefs.webSearchEngine(context).name)
        put("media_sync_flow", AppPrefs.Main.mediaSyncFlow[context]?.name ?: "ask every time")
        put("local_agent_automation", LocalAgentPrefs.isLocalAgentAutomationEnabled(context).toString())
        put("analytics_sharing", AnalyticsPreferences.isSharingEnabled(context).toString())
    }

    private fun LinkedHashMap<String, String>.pro(context: Context) {
        put("plan", ProSubscriptionPrefs.getPlan(context))
        put("active", ProSubscriptionPrefs.isActiveLocally(context).toString())
        put("billing_provider", ProSubscriptionPrefs.getProvider(context))
        ProSubscriptionPrefs.getExpiresAt(context).takeIf { it > 0 }?.let { put("expires", formatLocalDateTime(it)) }
        ProSubscriptionPrefs.getLastVerifiedAt(context).takeIf { it > 0 }?.let { put("last_verified", formatLocalDateTime(it)) }
        put("account_linked", ProSubscriptionServerPrefs.getApiToken(context).isNotBlank().toString())
    }

    private fun LinkedHashMap<String, String>.resources(context: Context) {
        val stat = StatFs(context.filesDir.absolutePath)
        put("storage_free_mb", (stat.availableBytes / MB).toString())
        put("storage_total_mb", (stat.totalBytes / MB).toString())
        val am = context.getSystemService(ActivityManager::class.java)
        val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        put("ram_total_mb", (mem.totalMem / MB).toString())
        put("ram_available_mb", (mem.availMem / MB).toString())
        put("ram_low", mem.lowMemory.toString())
        put("low_ram_device", am.isLowRamDevice.toString())
        val runtime = Runtime.getRuntime()
        put("app_heap_mb", "${(runtime.totalMemory() - runtime.freeMemory()) / MB} used of ${runtime.maxMemory() / MB}")
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        battery?.let {
            val level = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = it.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
            put("battery_pct", (level * 100 / scale).toString())
            put("charging", (it.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0).toString())
        }
        val power = context.getSystemService(PowerManager::class.java)
        put("power_save", power.isPowerSaveMode.toString())
        put("battery_optimization_ignored", power.isIgnoringBatteryOptimizations(context.packageName).toString())
        put("thermal_status", power.currentThermalStatus.toString())
    }

    private fun LinkedHashMap<String, String>.permissions(context: Context) {
        val names = buildList {
            add("mic" to Manifest.permission.RECORD_AUDIO)
            add("camera" to Manifest.permission.CAMERA)
            add("location" to Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add("bt_connect" to Manifest.permission.BLUETOOTH_CONNECT)
                add("bt_scan" to Manifest.permission.BLUETOOTH_SCAN)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add("notifications" to Manifest.permission.POST_NOTIFICATIONS)
                add("nearby_wifi" to Manifest.permission.NEARBY_WIFI_DEVICES)
            }
        }
        names.forEach { (label, permission) ->
            put(label, (ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED).toString())
        }
    }

    private fun hostOf(url: String): String = runCatching { URI(url.trim()).host }.getOrNull() ?: "unset"

    /**
     * The most recent exit of this app's main process before the current one, from
     * ApplicationExitInfo (API 30+), joined with the crash handler's saved stack and events when
     * they belong to it. Null when the previous process ended normally (or nothing is known).
     */
    fun lastExit(context: Context): DiagnosticLastExit? {
        val saved = CrashRecorder.readSaved(context)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return saved?.toLastExit()
        val am = context.getSystemService(ActivityManager::class.java) ?: return saved?.toLastExit()
        val exits = runCatching { am.getHistoricalProcessExitReasons(context.packageName, 0, 8) }.getOrDefault(emptyList())
            .filter { it.pid != Process.myPid() && it.processName == context.packageName }
        val exit = exits.firstOrNull() ?: return saved?.toLastExit()
        if (exit.reason !in INTERESTING_REASONS) {
            // A normal exit after the saved crash: that crash is old news.
            return null
        }
        val matchingSaved = saved?.takeIf { kotlin.math.abs(it.timeMs - exit.timestamp) < 30_000 }
        val trace = if (exit.reason == ApplicationExitInfo.REASON_ANR || exit.reason == ApplicationExitInfo.REASON_CRASH_NATIVE) {
            runCatching { exit.traceInputStream?.bufferedReader()?.use { readTrace(it.readText()) } }.getOrNull()
        } else {
            null
        }
        return DiagnosticLastExit(
            pid = exit.pid,
            reason = reasonName(exit.reason),
            timeMs = exit.timestamp,
            description = exit.description,
            importance = exit.importance,
            pssKb = exit.pss,
            rssKb = exit.rss,
            stack = matchingSaved?.stack,
            trace = trace,
            logcatTail = readLogcat(exit.pid, PREVIOUS_PROCESS_LINES, perTagCap = 120, keepLines = PREVIOUS_PROCESS_LINES)
                ?.text?.takeIf { it.isNotBlank() },
            events = matchingSaved?.events.orEmpty(),
        )
    }

    private val INTERESTING_REASONS = setOf(
        ApplicationExitInfo.REASON_CRASH,
        ApplicationExitInfo.REASON_CRASH_NATIVE,
        ApplicationExitInfo.REASON_ANR,
        ApplicationExitInfo.REASON_LOW_MEMORY,
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE,
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE,
        ApplicationExitInfo.REASON_SIGNALED,
    )

    private fun reasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_CRASH -> "CRASH"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        else -> "REASON_$reason"
    }

    /** The ANR trace's "main" thread first, then the rest, capped. */
    internal fun readTrace(text: String): String {
        val blocks = text.split("\n\n")
        val main = blocks.firstOrNull { it.trimStart().startsWith("\"main\"") }
        return (listOfNotNull(main) + blocks.filter { it !== main }).joinToString("\n\n").take(16_000)
    }

    /** This process's logcat, PID-filtered, with per-tag caps. */
    fun currentLogcat(): Logcat = readLogcat(Process.myPid(), LOGCAT_READ_LINES, LOGCAT_PER_TAG_CAP, LOGCAT_KEEP_LINES)
        ?: Logcat("Failed to read logcat for this process.", 0, 0, emptyList())

    private fun readLogcat(pid: Int, readLines: Int, perTagCap: Int, keepLines: Int): Logcat? {
        val raw = runLogcat(listOf("logcat", "-d", "-b", "main,system,crash", "-v", "threadtime", "--pid=$pid", "-t", readLines.toString()))
            ?: runLogcat(listOf("logcat", "-d", "-v", "threadtime", "--pid=$pid", "-t", readLines.toString()))
            ?: return null
        return capLogcat(raw.lines().filter { it.isNotBlank() && !it.startsWith("--------- beginning of") }, perTagCap, keepLines)
    }

    private fun runLogcat(command: List<String>): String? = runCatching {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        if (process.waitFor() == 0) output else null
    }.getOrNull()

    /** Keeps the newest [perTagCap] lines of each tag group, then the newest [keepLines] overall. */
    internal fun capLogcat(lines: List<String>, perTagCap: Int, keepLines: Int): Logcat {
        val groups = lines.map(::tagGroup)
        val counts = groups.groupingBy { it }.eachCount()
        val seen = HashMap<String, Int>()
        val keptReversed = ArrayList<String>()
        for (i in lines.indices.reversed()) {
            val group = groups[i]
            val n = seen.getOrDefault(group, 0)
            if (n >= perTagCap) continue
            seen[group] = n + 1
            keptReversed.add(lines[i])
            if (keptReversed.size >= keepLines) break
        }
        val kept = keptReversed.asReversed()
        val top = counts.entries.sortedByDescending { it.value }.take(6).map { it.key to it.value }
        return Logcat(kept.joinToString("\n"), lines.size, kept.size, top)
    }

    /** The tag of a `threadtime` line; every Meta DAT tag (`DAT:CORE:…`) is one group. */
    internal fun tagGroup(line: String): String {
        val match = THREADTIME.find(line) ?: return "?"
        val tag = match.groupValues[1].trim()
        return if (tag.startsWith("DAT:")) "DAT" else tag
    }

    private val THREADTIME = Regex("""^\S+\s+\S+\s+\d+\s+\d+\s+[VDIWEF]\s+(.*?)\s*: """)
    private const val MB = 1024L * 1024L
}

/** A crash saved by [CrashRecorder]. */
data class SavedCrash(val timeMs: Long, val pid: Int, val stack: String, val events: List<DiagnosticEvent>) {
    fun toLastExit(): DiagnosticLastExit =
        DiagnosticLastExit(pid = pid, reason = "CRASH", timeMs = timeMs, stack = stack, events = events)
}
