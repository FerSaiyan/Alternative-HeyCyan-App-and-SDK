package com.fersaiyan.cyanbridge.plugins.visualdiary

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager

/** Receives Tasker's periodic Visual Diary trigger and queues one CyanBridge capture. */
class VisualDiaryTaskerCaptureReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_CAPTURE) return

        val appContext = context.applicationContext
        if (!VisualDiaryPreferences.isEnabled(appContext)) {
            VisualDiaryService.syncTaskerState(appContext)
            return
        }

        val request = OneTimeWorkRequestBuilder<VisualDiaryCaptureWorker>().build()
        WorkManager.getInstance(appContext).enqueueUniqueWork(
            UNIQUE_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    companion object {
        const val ACTION_CAPTURE = "com.fersaiyan.cyanbridge.TASKER_VISUAL_DIARY_CAPTURE"
        private const val UNIQUE_WORK_NAME = "visual_diary_tasker_capture"
    }
}
