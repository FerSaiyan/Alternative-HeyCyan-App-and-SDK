package com.fersaiyan.cyanbridge.localagent.daily

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

object DailyFactsReminderScheduler {

    private const val REQ_CODE = 44101

    fun scheduleIfEnabled(context: Context, enabled: Boolean) {
        if (enabled) schedule(context) else cancel(context)
    }

    fun schedule(context: Context) {
        // Review notifications are emitted only after the nightly batch reaches READY.
        // Cancel the legacy unconditional 21:00 alarm during upgrades.
        cancel(context)
    }

    fun cancel(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(pendingIntent(context))
    }

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, DailyFactsReminderReceiver::class.java)
            .setAction(DailyFactsReminderReceiver.ACTION_REMIND)

        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)

        return PendingIntent.getBroadcast(context, REQ_CODE, intent, flags)
    }
}
