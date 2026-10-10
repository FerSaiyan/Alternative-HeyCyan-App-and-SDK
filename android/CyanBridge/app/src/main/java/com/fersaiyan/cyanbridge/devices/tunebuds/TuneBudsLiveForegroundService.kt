package com.fersaiyan.cyanbridge.devices.tunebuds

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
import android.net.wifi.WifiManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.fersaiyan.cyanbridge.MainActivity
import com.fersaiyan.cyanbridge.R

/** Keeps the hotspot and local relay running when the user opens external VLC. */
class TuneBudsLiveForegroundService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "TuneBuds Live", NotificationManager.IMPORTANCE_LOW),
            )
        }
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:tunebuds-live")
            .apply { setReferenceCounted(false) }
        wifiLock = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "$packageName:tunebuds-live")
            .apply { setReferenceCounted(false) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent.action == ACTION_STOP) {
            // Session owner stops us after relay/player/camera/hotspot teardown.
            sendBroadcast(Intent(ACTION_TUNEBUDS_LIVE_STOP).setPackage(packageName))
            return START_NOT_STICKY
        }
        val url = intent.getStringExtra(EXTRA_URL)
        val stopIntent = PendingIntent.getService(this, 0,
            Intent(this, TuneBudsLiveForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val openIntent = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("TuneBuds Live • CyanBridge")
            .setContentText(url ?: "Connecting TuneBuds livestream")
            .setStyle(NotificationCompat.BigTextStyle().bigText(url ?: "Connecting TuneBuds livestream"))
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, "Stop", stopIntent)
            .build()
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                } else 0)
            wakeLock?.let { if (!it.isHeld) it.acquire(30L * 60L * 1000L) }
            wifiLock?.let { if (!it.isHeld) it.acquire() }
        } catch (error: Exception) {
            Log.e("TuneBudsLiveService", "Cannot keep livestream in foreground", error)
            sendBroadcast(Intent(ACTION_TUNEBUDS_LIVE_STOP).setPackage(packageName))
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) runCatching { it.release() } }
        wifiLock?.let { if (it.isHeld) runCatching { it.release() } }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "tunebuds_live"
        private const val NOTIFICATION_ID = 7047
        const val ACTION_TUNEBUDS_LIVE_STOP = "com.fersaiyan.cyanbridge.action.TUNEBUDS_LIVE_STOP"
        private const val ACTION_STOP = "com.fersaiyan.cyanbridge.action.TUNEBUDS_LIVE_FG_STOP"
        private const val EXTRA_URL = "stream_url"

        fun start(context: Context): Boolean = runCatching {
            ContextCompat.startForegroundService(context, Intent(context, TuneBudsLiveForegroundService::class.java))
        }.onFailure { Log.e("TuneBudsLiveService", "Cannot start foreground service", it) }.isSuccess

        fun updateUrl(context: Context, url: String?) {
            runCatching { context.startService(Intent(context, TuneBudsLiveForegroundService::class.java).putExtra(EXTRA_URL, url)) }
                .onFailure { Log.w("TuneBudsLiveService", "Cannot update livestream notification", it) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TuneBudsLiveForegroundService::class.java))
        }
    }
}
