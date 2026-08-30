package com.fersaiyan.cyanbridge.localagent.daily

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.fersaiyan.cyanbridge.R
import com.fersaiyan.cyanbridge.ui.ChatThreadActivity

class DailyFactsReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_REMIND) return

        val date = intent.getStringExtra(EXTRA_DATE)?.trim().orEmpty()
        val count = intent.getIntExtra(EXTRA_COUNT, 0)
        if (date.isBlank() || count <= 0 || !NightlyEnrichmentPrefs.isReady(context, date)) return
        ensureChannel(context)

        val openIntent = Intent(context, ChatThreadActivity::class.java)
            .putExtra(ChatThreadActivity.EXTRA_CREATE_THREAD_TITLE, "Daily facts review")
            .putExtra(ChatThreadActivity.EXTRA_DAILY_FACTS_REVIEW, true)
            .putExtra(ChatThreadActivity.EXTRA_DAILY_FACTS_DATE, date)
            .putExtra(ChatThreadActivity.EXTRA_DAILY_FACTS_LOOKBACK_DAYS, 1)

        val openPi = PendingIntent.getActivity(
            context,
            date.hashCode(),
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Yesterday's facts are ready")
            .setContentText("Review $count processed memory candidate${if (count == 1) "" else "s"}")
            .setContentIntent(openPi)
            .setAutoCancel(true)
            .build()

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, notification)
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Daily facts",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Daily prompts to verify Local Agent facts"
        }
        nm.createNotificationChannel(channel)
    }

    companion object {
        const val ACTION_REMIND = "com.fersaiyan.cyanbridge.action.DAILY_FACTS_REMIND"
        const val EXTRA_DATE = "daily_facts_date"
        const val EXTRA_COUNT = "daily_facts_count"

        private const val CHANNEL_ID = "daily_facts"
        private const val NOTIF_ID = 44102
    }
}
