package com.fersaiyan.cyanbridge.localagent.daily

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.Calendar
import java.util.concurrent.TimeUnit

object NightlyEnrichmentScheduler {
    private const val UNIQUE_WORK = "nightly-personal-knowledge-enrichment"

    fun schedule(context: Context, nowMs: Long = System.currentTimeMillis()) {
        val request = PeriodicWorkRequestBuilder<NightlyEnrichmentWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(delayUntilNightWindow(nowMs), TimeUnit.MILLISECONDS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                    .setRequiresCharging(true)
                    .setRequiresBatteryNotLow(true)
                    .setRequiresStorageNotLow(true)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            UNIQUE_WORK,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    internal fun delayUntilNightWindow(nowMs: Long): Long {
        val target = Calendar.getInstance().apply {
            timeInMillis = nowMs
            set(Calendar.HOUR_OF_DAY, 2)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= nowMs) add(Calendar.DAY_OF_YEAR, 1)
        }
        return (target.timeInMillis - nowMs).coerceAtLeast(0L)
    }
}
