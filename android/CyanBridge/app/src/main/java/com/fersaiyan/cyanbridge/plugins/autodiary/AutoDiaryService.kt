package com.fersaiyan.cyanbridge.plugins.autodiary

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.fersaiyan.cyanbridge.MainActivity
import com.fersaiyan.cyanbridge.R
import com.fersaiyan.cyanbridge.agent.LocalAgentPrefs
import com.fersaiyan.cyanbridge.localagent.dailysummary.DailySummaryRegenerateWorker
import com.fersaiyan.cyanbridge.memoryvault.MemoryModeManager
import com.fersaiyan.cyanbridge.shared.plugins.NativePluginIds
import com.fersaiyan.cyanbridge.ui.CommunityPluginPrefs
import com.fersaiyan.cyanbridge.ui.ensureNotificationPermission
import com.fersaiyan.cyanbridge.ui.hasNotificationPermission
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * AutoDiary lifecycle/scheduler host.
 *
 * CyanBridge owns feature state, interval, privacy policy, storage, indexing and summary
 * generation. Tasker + AutoInput are the Android UI observer; CyanBridge no longer needs
 * its own Accessibility service for AutoDiary screen collection.
 */
class AutoDiaryService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var captureJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> if (isEnabled(this)) startDiary() else stopSelf()
            ACTION_STOP -> stopDiary()
            ACTION_SUMMARIZE -> {
                startForegroundSafely("Preparing today's diary summary")
                queueSummary(this)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            null -> if (isEnabled(this)) startDiary() else stopSelf()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        captureJob?.cancel()
        captureJob = null
        RUNNING.set(false)
        scope.cancel()
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        super.onDestroy()
    }

    private fun startDiary() {
        if (RUNNING.getAndSet(true)) return
        if (!startForegroundSafely("AutoDiary is collecting screen context through Tasker")) {
            RUNNING.set(false)
            disable(this)
            return
        }

        // Persist the new state before clearing the legacy Accessibility capture bit. This
        // migrates users who already had AutoDiary enabled and prevents duplicate captures
        // if CyanBridge's old Accessibility service remains enabled for debugging.
        LocalAgentPrefs.setTaskerAutoDiaryEnabled(this, true)
        LocalAgentPrefs.setAutoCaptureEnabled(this, false)

        captureJob?.cancel()
        captureJob = scope.launch {
            while (isActive && isEnabled(this@AutoDiaryService)) {
                val result = runCatching {
                    AutoDiaryCaptureCoordinator.captureOnce(this@AutoDiaryService)
                }.getOrElse {
                    AutoDiaryCaptureCoordinator.CaptureResult(
                        success = false,
                        detail = "capture_exception:${it.javaClass.simpleName}:${it.message.orEmpty()}",
                    )
                }

                if (result.success) {
                    Log.i(TAG, "AutoDiary capture succeeded: ${result.detail}")
                    updateNotification("AutoDiary captured screen context through Tasker")
                } else {
                    Log.w(TAG, "AutoDiary capture skipped/failed: ${result.detail}")
                    updateNotification("AutoDiary waiting: ${result.detail.take(100)}")
                }

                val delayMs = LocalAgentPrefs.getCaptureIntervalMin(this@AutoDiaryService)
                    .toLong()
                    .coerceAtLeast(1L) * 60_000L
                delay(delayMs)
            }

            if (RUNNING.get()) {
                RUNNING.set(false)
                runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
                stopSelf()
            }
        }
    }

    private fun stopDiary() {
        clearEnabledState(this)
        captureJob?.cancel()
        captureJob = null
        RUNNING.set(false)
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    private fun startForegroundSafely(content: String): Boolean {
        if (!hasNotificationPermission(this)) {
            return false
        }
        return runCatching {
            val notification = notification(content)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }.isSuccess
    }

    private fun updateNotification(content: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        runCatching { manager.notify(NOTIFICATION_ID, notification(content)) }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "AutoDiary",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Tasker screen-memory and daily-summary automation"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            },
        )
    }

    private fun notification(content: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val openPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("AutoDiary")
            .setContentText(content)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(openPendingIntent)
            .build()
    }

    companion object {
        private const val TAG = "AutoDiaryService"
        private const val CHANNEL_ID = "auto_diary"
        private const val NOTIFICATION_ID = 55241
        private val RUNNING = AtomicBoolean(false)

        const val ACTION_START = "com.fersaiyan.cyanbridge.action.AUTO_DIARY_START"
        const val ACTION_STOP = "com.fersaiyan.cyanbridge.action.AUTO_DIARY_STOP"
        const val ACTION_SUMMARIZE = "com.fersaiyan.cyanbridge.action.AUTO_DIARY_SUMMARIZE"

        /** Enables AutoDiary without requesting CyanBridge Accessibility permission. */
        fun enable(context: Context): Boolean {
            if (!hasNotificationPermission(context)) {
                if (context is FragmentActivity) {
                    ensureNotificationPermission(
                        activity = context,
                        feature = "AutoDiary",
                        onDenied = { disable(context) },
                        onGranted = { enable(context) },
                    )
                }
                return false
            }
            setEnabledState(context, true)
            startIfEnabled(context)
            return true
        }

        /** Restores an already-enabled diary without an Accessibility prerequisite. */
        fun startIfEnabled(context: Context): Boolean {
            if (!isEnabled(context) || !hasNotificationPermission(context)) {
                return false
            }

            // Complete the one-time migration from the legacy Accessibility capture bit.
            LocalAgentPrefs.setTaskerAutoDiaryEnabled(context, true)
            LocalAgentPrefs.setAutoCaptureEnabled(context, false)

            val intent = Intent(context, AutoDiaryService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(context, intent)
            } else {
                context.startService(intent)
            }
            return true
        }

        fun disable(context: Context) {
            clearEnabledState(context)
            context.stopService(Intent(context, AutoDiaryService::class.java))
        }

        fun summarize(context: Context) {
            if (!hasNotificationPermission(context)) {
                if (context is FragmentActivity) {
                    ensureNotificationPermission(context, "AutoDiary") {
                        summarize(context)
                    }
                }
                return
            }
            val intent = Intent(context, AutoDiaryService::class.java).setAction(ACTION_SUMMARIZE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(context, intent)
            } else {
                context.startService(intent)
            }
        }

        fun isRunning(): Boolean = RUNNING.get()

        fun isEnabled(context: Context): Boolean =
            LocalAgentPrefs.isTaskerAutoDiaryEnabled(context) &&
                MemoryModeManager.isScreenOcrCaptureEnabled(context)

        private fun setEnabledState(context: Context, enabled: Boolean) {
            LocalAgentPrefs.setTaskerAutoDiaryEnabled(context, enabled)
            // The legacy bit belongs only to LocalAgentAccessibilityService now. Always clear
            // it from the Tasker-backed AutoDiary path so both observers cannot run together.
            LocalAgentPrefs.setAutoCaptureEnabled(context, false)
            MemoryModeManager.setScreenOcrCaptureEnabled(context, enabled)
            CommunityPluginPrefs.setNativePluginEnabled(context, NativePluginIds.AUTO_DIARY, enabled)
        }

        private fun clearEnabledState(context: Context) {
            setEnabledState(context, false)
        }

        private fun queueSummary(context: Context) {
            val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(System.currentTimeMillis()))
            val request = OneTimeWorkRequestBuilder<DailySummaryRegenerateWorker>()
                .setInputData(workDataOf(DailySummaryRegenerateWorker.KEY_DATE to date))
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                DailySummaryRegenerateWorker.uniqueWorkName(date),
                ExistingWorkPolicy.KEEP,
                request,
            )
        }
    }
}
