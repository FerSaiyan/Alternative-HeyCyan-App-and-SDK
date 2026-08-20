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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * One-shot Visual Diary foreground capture host.
 *
 * Tasker owns periodic scheduling. Smart-glasses camera access and vision inference remain
 * inside CyanBridge via [VisualDiaryCaptureCoordinator]. This service is only used for an
 * explicit manual "capture now" request that benefits from a visible foreground lifecycle.
 */
class VisualDiaryService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != ACTION_CAPTURE_NOW) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!DeviceCapabilityHelper.hasCamera(this)) {
            Log.w(TAG, "Stopping VisualDiaryService: selected device profile has no camera")
            stopSelf()
            return START_NOT_STICKY
        }
        captureNowAndStop()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        RUNNING.set(false)
        scope.cancel()
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        super.onDestroy()
    }

    private fun captureNowAndStop() {
        if (!startForegroundSafely("Capturing a glasses scene")) {
            stopSelf()
            return
        }
        RUNNING.set(true)
        scope.launch {
            val index = CAPTURE_INDEX.incrementAndGet()
            val result = runCatching {
                VisualDiaryCaptureCoordinator.captureOnce(
                    context = this@VisualDiaryService,
                    captureIndex = index,
                )
            }.getOrElse {
                VisualDiaryCaptureCoordinator.Result(
                    success = false,
                    detail = "capture_exception:${it.javaClass.simpleName}:${it.message.orEmpty()}",
                )
            }

            if (result.success) {
                Log.i(TAG, "Visual Diary manual capture queued: ${result.detail}")
            } else {
                Log.w(TAG, "Visual Diary manual capture failed: ${result.detail}")
            }
            RUNNING.set(false)
            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
            stopSelf()
        }
    }

    private fun startForegroundSafely(content: String): Boolean {
        if (!hasNotificationPermission(this)) return false
        return runCatching {
            val notification = notification(content)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }.isSuccess
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
                description = "One-shot glasses scene capture"
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
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(openPendingIntent)
            .build()
    }

    companion object {
        private const val TAG = "VisualDiaryService"
        private const val CHANNEL_ID = "visual_diary"
        private const val NOTIFICATION_ID = 55242
        private const val TASKER_PACKAGE = "net.dinglisch.android.taskerm"
        private val RUNNING = AtomicBoolean(false)
        private val CAPTURE_INDEX = AtomicInteger(0)

        const val ACTION_CAPTURE_NOW = "com.fersaiyan.cyanbridge.action.VISUAL_DIARY_CAPTURE_NOW"
        const val ACTION_TASKER_ENABLE = "com.fersaiyan.cyanbridge.TASKER_VISUAL_DIARY_ENABLE"
        const val ACTION_TASKER_DISABLE = "com.fersaiyan.cyanbridge.TASKER_VISUAL_DIARY_DISABLE"

        fun enable(context: Context): Boolean {
            if (!DeviceCapabilityHelper.hasCamera(context)) return false
            setEnabledState(context, true)
            syncTaskerState(context)
            return true
        }

        /** Re-sends CyanBridge's authoritative enabled state to the Tasker scheduler. */
        fun startIfEnabled(context: Context): Boolean {
            val enabled = VisualDiaryPreferences.isEnabled(context)
            syncTaskerState(context)
            return enabled
        }

        fun disable(context: Context, error: String? = null) {
            setEnabledState(context, false)
            if (error != null) VisualDiaryPreferences.setLastError(context, error)
            syncTaskerState(context)
            context.stopService(Intent(context, VisualDiaryService::class.java))
        }

        fun syncTaskerState(context: Context) {
            val action = if (VisualDiaryPreferences.isEnabled(context)) ACTION_TASKER_ENABLE else ACTION_TASKER_DISABLE
            runCatching {
                context.applicationContext.sendBroadcast(Intent(action).setPackage(TASKER_PACKAGE))
            }
        }

        fun captureNow(context: Context) {
            if (!hasNotificationPermission(context)) {
                if (context is FragmentActivity) {
                    ensureNotificationPermission(context, "Visual Diary") { captureNow(context) }
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
    }
}
