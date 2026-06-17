package com.fersaiyan.cyanbridge.localagent.dailysummary

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToLong

object DailySummaryBulletRunHistory {
    private data class Run(
        val totalBullets: Int,
        val totalMs: Long,
        val createdAtMs: Long,
    )

    private const val PREFS_NAME = "daily_summary_bullet_run_history"
    private const val KEY_RUNS = "runs_json"
    private const val MAX_RUNS = 20

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun record(
        context: Context,
        totalBullets: Int,
        totalMs: Long,
    ) {
        if (totalBullets <= 0 || totalMs <= 0L) return
        val current = loadRuns(context).toMutableList()
        current += Run(
            totalBullets = totalBullets.coerceAtLeast(1),
            totalMs = totalMs.coerceAtLeast(1L),
            createdAtMs = System.currentTimeMillis(),
        )
        val trimmed = current.takeLast(MAX_RUNS)
        prefs(context).edit().putString(KEY_RUNS, encode(trimmed)).apply()
    }

    fun estimateEtaMs(
        context: Context,
        bulletTotal: Int,
        sampleWindow: Int = 3,
    ): Long {
        if (bulletTotal <= 0) return 0L

        val recent = loadRuns(context).takeLast(sampleWindow.coerceIn(1, 10))
        if (recent.isEmpty()) {
            return (bulletTotal * 1200L).coerceAtLeast(800L)
        }

        val rates = recent.mapNotNull { run ->
            if (run.totalBullets <= 0) null else run.totalMs.toDouble() / run.totalBullets.toDouble()
        }
        val avgMsPerBullet = rates.average().takeIf { it.isFinite() && it > 0.0 } ?: 1200.0
        return (avgMsPerBullet * bulletTotal.toDouble()).roundToLong().coerceAtLeast(600L)
    }

    private fun loadRuns(context: Context): List<Run> {
        val raw = prefs(context).getString(KEY_RUNS, "[]") ?: "[]"
        val arr = runCatching { JSONArray(raw) }.getOrElse { JSONArray() }
        val out = ArrayList<Run>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            out += Run(
                totalBullets = obj.optInt("total_bullets", 0),
                totalMs = obj.optLong("total_ms", 0L),
                createdAtMs = obj.optLong("created_at_ms", 0L),
            )
        }
        return out
    }

    private fun encode(runs: List<Run>): String {
        val arr = JSONArray()
        runs.forEach { run ->
            arr.put(
                JSONObject()
                    .put("total_bullets", run.totalBullets)
                    .put("total_ms", run.totalMs)
                    .put("created_at_ms", run.createdAtMs),
            )
        }
        return arr.toString()
    }
}
