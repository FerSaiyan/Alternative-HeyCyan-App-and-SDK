package com.fersaiyan.cyanbridge.ui

import android.content.Context

object CommunityPluginPrefs {
    private const val PREFS = "community_plugins"
    private const val KEY_TASKER_ASSISTANT_ENABLED = "tasker_assistant_enabled"
    private const val LEGACY_KEY_GEMINI_CHATGPT_IMAGE_AUTOMATION = "gemini_chatgpt_image_automation"
    private const val KEY_GLASS_TAB_SHORTCUT_PLUGIN = "glasses_tab_shortcut_plugin"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isTaskerAssistantEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(
            KEY_TASKER_ASSISTANT_ENABLED,
            prefs(context).getBoolean(LEGACY_KEY_GEMINI_CHATGPT_IMAGE_AUTOMATION, false),
        )
    }

    fun setTaskerAssistantEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_TASKER_ASSISTANT_ENABLED, enabled).apply()
    }

    private fun nativePluginKey(pluginId: String) = "native_plugin_enabled_$pluginId"

    fun isNativePluginEnabled(context: Context, pluginId: String): Boolean {
        return prefs(context).getBoolean(nativePluginKey(pluginId), false)
    }

    fun setNativePluginEnabled(context: Context, pluginId: String, enabled: Boolean) {
        prefs(context).edit().putBoolean(nativePluginKey(pluginId), enabled).apply()
    }

    fun getGlassesTabShortcutPluginId(context: Context): String? {
        return prefs(context)
            .getString(KEY_GLASS_TAB_SHORTCUT_PLUGIN, null)
            ?.takeIf { it.isNotBlank() }
    }

    fun isGlassesTabShortcutEnabled(context: Context, pluginId: String): Boolean {
        return getGlassesTabShortcutPluginId(context) == pluginId
    }

    fun setGlassesTabShortcutEnabled(context: Context, pluginId: String, enabled: Boolean) {
        val editor = prefs(context).edit()
        if (enabled) {
            editor.putString(KEY_GLASS_TAB_SHORTCUT_PLUGIN, pluginId)
        } else if (getGlassesTabShortcutPluginId(context) == pluginId) {
            editor.putString(KEY_GLASS_TAB_SHORTCUT_PLUGIN, "")
        }
        editor.apply()
    }
}
