package com.fersaiyan.cyanbridge.plugins.mentra

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.fersaiyan.cyanbridge.MainActivity
import com.fersaiyan.cyanbridge.R
import com.fersaiyan.cyanbridge.plugins.PluginVoicePermissions
import com.fersaiyan.cyanbridge.plugins.PluginVoiceRecognizer
import com.fersaiyan.cyanbridge.plugins.startPluginVoiceForeground
import com.fersaiyan.cyanbridge.plugins.startPluginVoiceService

/**
 * Speech recognition for running miniapps that subscribed to transcription: a microphone
 * foreground service around the plugins' [PluginVoiceRecognizer] (phone or Bluetooth glasses
 * microphone, one listener at a time through the audio session coordinator). Text goes to
 * [MentraMiniappHost.onTranscription].
 */
class MentraMiniappService : Service() {
    private var recognizer: PluginVoiceRecognizer? = null
    private var language: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_LISTEN -> listen(intent.getStringExtra(EXTRA_LANGUAGE))
            ACTION_STOP_ALL -> {
                MentraMiniappHost.stopAll(this)
                stopListening()
            }
            else -> stopListening()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        recognizer?.stop()
        recognizer = null
        super.onDestroy()
    }

    private fun listen(languageTag: String?) {
        ensureChannel(this)
        if (!startPluginVoiceForeground(this, NOTIFICATION_ID, notification(this))) {
            Log.w(TAG, "Missing microphone or notification permission")
            stopSelf()
            return
        }
        if (recognizer != null && languageTag == language) return
        recognizer?.stop()
        language = languageTag
        val created = PluginVoiceRecognizer(
            context = this,
            languageTag = languageTag,
            onPartialText = { MentraMiniappHost.onTranscription(it, isFinal = false, language = languageTag) },
            onFinalText = { MentraMiniappHost.onTranscription(it, isFinal = true, language = languageTag) },
            onError = { Log.w(TAG, it) },
        )
        recognizer = if (created.start()) created else null
    }

    private fun stopListening() {
        recognizer?.stop()
        recognizer = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    companion object {
        private const val TAG = "MentraMiniappService"
        private const val ACTION_LISTEN = "com.fersaiyan.cyanbridge.mentra.LISTEN"
        private const val ACTION_STOP = "com.fersaiyan.cyanbridge.mentra.STOP"
        private const val ACTION_STOP_ALL = "com.fersaiyan.cyanbridge.mentra.STOP_ALL"
        private const val EXTRA_LANGUAGE = "language"
        private const val CHANNEL_ID = "mentra_miniapps"
        private const val NOTIFICATION_ID = 77431
        @Volatile private var started = false

        fun listen(context: Context, language: String?) {
            if (!PluginVoicePermissions.hasRequiredPermissions(context)) {
                Log.w(TAG, "A miniapp wants transcription, but microphone or notification permission is missing")
                return
            }
            started = true
            runCatching {
                startPluginVoiceService(
                    context,
                    Intent(context, MentraMiniappService::class.java).setAction(ACTION_LISTEN).putExtra(EXTRA_LANGUAGE, language),
                )
            }.onFailure { Log.w(TAG, "Cannot start listening: ${it.message}") }
        }

        fun stop(context: Context) {
            if (!started) return
            started = false
            runCatching { context.startService(Intent(context, MentraMiniappService::class.java).setAction(ACTION_STOP)) }
        }

        private fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, context.getString(R.string.mentra_miniapps_title), NotificationManager.IMPORTANCE_LOW).apply {
                    setShowBadge(false)
                },
            )
        }

        private fun notification(context: Context): Notification {
            val open = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val stopAll = PendingIntent.getService(
                context, 1, Intent(context, MentraMiniappService::class.java).setAction(ACTION_STOP_ALL),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            return NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(context.getString(R.string.mentra_miniapps_title))
                .setContentText(context.getString(R.string.mentra_miniapps_listening))
                .setContentIntent(open)
                .addAction(0, context.getString(R.string.mentra_miniapps_stop_all), stopAll)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .build()
        }
    }
}
