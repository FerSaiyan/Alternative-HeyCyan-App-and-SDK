package com.fersaiyan.cyanbridge.shared.platform

import com.fersaiyan.cyanbridge.shared.plugins.CommunityPluginCardData
import kotlinx.coroutines.flow.StateFlow

/**
 * Native plugin runtime and community catalog for the shared Plugins destination.
 * The iOS host installs it; Android keeps CommunityPluginsActivity.
 */
interface SharedPluginsPlatform {
    /** Native plugin ids this platform can run. */
    val availablePluginIds: Set<String>
    val enabledPluginIds: StateFlow<Set<String>>

    fun setPluginEnabled(id: String, enabled: Boolean)
    suspend fun fetchCommunityPlugins(): List<CommunityPluginCardData>
    fun openLink(url: String)
    suspend fun publishPlugin(title: String, author: String, description: String, category: String, link: String): Boolean
}

object SharedPluginsHooks {
    var platform: SharedPluginsPlatform? = null
}
