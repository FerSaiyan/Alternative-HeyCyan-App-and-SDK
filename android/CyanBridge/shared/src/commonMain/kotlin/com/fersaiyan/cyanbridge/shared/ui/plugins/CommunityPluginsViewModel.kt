package com.fersaiyan.cyanbridge.shared.ui.plugins

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fersaiyan.cyanbridge.shared.plugins.CommunityPluginCardData
import com.fersaiyan.cyanbridge.shared.plugins.NativePluginCardData
import com.fersaiyan.cyanbridge.shared.plugins.PluginEcosystemSection
import com.fersaiyan.cyanbridge.shared.plugins.PluginTimeWindow
import com.fersaiyan.cyanbridge.shared.ui.UiMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** What [CommunityPluginsViewModel] needs from the platform. */
interface CommunityPluginsPlatform {
    /** The native plugin cards for the selected glasses, with their saved switches. */
    fun nativePlugins(): List<NativePluginCardData>

    /** Third-party ecosystem sections for the selected glasses (see PluginEcosystems.sectionsFor). */
    fun ecosystemSections(): List<PluginEcosystemSection> = emptyList()

    suspend fun fetchCommunityPlugins(): List<CommunityPluginCardData>

    fun showMessage(message: UiMessage)
}

/** State of [CommunityPluginsScreen]: the server catalog, its time window and the native plugin switches. */
class CommunityPluginsViewModel(private val platform: CommunityPluginsPlatform) : ViewModel() {
    var selectedWindow by mutableStateOf(PluginTimeWindow.ALL_TIME)
        private set
    var isRefreshing by mutableStateOf(false)
        private set
    var communityPlugins by mutableStateOf<List<CommunityPluginCardData>>(emptyList())
        private set
    var nativePlugins by mutableStateOf(platform.nativePlugins())
        private set
    var ecosystemSections by mutableStateOf(platform.ecosystemSections())
        private set
    private var serverPluginsLoaded = false

    init {
        refresh()
    }

    fun onWindowSelected(window: PluginTimeWindow) {
        selectedWindow = window
    }

    /** Re-reads the native plugin switches, which other screens may have changed. */
    fun refreshNativePlugins() {
        nativePlugins = platform.nativePlugins()
        ecosystemSections = platform.ecosystemSections()
    }

    /** Shows a switch the host has applied (after any permission it needed). */
    fun onNativePluginToggled(pluginId: String, enabled: Boolean) {
        nativePlugins = nativePlugins.map { if (it.id == pluginId) it.copy(enabled = enabled) else it }
    }

    fun refresh() {
        if (isRefreshing) return
        if (!serverPluginsLoaded) platform.showMessage(UiMessage("Fetching plugins from server..."))
        isRefreshing = true
        viewModelScope.launch {
            val plugins = try {
                platform.fetchCommunityPlugins()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            }
            isRefreshing = false
            if (plugins != null) {
                communityPlugins = plugins
                serverPluginsLoaded = true
                platform.showMessage(UiMessage("Plugins refreshed from server!"))
            } else {
                platform.showMessage(UiMessage("Server unavailable. Official Tasker integrations are still available above.", long = true))
            }
        }
    }
}
