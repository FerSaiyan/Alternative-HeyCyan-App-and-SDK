package com.fersaiyan.cyanbridge.ui.main

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import com.fersaiyan.cyanbridge.MainActivity
import com.fersaiyan.cyanbridge.chat.ChatStore
import com.fersaiyan.cyanbridge.localagent.dailyfacts.DailyFactsReviewThreadStore
import com.fersaiyan.cyanbridge.shared.chat.ChatRole
import com.fersaiyan.cyanbridge.shared.navigation.AppDestination
import com.fersaiyan.cyanbridge.ui.ChatThreadActivity
import com.fersaiyan.cyanbridge.ui.CommunityPluginsActivity
import com.fersaiyan.cyanbridge.ui.SettingsActivity
import com.fersaiyan.cyanbridge.ui.recordings.RecordingsListActivity

/** Opens the top-level destinations of the shared navigation shell from the glasses screen. */
internal class AppDestinationNavigator(private val activity: AppCompatActivity) {
    fun navigateToDestination(destination: AppDestination) {
        if (destination != AppDestination.GLASSES) activity.openAppDestination(destination)
    }
}

private const val TOP_LEVEL_FLAGS =
    Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION

/**
 * Opens a bottom-bar destination without a transition. [chatsIntent] returns null to stay on
 * the current chat.
 */
internal fun Activity.openAppDestination(
    destination: AppDestination,
    chatsIntent: () -> Intent? = { recentChatIntent() },
) {
    val target = when (destination) {
        AppDestination.GLASSES -> Intent(this, MainActivity::class.java)
        AppDestination.CHATS -> chatsIntent() ?: return
        AppDestination.MEDIA -> Intent(this, RecordingsListActivity::class.java)
        AppDestination.PLUGINS -> Intent(this, CommunityPluginsActivity::class.java)
        AppDestination.SETTINGS -> Intent(this, SettingsActivity::class.java)
    }
    startActivity(target.addFlags(TOP_LEVEL_FLAGS))
    overridePendingTransition(0, 0)
}

/**
 * The Chats tab reopens the latest chat while its user wrote in the last 30 minutes, else starts
 * a new one. With [withDailyReview] a daily review chat reopens in review mode.
 */
internal fun Context.recentChatIntent(withDailyReview: Boolean = false): Intent {
    val last = ChatStore.listNonEmptyThreads().firstOrNull()
    val lastUserAt = last?.let { thread ->
        ChatStore.listMessages(thread.id).lastOrNull { it.role == ChatRole.USER }?.createdAt
    } ?: 0L
    val openChatId = last?.id?.takeIf { lastUserAt > 0L && System.currentTimeMillis() - lastUserAt < 30 * 60 * 1000L }
    return Intent(this, ChatThreadActivity::class.java).apply {
        if (openChatId != null) {
            if (withDailyReview) putChatExtras(this@recentChatIntent, openChatId) else putExtra(ChatThreadActivity.EXTRA_CHAT_ID, openChatId)
        }
    }
}

/** Points [ChatThreadActivity] at [chatId], in review mode when it is a daily review chat. */
internal fun Intent.putChatExtras(context: Context, chatId: String): Intent = apply {
    putExtra(ChatThreadActivity.EXTRA_CHAT_ID, chatId)
    DailyFactsReviewThreadStore.load(context, chatId)?.let { config ->
        putExtra(ChatThreadActivity.EXTRA_DAILY_FACTS_REVIEW, true)
        putExtra(ChatThreadActivity.EXTRA_DAILY_FACTS_DATE, config.date)
        putExtra(ChatThreadActivity.EXTRA_DAILY_FACTS_LOOKBACK_DAYS, config.lookbackDays)
    }
}
