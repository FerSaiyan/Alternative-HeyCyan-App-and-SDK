package com.fersaiyan.cyanbridge.plugins.visualdiary

import android.content.Context
import com.fersaiyan.cyanbridge.shared.prefs.PluginPrefs.VisualDiary
import com.fersaiyan.cyanbridge.shared.prefs.get
import com.fersaiyan.cyanbridge.shared.prefs.remove
import com.fersaiyan.cyanbridge.shared.prefs.set

object VisualDiaryPreferences {
    fun isEnabled(context: Context): Boolean = VisualDiary.enabled[context]

    fun setEnabled(context: Context, enabled: Boolean) {
        VisualDiary.enabled[context] = enabled
    }

    fun getIntervalMinutes(context: Context): Int = VisualDiary.intervalMinutes[context]

    fun setIntervalMinutes(context: Context, minutes: Int) {
        VisualDiary.intervalMinutes[context] = minutes
    }

    fun getCustomPrompt(context: Context): String = VisualDiary.customPrompt[context]

    fun setCustomPrompt(context: Context, prompt: String) {
        VisualDiary.customPrompt[context] = prompt
    }

    fun getLastError(context: Context): String = VisualDiary.lastError[context]

    fun setLastError(context: Context, error: String) {
        VisualDiary.lastError[context] = error.trim().take(500)
    }

    fun clearLastError(context: Context) {
        VisualDiary.lastError.remove(context)
    }
}
