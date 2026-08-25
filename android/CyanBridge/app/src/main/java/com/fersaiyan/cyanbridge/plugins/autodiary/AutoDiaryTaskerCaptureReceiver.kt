package com.fersaiyan.cyanbridge.plugins.autodiary

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Receives periodic observations produced by the Tasker AutoDiary profile.
 *
 * No package enumeration happens in CyanBridge. Tasker decides whether the current app is
 * excluded before it sends the observation. CyanBridge still owns whether AutoDiary is
 * enabled and whether the received content is eligible for Memory Vault ingestion.
 */
class AutoDiaryTaskerCaptureReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_CAPTURE) return

        val appContext = context.applicationContext
        if (!AutoDiaryService.isEnabled(appContext)) {
            // Self-heal stale Tasker state after reinstall/reboot/app-data changes.
            AutoDiaryService.syncTaskerState(appContext)
            return
        }

        val payload = intent.getStringExtra(EXTRA_PAYLOAD)?.takeIf { it.isNotBlank() } ?: return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val result = AutoDiaryCaptureCoordinator.ingestObservationJson(appContext, payload)
                if (result.success) {
                    Log.i(TAG, "Tasker AutoDiary capture stored: ${result.detail}")
                } else {
                    Log.w(TAG, "Tasker AutoDiary capture skipped: ${result.detail}")
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Tasker AutoDiary ingestion failed", t)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "AutoDiaryTaskerRx"
        const val ACTION_CAPTURE = "com.fersaiyan.cyanbridge.TASKER_AUTO_DIARY_CAPTURE"
        const val EXTRA_PAYLOAD = "payload"
    }
}
