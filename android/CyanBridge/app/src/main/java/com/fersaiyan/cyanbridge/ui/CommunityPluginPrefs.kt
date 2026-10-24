package com.fersaiyan.cyanbridge.ui

import android.content.Context
import com.fersaiyan.cyanbridge.shared.prefs.PluginPrefs.Community
import com.fersaiyan.cyanbridge.shared.prefs.get
import com.fersaiyan.cyanbridge.shared.prefs.isSet
import com.fersaiyan.cyanbridge.shared.prefs.set

object CommunityPluginPrefs {
    fun isTaskerAssistantEnabled(context: Context): Boolean =
        if (Community.taskerAssistantEnabled.isSet(context)) {
            Community.taskerAssistantEnabled[context]
        } else {
            Community.legacyGeminiChatGptImageAutomation[context]
        }

    fun setTaskerAssistantEnabled(context: Context, enabled: Boolean) {
        Community.taskerAssistantEnabled[context] = enabled
    }

    fun isNativePluginEnabled(context: Context, pluginId: String): Boolean =
        Community.nativePluginEnabled[pluginId][context]

    fun setNativePluginEnabled(context: Context, pluginId: String, enabled: Boolean) {
        Community.nativePluginEnabled[pluginId][context] = enabled
    }

    fun getGlassesTabShortcutPluginId(context: Context): String? =
        Community.glassesTabShortcutPlugin[context]?.takeIf { it.isNotBlank() }

    fun isGlassesTabShortcutEnabled(context: Context, pluginId: String): Boolean {
        return getGlassesTabShortcutPluginId(context) == pluginId
    }

    fun setGlassesTabShortcutEnabled(context: Context, pluginId: String, enabled: Boolean) {
        if (enabled) {
            Community.glassesTabShortcutPlugin[context] = pluginId
        } else if (getGlassesTabShortcutPluginId(context) == pluginId) {
            Community.glassesTabShortcutPlugin[context] = ""
        }
    }
}
