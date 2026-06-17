package com.fersaiyan.cyanbridge.localagent.dailysummary

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

object DailySummaryRunHistory {
    data class RunMetrics(
        val provider: String,
        val inputTokens: Int,
        val outputTokens: Int,
        val promptTokensPerSec: Double,
        val generationTokensPerSec: Double,
        val totalMs: Long,
        val createdAtMs: Long = System.currentTimeMillis(),
    )

    data class Estimate(
        val provider: String,
        val expectedPromptMs: Long,
        val expectedGenerationMs: Long,
        val expectedTotalMs: Long,
        val expectedOutputTokens: Int,
        val sampleCount: Int,
    )

    private const val PREFS_NAME = "daily_summary_run_history"
    private const val KEY_RUNS = "runs_json"
    private const val MAX_RUNS = 20

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun estimateTokenCount(text: String): Int {
        val cleaned = text.trim()
        if (cleaned.isBlank()) return 1
        val words = cleaned.split(Regex("\\s+")).count { it.isNotBlank() }
        return (words * 1.35).roundToInt().coerceAtLeast(1)
    }

    fun record(context: Context, metrics: RunMetrics) {
        val runs = loadRuns(context).toMutableList()
        runs += metrics.copy(
            inputTokens = metrics.inputTokens.coerceAtLeast(1),
            outputTokens = metrics.outputTokens.coerceAtLeast(1),
            promptTokensPerSec = metrics.promptTokensPerSec.coerceAtLeast(1.0),
            generationTokensPerSec = metrics.generationTokensPerSec.coerceAtLeast(1.0),
            totalMs = metrics.totalMs.coerceAtLeast(1L),
        )
        val trimmed = runs.takeLast(MAX_RUNS)
        prefs(context).edit().putString(KEY_RUNS, encode(trimmed)).apply()
    }

    fun estimate(
        context: Context,
        providerHint: String,
        inputTokens: Int,
    ): Estimate {
        val all = loadRuns(context)
        val providerRuns = all.filter { it.provider == providerHint }.takeLast(3)
        val selected = if (providerRuns.isNotEmpty()) providerRuns else all.takeLast(3)

        val avgPromptTps = selected.map { it.promptTokensPerSec }.averageOr(150.0)
        val avgGenTps = selected.map { it.generationTokensPerSec }.averageOr(25.0)
        val avgOutTokens = selected.map { it.outputTokens.toDouble() }.averageOr(220.0)

        val safeInput = inputTokens.coerceAtLeast(1)
        val expectedOutputTokens = avgOutTokens.roundToInt().coerceAtLeast(80)
        val promptMs = ((safeInput / avgPromptTps) * 1000.0).toLong().coerceAtLeast(800L)
        val generationMs = ((expectedOutputTokens / avgGenTps) * 1000.0).toLong().coerceAtLeast(1200L)

        return Estimate(
            provider = providerHint,
            expectedPromptMs = promptMs,
            expectedGenerationMs = generationMs,
            expectedTotalMs = promptMs + generationMs,
            expectedOutputTokens = expectedOutputTokens,
            sampleCount = selected.size,
        )
    }

    private fun loadRuns(context: Context): List<RunMetrics> {
        val raw = prefs(context).getString(KEY_RUNS, "[]") ?: "[]"
        val arr = runCatching { JSONArray(raw) }.getOrElse { JSONArray() }
        val out = ArrayList<RunMetrics>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            out += RunMetrics(
                provider = obj.optString("provider", "unknown").ifBlank { "unknown" },
                inputTokens = obj.optInt("input_tokens", 1).coerceAtLeast(1),
                outputTokens = obj.optInt("output_tokens", 1).coerceAtLeast(1),
                promptTokensPerSec = obj.optDouble("prompt_tps", 1.0).coerceAtLeast(1.0),
                generationTokensPerSec = obj.optDouble("gen_tps", 1.0).coerceAtLeast(1.0),
                totalMs = obj.optLong("total_ms", 1L).coerceAtLeast(1L),
                createdAtMs = obj.optLong("created_at_ms", System.currentTimeMillis()),
            )
        }
        return out
    }

    private fun encode(runs: List<RunMetrics>): String {
        val arr = JSONArray()
        runs.forEach { run ->
            arr.put(
                JSONObject()
                    .put("provider", run.provider)
                    .put("input_tokens", run.inputTokens)
                    .put("output_tokens", run.outputTokens)
                    .put("prompt_tps", run.promptTokensPerSec)
                    .put("gen_tps", run.generationTokensPerSec)
                    .put("total_ms", run.totalMs)
                    .put("created_at_ms", run.createdAtMs),
            )
        }
        return arr.toString()
    }

    private fun List<Double>.averageOr(default: Double): Double {
        if (isEmpty()) return default
        val avg = average()
        return if (avg.isFinite() && avg > 0.0) avg else default
    }
}
