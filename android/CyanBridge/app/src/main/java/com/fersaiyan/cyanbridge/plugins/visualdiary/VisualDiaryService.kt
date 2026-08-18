package com.fersaiyan.cyanbridge.plugins.visualdiary

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
import com.fersaiyan.cyanbridge.MainActivity
import com.fersaiyan.cyanbridge.R
import com.fersaiyan.cyanbridge.devices.DeviceCapabilityHelper
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Visual Diary lifecycle/scheduler host.
 *
 * The service owns enable/disable state, foreground lifecycle and interval scheduling.
 * One-shot smart-glasses capture and vision-pipeline handoff live in
 * [VisualDiaryCaptureCoordinator], entirely inside CyanBridge rather than Tasker.
 */
class VisualDiaryService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loopJob: Job? = null
    private val captureIndex = AtomicInteger(0)

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!DeviceCapabilityHelper.hasCamera(this)) {
            Log.w(TAG, "Stopping VisualDiaryService: selected device profile has no camera")
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_START -> if (VisualDiaryPreferences.isEnabled(this)) startLoop() else stopSelf()
            ACTION_STOP -> stopLoop()
            ACTION_CAPTURE_NOW -> captureNowAndKeepAlive()
            null -> if (VisualDiaryPreferences.isEnabled(this)) startLoop() else stopSelf()
        }
        return START_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "Foreground-service timeout: type=$fgsType; stopping without disabling Visual Diary")
        RUNNING.set(false)
        loopJob?.cancel()
        loopJob = null
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf(startId)
    }

    override fun onDestroy() {
        stopLoop(clearPreference = false)
        scope.cancel()
        super.onDestroy()
    }

    private fun startLoop() {
        if (RUNNING.getAndSet(true)) return

        if (!startForegroundSafely("Visual Diary is ready")) {
            RUNNING.set(false)
            disable(this, "Notification permission is required for Visual Diary")
            return
        }

        loopJob = scope.launch {
            val ready = runCatching {
                VisualDiaryCaptureCoordinator.prepare(this@VisualDiaryService)
            }.getOrElse {
                VisualDiaryCaptureCoordinator.Result(
                    success = false,
                    detail = "prepare_exception:${it.javaClass.simpleName}:${it.message.orEmpty()}",
                )
            }
            if (!ready.success) {
                Log.e(TAG, "Visual Diary cannot start: ${ready.detail}")
                updateNotification("Visual Diary unavailable: ${ready.detail.take(120)}")
                stopLoop()
                return@launch
            }

            while (isActive && VisualDiaryPreferences.isEnabled(this@VisualDiaryService)) {
                captureNowInternal()
                val delayMs = VisualDiaryPreferences.getIntervalMinutes(this@VisualDiaryService)
                    .toLong()
                    .coerceAtLeast(1L) * 60_000L
                delay(delayMs)
            }
            stopLoop()
        }
    }

    private fun captureNowAndKeepAlive() {
        if (!startForegroundSafely("Capturing a glasses scene")) {
            stopSelf()
            return
        }
        scope.launch {
            captureNowInternal()
            delay(CAPTURE_KEEP_ALIVE_MS)
            if (!RUNNING.get()) stopSelf()
        }
    }

    private suspend fun captureNowInternal() {
        val index = captureIndex.incrementAndGet()
        updateNotification("Capturing scene note #$index")

        val result = runCatching {
            VisualDiaryCaptureCoordinator.captureOnce(
                context = this,
                captureIndex = index,
            )
        }.getOrElse {
            VisualDiaryCaptureCoordinator.Result(
                success = false,
                detail = "capture_exception:${it.javaClass.simpleName}:${it.message.orEmpty()}",
            )
        }

        if (result.success) {
            Log.i(TAG, "Visual Diary capture queued: ${result.detail}")
            updateNotification("Visual Diary scene #$index queued")
        } else {
            Log.w(TAG, "Visual Diary capture failed: ${result.detail}")
            updateNotification("Visual Diary capture failed: ${result.detail.take(120)}")
        }
    }

    private fun stopLoop(clearPreference: Boolean = true) {
        if (clearPreference) clearEnabledState(this)
        RUNNING.set(false)
        loopJob?.cancel()
        loopJob = null
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
                "Visual Diary",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Automatic glasses scene notes"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            },
        )
    }

    private fun notification(content: String): Notification {
        val openPendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Visual Diary")
            .setContentText(content)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(RUNNING.get())
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(openPendingIntent)
            .build()
    }

    companion object {
        private const val TAG = "VisualDiaryService"
        private const val CHANNEL_ID = "visual_diary"
        private const val NOTIFICATION_ID = 55242
        private const val CAPTURE_KEEP_ALIVE_MS = 60_000L
        private val RUNNING = AtomicBoolean(false)

        const val ACTION_START = "com.fersaiyan.cyanbridge.action.VISUAL_DIARY_START"
        const val ACTION_STOP = "com.fersaiyan.cyanbridge.action.VISUAL_DIARY_STOP"
        const val ACTION_CAPTURE_NOW = "com.fersaiyan.cyanbridge.action.VISUAL_DIARY_CAPTURE_NOW"

        /** Enables Visual Diary only after it can run as a foreground service. */
        fun enable(context: Context): Boolean {
            if (!hasNotificationPermission(context)) {
                if (context is FragmentActivity) {
                    ensureNotificationPermission(
                        activity = context,
                        feature = "Visual Diary",
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

        /** Restores an already-enabled Visual Diary without prompting from a background context. */
        fun startIfEnabled(context: Context): Boolean {
            if (!VisualDiaryPreferences.isEnabled(context) || !hasNotificationPermission(context)) {
                return false
            }
            val intent = Intent(context, VisualDiaryService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(context, intent)
            } else {
                context.startService(intent)
            }
            return true
        }

        fun disable(context: Context, error: String? = null) {
            clearEnabledState(context)
            if (error != null) VisualDiaryPreferences.setLastError(context, error)
            context.stopService(Intent(context, VisualDiaryService::class.java))
        }

        fun captureNow(context: Context) {
            if (!hasNotificationPermission(context)) {
                if (context is FragmentActivity) {
                    ensureNotificationPermission(context, "Visual Diary") {
                        captureNow(context)
                    }
                }
                return
            }
            val intent = Intent(context, VisualDiaryService::class.java).setAction(ACTION_CAPTURE_NOW)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(context, intent)
            } else {
                context.startService(intent)
            }
        }

        fun isRunning(): Boolean = RUNNING.get()

        private fun setEnabledState(context: Context, enabled: Boolean) {
            VisualDiaryPreferences.setEnabled(context, enabled)
            CommunityPluginPrefs.setNativePluginEnabled(context, NativePluginIds.VISUAL_DIARY, enabled)
        }

        private fun clearEnabledState(context: Context) {
            setEnabledState(context, false)
        }
    }
}
