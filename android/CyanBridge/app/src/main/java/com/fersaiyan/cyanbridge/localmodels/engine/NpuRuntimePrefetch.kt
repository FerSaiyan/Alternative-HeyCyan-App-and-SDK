package com.fersaiyan.cyanbridge.localmodels.engine

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.fersaiyan.cyanbridge.localmodels.storage.LocalModelStorageRepository
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Keeps this phone's Hexagon skel ([QnnSkels]) next to the Snapdragon NPU models it runs, so an installed NPU model
 * never waits for a download when the user first runs it. Catalog downloads fetch the skel before the model
 * (ModelDownloadForegroundService); this covers the rest, in the background, when the phone is online:
 * - the skel download failed during a model download (the model is installed anyway),
 * - an app update brought a newer QAIRT, whose skels live in a new per-version directory,
 * - an NPU model was imported, or an NPU build of a Walking Aid, voice filter or OCR model was installed
 *   while the runtime download failed.
 */
internal object NpuRuntimePrefetch {
    private const val TAG = "NpuRuntimePrefetch"
    private const val WORK_NAME = "npu-runtime-prefetch"

    /** The Hexagon arch whose skel this phone still needs for an installed NPU model, or null. */
    fun pendingHexagon(context: Context): String? {
        val npu = LiteRtNpuSupport.status(context)
        if (npu.available || !npu.skelDownloadable) return null
        val names = runCatching { LocalModelStorageRepository.listInstalled(context).map { it.fileName } }.getOrDefault(emptyList()) +
            // Walking Aid detectors sit in filesDir; speaker and OCR models one folder down.
            context.filesDir.walkTopDown().maxDepth(2).filter { it.isFile && it.name.endsWith(".tflite") }.map { it.name }.toList()
        val needed = names.any { name ->
            LiteRtNpuSupport.compiledTarget(name)?.let { it.vendor == npu.vendor && it.socModel == npu.socModel } == true
        }
        return if (needed) npu.hexagon else null
    }

    /** Enqueues the background download when an installed NPU model is missing its skel; cheap otherwise. */
    fun schedule(context: Context, reason: String) {
        val hexagon = runCatching { pendingHexagon(context) }.getOrNull() ?: return
        Log.i(TAG, "Hexagon $hexagon NPU library missing ($reason); scheduling its download")
        val request = OneTimeWorkRequestBuilder<Worker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }

    class Worker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            val hexagon = pendingHexagon(applicationContext) ?: return Result.success()
            return runCatching { QnnSkels.ensure(applicationContext, hexagon, cancelled = { isStopped }) }
                .fold(
                    onSuccess = { file ->
                        Log.i(TAG, "Hexagon $hexagon NPU library ready: ${File(file.path).name}")
                        Result.success()
                    },
                    onFailure = { error ->
                        Log.w(TAG, "Hexagon $hexagon NPU library download failed (attempt ${runAttemptCount + 1})", error)
                        if (runAttemptCount < 8) Result.retry() else Result.failure()
                    },
                )
        }
    }
}
