package com.fersaiyan.cyanbridge.ui.chat

import android.content.Context
import androidx.core.content.ContextCompat
import com.fersaiyan.cyanbridge.R
import com.fersaiyan.cyanbridge.shared.prefs.AppPrefs.ChatAppearance
import com.fersaiyan.cyanbridge.shared.prefs.edit
import com.fersaiyan.cyanbridge.shared.prefs.get
import com.fersaiyan.cyanbridge.shared.prefs.remove
import com.fersaiyan.cyanbridge.shared.prefs.set

object ChatAppearancePrefs {
    fun defaultUserBubbleColor(context: Context): Int =
        ContextCompat.getColor(context, R.color.cyan_accent)

    fun defaultAssistantBubbleColor(context: Context): Int =
        ContextCompat.getColor(context, R.color.card_bg)

    fun getUserBubbleColor(context: Context): Int =
        ChatAppearance.userBubbleColor[context] ?: defaultUserBubbleColor(context)

    fun setUserBubbleColor(context: Context, color: Int) {
        ChatAppearance.userBubbleColor[context] = color
    }

    fun getAssistantBubbleColor(context: Context): Int =
        ChatAppearance.assistantBubbleColor[context] ?: defaultAssistantBubbleColor(context)

    fun setAssistantBubbleColor(context: Context, color: Int) {
        ChatAppearance.assistantBubbleColor[context] = color
    }

    fun getWallpaperUri(context: Context): String = ChatAppearance.wallpaperUri[context]

    fun setWallpaperUri(context: Context, uri: String) {
        ChatAppearance.wallpaperUri[context] = uri
    }

    fun clearWallpaper(context: Context) {
        ChatAppearance.wallpaperUri.remove(context)
    }

    fun reset(context: Context) {
        val userColor = defaultUserBubbleColor(context)
        val assistantColor = defaultAssistantBubbleColor(context)
        ChatAppearance.edit(context) {
            this[ChatAppearance.userBubbleColor] = userColor
            this[ChatAppearance.assistantBubbleColor] = assistantColor
            ChatAppearance.wallpaperUri.remove(this)
        }
    }
}
