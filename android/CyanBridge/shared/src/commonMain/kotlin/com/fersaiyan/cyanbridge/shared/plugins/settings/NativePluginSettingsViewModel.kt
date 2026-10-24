package com.fersaiyan.cyanbridge.shared.plugins.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.fersaiyan.cyanbridge.shared.platform.PlatformPreferences
import com.fersaiyan.cyanbridge.shared.plugins.NativePluginIds
import com.fersaiyan.cyanbridge.shared.prefs.MediaPrefs
import com.fersaiyan.cyanbridge.shared.prefs.PluginPrefs
import com.fersaiyan.cyanbridge.shared.prefs.PrefValue
import com.fersaiyan.cyanbridge.shared.prefs.get
import com.fersaiyan.cyanbridge.shared.prefs.set

/**
 * The platform half of a native plugin settings screen: the plugin's service and stored history.
 * Starting a plugin can need a permission prompt, so the screen's host starts it, not this.
 */
interface NativePluginHost {
    /** Stops the plugin's service after its switch was saved off. */
    fun stopPlugin(pluginId: String)

    /** Deletes the plugin's stored history and tells the user. */
    fun clearHistory(pluginId: String)
}

/**
 * State and preference writes shared by the native plugin settings screens. [store] is the
 * plugin's preference file and [catalog] the Community Plugins file. The state is Compose state,
 * so text fields bound to it update in the same frame they are edited.
 */
abstract class NativePluginSettingsViewModel<S>(
    val pluginId: String,
    protected val store: PlatformPreferences,
    private val catalog: PlatformPreferences,
    protected val host: NativePluginHost,
    load: PlatformPreferences.() -> S,
) : ViewModel() {
    var state: S by mutableStateOf(store.load())
        private set

    protected fun update(transform: S.() -> S) {
        state = state.transform()
    }

    /** Shows [value] at once, then saves it. */
    protected fun <T> save(pref: PrefValue<T>, value: T, show: S.(T) -> S) {
        update { show(value) }
        store[pref] = value
    }

    /** Like [save], but ignores a prompt longer than [maxLength] (the text field stops growing). */
    protected fun savePrompt(pref: PrefValue<String>, prompt: String, maxLength: Int, show: S.(String) -> S) {
        if (prompt.length > maxLength) return
        save(pref, prompt, show)
    }

    /** The master switch: the plugin's own flag, then its catalog flag, then the stop effect. */
    protected fun switchPlugin(enabledPref: PrefValue<Boolean>, enabled: Boolean, show: S.(Boolean) -> S) {
        save(enabledPref, enabled, show)
        setCatalogEnabled(enabled)
        if (!enabled) host.stopPlugin(pluginId)
    }

    fun setCatalogEnabled(enabled: Boolean) {
        catalog[PluginPrefs.Community.nativePluginEnabled[pluginId]] = enabled
    }

    protected fun <T> read(pref: PrefValue<T>): T = store[pref]

    fun onClearHistory() = host.clearHistory(pluginId)
}

/** Each switchable native plugin's own enabled flag, which its service reads; the catalog flag mirrors it. */
val nativePluginEnabledPrefs: Map<String, PrefValue<Boolean>> = mapOf(
    NativePluginIds.WALKING_AID to PluginPrefs.WalkingAid.enabled,
    NativePluginIds.MEETING_SPARK_NOTES to PluginPrefs.MeetingSparkNotes.enabled,
    NativePluginIds.LIVE_CAPTION_RELAY to PluginPrefs.LiveCaptionRelay.enabled,
    NativePluginIds.HANDS_FREE_TRANSLATOR to PluginPrefs.HandsFreeTranslator.enabled,
    NativePluginIds.ERRAND_BRAIN to PluginPrefs.ErrandBrain.enabled,
    NativePluginIds.AUTO_AUDIO to MediaPrefs.AutoAudioCapture.enabled,
)
