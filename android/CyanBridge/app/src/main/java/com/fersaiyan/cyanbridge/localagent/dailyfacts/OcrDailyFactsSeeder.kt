package com.fersaiyan.cyanbridge.localagent.dailyfacts

import android.content.Context
import com.fersaiyan.cyanbridge.localagent.memory.LocalAgentMemoryStore
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.LinkedHashSet
import java.util.Locale

object OcrDailyFactsSeeder {
    data class SeedResult(
        val addedCount: Int,
        val scannedCaptures: Int,
    )

    private const val MAX_FACTS_TO_ADD = 240
    private const val MAX_LINES_PER_DAY = 1200

    fun seedDraftFactsFromScreenCaptures(
        context: Context,
        targetDate: String,
        lookbackDays: Int,
    ): SeedResult {
        val normalizedLookback = lookbackDays.coerceIn(1, 365)
        val endCal = parseDateToCalendar(targetDate) ?: Calendar.getInstance()

        val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val timeFmt = SimpleDateFormat("HH:mm", Locale.US)

        val dedupe = LinkedHashSet<String>()
        val facts = ArrayList<String>()
        var scanned = 0

        for (offset in 0 until normalizedLookback) {
            if (facts.size >= MAX_FACTS_TO_ADD) break
            val dayCal = (endCal.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -offset) }
            val day = dateFmt.format(dayCal.time)
            val lines = LocalAgentMemoryStore.readScreenCaptureLines(context, day, maxLines = MAX_LINES_PER_DAY)
            if (lines.isEmpty()) continue

            for (line in lines) {
                if (facts.size >= MAX_FACTS_TO_ADD) break
                val obj = runCatching { JSONObject(line) }.getOrNull() ?: continue
                scanned += 1

                val ts = obj.optLong("ts_ms", 0L)
                val pkg = obj.optString("package", "?").trim().ifBlank { "?" }
                val raw = obj.optString("text", "").trim()
                if (raw.isBlank()) continue

                val clean = raw
                    .replace(Regex("\\s+"), " ")
                    .replace(Regex("[\\u0000-\\u001F]"), " ")
                    .trim()
                if (clean.length < 18) continue

                val appLabel = pkg.substringAfterLast('.')
                    .replace('_', ' ')
                    .ifBlank { pkg }
                val snippet = clean.take(170)
                val time = if (ts > 0L) timeFmt.format(Date(ts)) else "??:??"
                val fact = "[$day $time] In $appLabel, screen showed: $snippet"

                val key = fact
                    .lowercase(Locale.US)
                    .replace(Regex("[^a-z0-9\\s]"), " ")
                    .replace(Regex("\\s+"), " ")
                    .trim()
                    .take(260)
                if (key.length < 12) continue
                if (!dedupe.add(key)) continue

                facts += fact
            }
        }

        if (facts.isNotEmpty()) {
            DailyFactsStorage.appendDraft(context, targetDate, facts)
        }

        return SeedResult(
            addedCount = facts.size,
            scannedCaptures = scanned,
        )
    }

    private fun parseDateToCalendar(date: String): Calendar? {
        val parsed = runCatching {
            SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(date)
        }.getOrNull() ?: return null
        return Calendar.getInstance().apply {
            time = parsed
            set(Calendar.HOUR_OF_DAY, 12)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
    }
}
