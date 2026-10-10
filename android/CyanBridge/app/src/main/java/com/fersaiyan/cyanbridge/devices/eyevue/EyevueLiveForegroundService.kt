package com.fersaiyan.cyanbridge.devices.eyevue

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.fersaiyan.cyanbridge.MainActivity
import com.fersaiyan.cyanbridge.R

/**
 * Minimal foreground service that keeps an EyeVue live preview (and its local
 * relay for other apps) alive while this app is in the background. Copied from
 * the Gemini live service shape: ongoing notification with Stop + tap-to-open.
 */
class EyevueLiveForegroundService : Service() {
    private val notificationManager by lazy { getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager }
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null
    private var streamUrl: String? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "EyeVue Live", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "EyeVue glasses live preview and local stream relay"
                    setShowBadge(false)
                },
            )
        }
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:eyevue-live").apply {
            setReferenceCounted(false)
        }
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
        wifiLock = wifiManager.createWifiLock(
            android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF,
            "$packageName:eyevue-live",
        ).apply { setReferenceCounted(false) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Log.i(TAG, "Stop requested via notification")
                sendBroadcast(Intent(ACTION_EYEVUE_LIVE_STOP).setPackage(packageName))
                stopLive()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                streamUrl = intent.getStringExtra(EXTRA_STREAM_URL)?.takeIf { it.isNotBlank() }
                if (!startForegroundWithStatus(streamUrl)) {
                    stopLive()
                    return START_NOT_STICKY
                }
                wakeLock?.let { lock ->
                    if (lock.isHeld) lock.release()
                    lock.acquire(MAX_DURATION_MS)
                }
                wifiLock?.let { lock ->
                    if (!lock.isHeld) runCatching { lock.acquire() }
                }
                return START_NOT_STICKY
            }
            ACTION_UPDATE_URL -> {
                streamUrl = intent.getStringExtra(EXTRA_STREAM_URL)?.takeIf { it.isNotBlank() }
                startForegroundWithStatus(streamUrl)
                return START_NOT_STICKY
            }
        }
        if (intent == null) {
            stopLive()
            return START_NOT_STICKY
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) runCatching { it.release() } }
        wakeLock = null
        wifiLock?.let { if (it.isHeld) runCatching { it.release() } }
        wifiLock = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundWithStatus(url: String?): Boolean {
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, EyevueLiveForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val contentText = if (url.isNullOrBlank()) {
            "EyeVue live preview running"
        } else {
            "EyeVue live: $url"
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("EyeVue Live • CyanBridge")
            .setContentText(contentText)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$contentText\nOpen in VLC while live runs."))
            .setContentIntent(openIntent)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(NotificationCompat.Action.Builder(0, "Stop", stopIntent).build())
            .build()
        return runCatching {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, notification,
                when {
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                    else -> 0
                },
            )
        }.onFailure { Log.e(TAG, "Unable to start EyeVue live foreground service", it) }.isSuccess
    }

    private fun stopLive() {
        wakeLock?.let { if (it.isHeld) runCatching { it.release() } }
        wifiLock?.let { if (it.isHeld) runCatching { it.release() } }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    companion object {
        private const val TAG = "EyevueLiveService"
        private const val CHANNEL_ID = "eyevue_live"
        private const val NOTIFICATION_ID = 7045
        private const val MAX_DURATION_MS = 30L * 60L * 1000L
        const val ACTION_EYEVUE_LIVE_STOP = "com.fersaiyan.cyanbridge.action.EYEVUE_LIVE_STOP"
        private const val ACTION_START = "com.fersaiyan.cyanbridge.action.EYEVUE_LIVE_FG_START"
        private const val ACTION_STOP = "com.fersaiyan.cyanbridge.action.EYEVUE_LIVE_FG_STOP"
        private const val ACTION_UPDATE_URL = "com.fersaiyan.cyanbridge.action.EYEVUE_LIVE_FG_URL"
        private const val EXTRA_STREAM_URL = "stream_url"

        fun start(context: Context, streamUrl: String? = null) {
            val intent = Intent(context, EyevueLiveForegroundService::class.java)
                .setAction(ACTION_START)
            if (!streamUrl.isNullOrBlank()) intent.putExtra(EXTRA_STREAM_URL, streamUrl)
            runCatching { ContextCompat.startForegroundService(context, intent) }
                .onFailure { Log.w(TAG, "Unable to start EyeVue live service", it) }
        }

        fun updateUrl(context: Context, streamUrl: String?) {
            val intent = Intent(context, EyevueLiveForegroundService::class.java)
                .setAction(ACTION_UPDATE_URL)
            if (!streamUrl.isNullOrBlank()) intent.putExtra(EXTRA_STREAM_URL, streamUrl)
            runCatching { context.startService(intent) }
                .onFailure { Log.w(TAG, "Unable to update EyeVue live URL", it) }
        }

        fun stop(context: Context) {
            val intent = Intent(context, EyevueLiveForegroundService::class.java).setAction(ACTION_STOP)
            runCatching { context.startService(intent) }
                .onFailure {
                    Log.w(TAG, "Unable to deliver EyeVue live stop; stopping directly", it)
                    context.stopService(Intent(context, EyevueLiveForegroundService::class.java))
                }
        }
    }
}
