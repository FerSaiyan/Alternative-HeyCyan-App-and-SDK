package com.fersaiyan.cyanbridge.ui.main

import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import com.fersaiyan.cyanbridge.chat.ChatStore
import com.fersaiyan.cyanbridge.shared.navigation.AppDestination
import com.fersaiyan.cyanbridge.ui.ChatThreadActivity
import com.fersaiyan.cyanbridge.ui.CommunityPluginsActivity
import com.fersaiyan.cyanbridge.ui.SettingsActivity
import com.fersaiyan.cyanbridge.ui.recordings.RecordingsListActivity

/** Opens the top-level destinations of the shared navigation shell from the glasses screen. */
internal class AppDestinationNavigator(private val activity: AppCompatActivity) {
    fun navigateToDestination(destination: AppDestination) {
        when (destination) {
            AppDestination.GLASSES -> Unit
            AppDestination.CHATS -> {
                val last = ChatStore.listNonEmptyThreads().firstOrNull()
                val now = System.currentTimeMillis()
                val lastUserAt = last?.let { thread ->
                    ChatStore.listMessages(thread.id)
                        .lastOrNull { it.role == com.fersaiyan.cyanbridge.shared.chat.ChatRole.USER }
                        ?.createdAt
                } ?: 0L
                val openChatId = last?.id?.takeIf { lastUserAt > 0L && now - lastUserAt < 30 * 60 * 1000L }
                activity.startActivity(Intent(activity, ChatThreadActivity::class.java).apply {
                    openChatId?.let { putExtra(ChatThreadActivity.EXTRA_CHAT_ID, it) }
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                })
            }
            AppDestination.MEDIA -> activity.startActivity(Intent(activity, RecordingsListActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            })
            AppDestination.PLUGINS -> activity.startActivity(Intent(activity, CommunityPluginsActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            })
            AppDestination.SETTINGS -> activity.startActivity(Intent(activity, SettingsActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            })
        }
        if (destination != AppDestination.GLASSES) {
            activity.overridePendingTransition(0, 0)
        }
    }
}
