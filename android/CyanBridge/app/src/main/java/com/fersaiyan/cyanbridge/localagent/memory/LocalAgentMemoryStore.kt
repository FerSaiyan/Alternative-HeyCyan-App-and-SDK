package com.fersaiyan.cyanbridge.localagent.memory

import android.content.Context
import android.util.Log
import com.fersaiyan.cyanbridge.memoryvault.MemoryModeManager
import com.fersaiyan.cyanbridge.memoryvault.MemoryPolicyService
import com.fersaiyan.cyanbridge.memoryvault.MemoryRefMapper
import com.fersaiyan.cyanbridge.shared.settings.MemorySourceType
import com.fersaiyan.cyanbridge.memoryvault.MemoryVaultBootstrap
import com.fersaiyan.cyanbridge.memoryvault.MemoryVaultService
import com.fersaiyan.cyanbridge.memoryvault.VaultLockStateManager
import com.fersaiyan.cyanbridge.ui.MyApplication
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Local Agent memory store.
 *
 * Public API remains file-oriented for compatibility, while payloads are now
 * protected by the memory vault when possible.
 */
object LocalAgentMemoryStore {
    private const val TAG = "LocalAgentMemory"

    private fun appContextOrNull(): Context? = runCatching { MyApplication.CONTEXT }.getOrNull()

    private fun baseDir(context: Context): File {
        val dir = File(context.filesDir, "local_agent_memory")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun screenDir(context: Context): File {
        val dir = File(baseDir(context), "screen_captures")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun dailySummariesDir(context: Context): File {
        val dir = File(baseDir(context), "daily_summaries")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun dailyBulletsDir(context: Context): File {
        val dir = File(baseDir(context), "daily_bullets")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun userFactsCandidatesDir(context: Context): File {
        val dir = File(baseDir(context), "user_facts_candidates")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun userFactsFile(context: Context): File = File(baseDir(context), "USER_FACTS.md")

    fun agentPersonaFile(context: Context): File = File(baseDir(context), "AGENT_PERSONA.md")

    fun userFactsCandidatesFileForDate(context: Context, date: String): File {
        return File(userFactsCandidatesDir(context), "${date.trim()}.md")
    }

    fun userFactsCandidatesFileForToday(context: Context): File {
        return userFactsCandidatesFileForDate(context, dayString(System.currentTimeMillis()))
    }

    fun dailyFactsIndexFile(context: Context): File = File(baseDir(context), "DAILY_FACTS.md")

    private fun dailyFactsDir(context: Context): File {
        val dir = File(baseDir(context), "daily_facts")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun confirmedDailyFactsDir(context: Context): File {
        val dir = File(baseDir(context), "daily_facts_confirmed")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun dailyFactsFileForDate(context: Context, date: String): File {
        return File(dailyFactsDir(context), "${date.trim()}.md")
    }

    fun confirmedDailyFactsFileForDate(context: Context, date: String): File {
        return File(confirmedDailyFactsDir(context), "${date.trim()}.md")
    }

    fun confirmedDailyFactsFileForToday(context: Context): File {
        return confirmedDailyFactsFileForDate(context, dayString(System.currentTimeMillis()))
    }

    fun dailySummaryFileForDate(context: Context, date: String): File {
        return File(dailySummariesDir(context), "${date.trim()}.md")
    }

    fun dailyBulletsFileForDate(context: Context, date: String): File {
        return File(dailyBulletsDir(context), "${date.trim()}.jsonl")
    }

    fun dailySummaryFileForDay(context: Context, tsMs: Long): File {
        return File(dailySummariesDir(context), "${dayString(tsMs)}.md")
    }

    fun dailySummaryFileForToday(context: Context): File =
        dailySummaryFileForDay(context, System.currentTimeMillis())

    fun listDailySummaryDatesDesc(context: Context, limit: Int = 30): List<String> {
        val files = dailySummariesDir(context).listFiles().orEmpty()
        if (files.isEmpty()) return emptyList()

        return files.asSequence()
            .map { it.nameWithoutExtension.trim() }
            .filter { it.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) }
            .distinct()
            .sortedDescending()
            .take(limit.coerceIn(1, 365))
            .toList()
    }

    fun dailyFactsFileForDay(context: Context, tsMs: Long): File {
        return File(dailyFactsDir(context), "${dayString(tsMs)}.md")
    }

    fun dailyFactsFileForToday(context: Context): File =
        dailyFactsFileForDay(context, System.currentTimeMillis())

    fun ensureSeedFiles(context: Context) {
        runCatching {
            MemoryVaultBootstrap.ensureInitialized(context)

            seedIfMissing(
                file = userFactsFile(context),
                defaultText = "# User Facts\n\n- Name: \n- Preferences: \n\n",
            )
            seedIfMissing(
                file = agentPersonaFile(context),
                defaultText = "# Agent Persona\n\nPrecision with a pulse.\n\n",
            )
            seedIfMissing(
                file = dailyFactsIndexFile(context),
                defaultText = "# Daily Facts\n\nThis folder stores day-by-day facts you want to confirm.\n\nSee: daily_facts/YYYY-MM-DD.md\n",
            )

            val todayDate = dayString(System.currentTimeMillis())
            seedIfMissing(
                file = dailyFactsFileForDate(context, todayDate),
                defaultText = "# Daily facts (${todayDate})\n\n- \n",
            )
            seedIfMissing(
                file = confirmedDailyFactsFileForDate(context, todayDate),
                defaultText = "# Confirmed daily facts (${todayDate})\n\n- \n",
            )
            seedIfMissing(
                file = dailySummaryFileForDate(context, todayDate),
                defaultText = "# Daily Summary (${todayDate})\n\n(Generate from Settings -> View today's daily summary -> Regenerate)\n",
            )
            seedIfMissing(
                file = userFactsCandidatesFileForDate(context, todayDate),
                defaultText = "# Candidate user facts (${todayDate})\n\n- \n",
            )
        }.onFailure {
            Log.w(TAG, "Failed to seed memory files: ${it.message}")
        }
    }

    fun readText(file: File): String {
        val context = appContextOrNull()
        if (context != null) {
            if (VaultLockStateManager.isLocked(context)) return ""
            val ref = memoryRefForFile(context, file)
            val fromVault = MemoryVaultService.getTextBlocking(context, ref)
            if (fromVault != null) return fromVault
        }
        return runCatching { file.readText(Charsets.UTF_8) }.getOrDefault("")
    }

    fun writeText(file: File, text: String) {
        file.parentFile?.mkdirs()

        val context = appContextOrNull()
        if (context == null) {
            file.writeText(text, Charsets.UTF_8)
            return
        }

        if (VaultLockStateManager.isLocked(context)) {
            Log.w(TAG, "Vault locked; write skipped for ${file.name}")
            return
        }

        val ref = memoryRefForFile(context, file)
        val policy = MemoryPolicyService.classifyForMemoryRef(
            context = context,
            memoryRef = ref,
            text = text,
            derivedFromIds = defaultDerivedRefsFor(context, file),
            provenance = "local_agent_memory_store",
        )

        val stored = MemoryVaultService.putTextBlocking(context, ref, text, policy)
        if (!stored) {
            file.writeText(text, Charsets.UTF_8)
            return
        }

        if (shouldMirrorPlaintext(policy.sourceType)) {
            file.writeText(text, Charsets.UTF_8)
        } else {
            if (!file.exists()) file.createNewFile()
            file.writeText("", Charsets.UTF_8)
        }
    }

    private fun dayString(tsMs: Long): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        return fmt.format(Date(tsMs))
    }

    fun screenCaptureFileForDate(context: Context, date: String): File {
        return File(screenDir(context), "${date.trim()}.jsonl")
    }

    fun screenCaptureFileForDay(context: Context, tsMs: Long): File {
        return File(screenDir(context), "${dayString(tsMs)}.jsonl")
    }

    fun screenCaptureFileForToday(context: Context): File =
        screenCaptureFileForDay(context, System.currentTimeMillis())

    fun screenCapturesFileForDate(context: Context, date: String): File = screenCaptureFileForDate(context, date)

    fun screenCapturesFileForToday(context: Context): File = screenCaptureFileForToday(context)

    fun appendScreenCapture(
        context: Context,
        packageName: String,
        text: String,
        tsMs: Long = System.currentTimeMillis(),
        maxChars: Int = 25_000,
    ) {
        ensureSeedFiles(context)
        if (!MemoryModeManager.isScreenOcrCaptureEnabled(context)) return
        if (VaultLockStateManager.isLocked(context)) return

        val trimmed = text.trim().take(maxChars)
        if (trimmed.isBlank()) return

        val obj = JSONObject().apply {
            put("ts_ms", tsMs)
            put("package", packageName)
            put("text", trimmed)
        }
        val line = obj.toString()

        val date = dayString(tsMs)
        val file = screenCaptureFileForDate(context, date)
        val ref = memoryRefForFile(context, file)
        val existing = MemoryVaultService.getTextBlocking(context, ref).orEmpty().trimEnd()
        val next = if (existing.isBlank()) "$line\n" else "$existing\n$line\n"

        val policy = MemoryPolicyService.classifyForMemoryRef(
            context = context,
            memoryRef = ref,
            text = next,
            sourceTimestampMs = tsMs,
            provenance = "periodic_screen_capture",
        )
        MemoryVaultService.putTextBlocking(context, ref, next, policy)

        MemoryVaultService.enforceScreenOcrRetentionBlocking(context)
    }

    fun readScreenCaptureLines(context: Context, date: String, maxLines: Int = 200): List<String> {
        if (VaultLockStateManager.isLocked(context)) return emptyList()
        val ref = memoryRefForFile(context, screenCaptureFileForDate(context, date))
        val text = MemoryVaultService.getTextBlocking(context, ref)
            ?: runCatching { screenCaptureFileForDate(context, date).readText(Charsets.UTF_8) }.getOrDefault("")
        if (text.isBlank()) return emptyList()
        return text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toList()
            .takeLast(maxLines)
    }

    fun hasScreenCapturesForDate(context: Context, date: String): Boolean {
        return readScreenCaptureLines(context, date, maxLines = 1).isNotEmpty()
    }

    fun screenCaptureLastUpdatedAtMs(context: Context, date: String): Long {
        val ref = memoryRefForFile(context, screenCaptureFileForDate(context, date))
        return MemoryVaultService.getUpdatedAtBlocking(ref) ?: 0L
    }

    fun memoryRefForFile(context: Context, file: File): String = MemoryRefMapper.forFile(context, file)

    fun deleteAllPassiveCapture(context: Context) {
        MemoryVaultService.deleteAllScreenOcrArtifactsBlocking()
        val dir = File(baseDir(context), "screen_captures")
        runCatching { dir.deleteRecursively() }
        dir.mkdirs()
    }

    fun resetVault(context: Context) {
        MemoryVaultService.resetAllVaultTablesBlocking()
        VaultLockStateManager.clearAll(context)
    }

    private fun seedIfMissing(file: File, defaultText: String) {
        val current = readText(file)
        if (current.isBlank()) {
            writeText(file, defaultText)
        }
    }

    private fun shouldMirrorPlaintext(sourceType: MemorySourceType): Boolean {
        return when (sourceType) {
            MemorySourceType.SCREEN_OCR,
            MemorySourceType.DERIVED_SUMMARY,
            -> false

            else -> true
        }
    }

    private fun defaultDerivedRefsFor(context: Context, file: File): List<String> {
        val rel = runCatching {
            file.relativeTo(File(context.filesDir, "local_agent_memory")).invariantSeparatorsPath
        }.getOrDefault("")
        if (!rel.startsWith("daily_summaries/")) return emptyList()

        val date = rel.substringAfterLast('/').removeSuffix(".md")
        val refs = mutableListOf<String>()
        refs += memoryRefForFile(context, confirmedDailyFactsFileForDate(context, date))
        refs += memoryRefForFile(context, screenCaptureFileForDate(context, date))
        return refs
    }
}
