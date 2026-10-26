package com.fersaiyan.cyanbridge.devices.metarayban

import com.fersaiyan.cyanbridge.ui.nav.AppRoute
import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.fersaiyan.cyanbridge.R
import com.fersaiyan.cyanbridge.audio.TtsSpeaker
import com.fersaiyan.cyanbridge.shared.glasses.MetaSetupChoice
import com.fersaiyan.cyanbridge.ui.MetaPairingDestination

/**
 * Tells the user that a Developer-Mode registration ended (Developer Mode turned off in Meta AI,
 * or CyanBridge disconnected there): spoken through the app's [TtsSpeaker], and a notification
 * whose "Register again" action opens Meta setup. Meta sends no signal when Developer Mode is
 * turned back on, so the user re-registers by hand.
 */
internal object MetaRegistrationNotices {
    private const val TAG = "MetaRegistrationNotice"
    private const val CHANNEL_ID = "meta_registration"
    private const val NOTIFICATION_ID = 4107

    private val lock = Any()
    private var speaker: TtsSpeaker? = null

    fun developerModeLost(context: Context, choice: MetaSetupChoice) {
        val appContext = context.applicationContext
        val title = appContext.getString(R.string.meta_registration_lost_title)
        val text = appContext.getString(
            if (choice == MetaSetupChoice.EMAIL_INVITE) R.string.meta_registration_lost_invite_text
            else R.string.meta_registration_lost_dev_mode_text,
        )
        speak(appContext, "$title. $text")
        notify(appContext, title, text)
    }

    private fun speak(context: Context, text: String) {
        val current = synchronized(lock) {
            speaker ?: TtsSpeaker(context, TAG, onInit = { engine, ok ->
                if (ok) engine.setLanguage(context.resources.configuration.locales[0])
            }).also { speaker = it }
        }
        current.whenReady { it.speak(text, "meta_registration_lost") }
    }

    private fun notify(context: Context, title: String, text: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.i(TAG, "Notifications are not allowed; the notice is spoken and shown in Meta setup")
            return
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.meta_registration_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
        val registerAgain = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            AppRoute.META_PAIRING.intent(context)
                .putExtra(MetaPairingDestination.EXTRA_REGISTER_AGAIN, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(registerAgain)
            .addAction(0, context.getString(R.string.meta_registration_register_again), registerAgain)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification) }
            .onFailure { Log.w(TAG, "Could not post the Meta registration notice", it) }
    }

    fun cancel(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }
}
