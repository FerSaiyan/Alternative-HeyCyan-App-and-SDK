package com.fersaiyan.cyanbridge.integrations.knowledge

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

class KnowledgeSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        if (!KnowledgeIntegrationPrefs.autoSyncEnabled(applicationContext)) return Result.success()
        return runCatching {
            KnowledgeImportCoordinator.syncAll(applicationContext)
            Result.success()
        }.getOrElse {
            if (runAttemptCount < 2) Result.retry() else Result.failure()
        }
    }

    companion object {
        private const val PERIODIC_NAME = "knowledge-integrations-periodic-sync"
        private const val ONE_TIME_NAME = "knowledge-integrations-sync-now"

        fun schedule(context: Context) {
            if (!KnowledgeIntegrationPrefs.autoSyncEnabled(context)) {
                WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_NAME)
                return
            }
            val request = PeriodicWorkRequestBuilder<KnowledgeSyncWorker>(12, TimeUnit.HOURS).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }

        fun syncNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<KnowledgeSyncWorker>().build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                ONE_TIME_NAME,
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }
    }
}
