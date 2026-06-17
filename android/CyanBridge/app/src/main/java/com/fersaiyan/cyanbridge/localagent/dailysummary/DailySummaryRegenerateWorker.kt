package com.fersaiyan.cyanbridge.localagent.dailysummary

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import java.util.concurrent.atomic.AtomicInteger

class DailySummaryRegenerateWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = supervisorScope {
        val date = inputData.getString(KEY_DATE)?.trim().orEmpty()
        if (date.isBlank()) {
            return@supervisorScope Result.failure(workDataOf(KEY_ERROR to "Missing summary date"))
        }

        val provider = DailySummaryGenerator.providerHint(applicationContext)
        val inputTokens = DailySummaryGenerator.estimateInputTokensForDate(applicationContext, date)
        val initialBulletTotal = DailySummaryGenerator.estimateBulletEventsForDate(applicationContext, date)
        val estimate = DailySummaryRunHistory.estimate(
            context = applicationContext,
            providerHint = provider,
            inputTokens = inputTokens,
        )
        val bulletEtaMs = DailySummaryBulletRunHistory.estimateEtaMs(
            context = applicationContext,
            bulletTotal = initialBulletTotal,
        )
        val expectedTotalMs = estimate.expectedTotalMs + bulletEtaMs
        val bulletDoneRef = AtomicInteger(0)
        val bulletTotalRef = AtomicInteger(initialBulletTotal)

        setProgress(progressData(
            percent = 1,
            etaMs = expectedTotalMs,
            stage = "Preparing prompt",
            provider = estimate.provider,
            sampleCount = estimate.sampleCount,
            bulletDone = 0,
            bulletTotal = initialBulletTotal,
        ))

        val started = System.currentTimeMillis()
        val ticker = launch {
            while (isActive) {
                val elapsed = (System.currentTimeMillis() - started).coerceAtLeast(0L)
                val total = expectedTotalMs.coerceAtLeast(2_000L)
                val bulletDone = bulletDoneRef.get().coerceAtLeast(0)
                val bulletTotal = bulletTotalRef.get().coerceAtLeast(0)

                val (percent, eta, stage) = if (bulletTotal > 0 && bulletDone < bulletTotal) {
                    val ratio = bulletDone.toDouble() / bulletTotal.toDouble()
                    val bulletPercent = (ratio * 85.0).toInt().coerceIn(1, 94)
                    val bulletsRemaining = (bulletTotal - bulletDone).coerceAtLeast(0)
                    val bulletEta = DailySummaryBulletRunHistory.estimateEtaMs(applicationContext, bulletsRemaining)
                    Triple(bulletPercent, bulletEta, "Inferring bullets ($bulletDone/$bulletTotal)")
                } else {
                    val rawPercent = ((elapsed.toDouble() / total.toDouble()) * 100.0).toInt()
                    val fallbackPercent = rawPercent.coerceIn(1, 95)
                    val fallbackEta = (total - elapsed).coerceAtLeast(0L)
                    val fallbackStage = if (elapsed < estimate.expectedPromptMs) {
                        "Processing prompt"
                    } else {
                        "Generating summary"
                    }
                    Triple(fallbackPercent, fallbackEta, fallbackStage)
                }

                setProgress(
                    progressData(
                        percent = percent,
                        etaMs = eta,
                        stage = stage,
                        provider = estimate.provider,
                        sampleCount = estimate.sampleCount,
                        bulletDone = bulletDone,
                        bulletTotal = bulletTotal,
                    ),
                )
                delay(500L)
            }
        }

        val generation = DailySummaryGenerator.generateAndStore(
            context = applicationContext,
            date = date,
            onBulletProgress = { progress ->
                bulletDoneRef.set(progress.done)
                bulletTotalRef.set(progress.total)
            },
        )
        ticker.cancel()

        val finalBulletDone = bulletDoneRef.get().coerceAtLeast(0)
        val finalBulletTotal = bulletTotalRef.get().coerceAtLeast(0)

        return@supervisorScope generation.fold(
            onSuccess = { file ->
                setProgress(
                    progressData(
                        percent = 100,
                        etaMs = 0L,
                        stage = "Completed",
                        provider = estimate.provider,
                        sampleCount = estimate.sampleCount,
                        bulletDone = finalBulletDone,
                        bulletTotal = finalBulletTotal,
                    ),
                )
                Result.success(
                    workDataOf(
                        KEY_DATE to date,
                        KEY_OUTPUT_PATH to file.absolutePath,
                    ),
                )
            },
            onFailure = { err ->
                Result.failure(
                    workDataOf(
                        KEY_DATE to date,
                        KEY_ERROR to (err.message ?: "Failed to regenerate summary"),
                    ),
                )
            },
        )
    }

    private fun progressData(
        percent: Int,
        etaMs: Long,
        stage: String,
        provider: String,
        sampleCount: Int,
        bulletDone: Int,
        bulletTotal: Int,
    ): Data {
        return workDataOf(
            KEY_PROGRESS_PERCENT to percent.coerceIn(0, 100),
            KEY_ETA_MS to etaMs.coerceAtLeast(0L),
            KEY_STAGE to stage,
            KEY_PROVIDER to provider,
            KEY_SAMPLE_COUNT to sampleCount.coerceAtLeast(0),
            KEY_BULLET_DONE to bulletDone.coerceAtLeast(0),
            KEY_BULLET_TOTAL to bulletTotal.coerceAtLeast(0),
        )
    }

    companion object {
        const val KEY_DATE = "key_date"
        const val KEY_OUTPUT_PATH = "key_output_path"
        const val KEY_ERROR = "key_error"

        const val KEY_PROGRESS_PERCENT = "key_progress_percent"
        const val KEY_ETA_MS = "key_eta_ms"
        const val KEY_STAGE = "key_stage"
        const val KEY_PROVIDER = "key_provider"
        const val KEY_SAMPLE_COUNT = "key_sample_count"
        const val KEY_BULLET_DONE = "key_bullet_done"
        const val KEY_BULLET_TOTAL = "key_bullet_total"

        fun uniqueWorkName(date: String): String = "daily_summary_regen_${date.trim()}"

        fun buildRequest(date: String) = OneTimeWorkRequestBuilder<DailySummaryRegenerateWorker>()
            .setInputData(workDataOf(KEY_DATE to date.trim()))
            .build()
    }
}
