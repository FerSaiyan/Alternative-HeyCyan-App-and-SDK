package com.fersaiyan.cyanbridge.plugins.visualdiary

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.fersaiyan.cyanbridge.devices.DeviceCapabilityHelper

/** Executes one Tasker-scheduled Visual Diary capture without a long-running scheduler service. */
class VisualDiaryCaptureWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        if (!VisualDiaryPreferences.isEnabled(applicationContext)) {
            return Result.success()
        }
        if (!DeviceCapabilityHelper.hasCamera(applicationContext)) {
            VisualDiaryPreferences.setLastError(applicationContext, "Selected device profile has no camera")
            return Result.failure()
        }

        val captureIndex = ((System.currentTimeMillis() / 1000L) and 0x7fffffffL).toInt()
        val result = runCatching {
            VisualDiaryCaptureCoordinator.captureOnce(
                context = applicationContext,
                captureIndex = captureIndex,
            )
        }.getOrElse {
            VisualDiaryCaptureCoordinator.Result(
                success = false,
                detail = "capture_exception:${it.javaClass.simpleName}:${it.message.orEmpty()}",
            )
        }

        return if (result.success) {
            Log.i(TAG, "Tasker Visual Diary capture queued: ${result.detail}")
            Result.success()
        } else {
            Log.w(TAG, "Tasker Visual Diary capture failed: ${result.detail}")
            // Tasker owns periodicity. Do not create a hidden WorkManager retry schedule.
            Result.failure()
        }
    }

    companion object {
        private const val TAG = "VisualDiaryWorker"
    }
}
