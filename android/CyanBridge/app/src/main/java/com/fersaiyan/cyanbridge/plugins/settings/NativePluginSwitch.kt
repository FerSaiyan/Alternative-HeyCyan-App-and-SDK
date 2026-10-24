package com.fersaiyan.cyanbridge.plugins.settings

import android.content.Context
import com.fersaiyan.cyanbridge.ui.CommunityPluginPrefs

/**
 * Applies a native plugin's master switch in the order its settings screen has always used:
 * persist the plugin's own enabled flag, mirror it into the plugin catalog, then run the
 * matching start/stop effect.
 */
internal fun Context.applyNativePluginSwitch(
    pluginId: String,
    enabled: Boolean,
    persistPluginFlag: (Boolean) -> Unit,
    onEnabled: () -> Unit = {},
    onDisabled: () -> Unit = {},
) {
    persistPluginFlag(enabled)
    CommunityPluginPrefs.setNativePluginEnabled(this, pluginId, enabled)
    if (enabled) onEnabled() else onDisabled()
}
