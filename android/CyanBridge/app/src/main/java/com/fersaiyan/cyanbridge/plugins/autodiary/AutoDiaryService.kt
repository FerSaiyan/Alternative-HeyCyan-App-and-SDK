package com.fersaiyan.cyanbridge.plugins.autodiary

import android.content.Context
import android.content.Intent
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.fersaiyan.cyanbridge.agent.LocalAgentPrefs
import com.fersaiyan.cyanbridge.localagent.dailysummary.DailySummaryRegenerateWorker
import com.fersaiyan.cyanbridge.memoryvault.MemoryModeManager
import com.fersaiyan.cyanbridge.shared.plugins.NativePluginIds
import com.fersaiyan.cyanbridge.ui.CommunityPluginPrefs
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * AutoDiary feature-state controller.
 *
 * Tasker owns periodic scheduling, foreground-package exclusion and AutoInput observation.
 * CyanBridge owns Memory Mode, ingestion, encrypted storage, indexing, facts and summaries.
 * There is intentionally no long-running CyanBridge foreground service for periodic capture.
 */
object AutoDiaryService {
    private const val TASKER_PACKAGE = "net.dinglisch.android.taskerm"
    const val ACTION_TASKER_ENABLE = "com.fersaiyan.cyanbridge.TASKER_AUTO_DIARY_ENABLE"
    const val ACTION_TASKER_DISABLE = "com.fersaiyan.cyanbridge.TASKER_AUTO_DIARY_DISABLE"

    fun enable(context: Context): Boolean {
        setEnabledState(context, true)
        syncTaskerState(context)
        return true
    }

    /** Re-sends the authoritative CyanBridge enabled state to Tasker. */
    fun startIfEnabled(context: Context): Boolean {
        val enabled = isEnabled(context)
        syncTaskerState(context)
        return enabled
    }

    fun disable(context: Context) {
        setEnabledState(context, false)
        syncTaskerState(context)
    }

    fun summarize(context: Context) {
        queueSummary(context.applicationContext)
    }

    /** Kept for callers written against the old foreground-service API. */
    fun isRunning(): Boolean = false

    fun isEnabled(context: Context): Boolean =
        LocalAgentPrefs.isTaskerAutoDiaryEnabled(context) &&
            MemoryModeManager.isScreenOcrCaptureEnabled(context)

    fun syncTaskerState(context: Context) {
        val action = if (isEnabled(context)) ACTION_TASKER_ENABLE else ACTION_TASKER_DISABLE
        runCatching {
            context.applicationContext.sendBroadcast(
                Intent(action).setPackage(TASKER_PACKAGE),
            )
        }
    }

    private fun setEnabledState(context: Context, enabled: Boolean) {
        LocalAgentPrefs.setTaskerAutoDiaryEnabled(context, enabled)
        // The old Accessibility observer must never run alongside Tasker-backed AutoDiary.
        LocalAgentPrefs.setAutoCaptureEnabled(context, false)
        MemoryModeManager.setScreenOcrCaptureEnabled(context, enabled)
        CommunityPluginPrefs.setNativePluginEnabled(context, NativePluginIds.AUTO_DIARY, enabled)
    }

    private fun queueSummary(context: Context) {
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(System.currentTimeMillis()))
        val request = OneTimeWorkRequestBuilder<DailySummaryRegenerateWorker>()
            .setInputData(workDataOf(DailySummaryRegenerateWorker.KEY_DATE to date))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            DailySummaryRegenerateWorker.uniqueWorkName(date),
            ExistingWorkPolicy.KEEP,
            request,
        )
    }
}
