package com.fersaiyan.cyanbridge.shared.plugins.settings

import com.fersaiyan.cyanbridge.shared.platform.PlatformPreferences
import com.fersaiyan.cyanbridge.shared.plugins.NativePluginIds
import com.fersaiyan.cyanbridge.shared.prefs.MediaPrefs.AutoAudioCapture
import com.fersaiyan.cyanbridge.shared.prefs.get

data class AutoAudioSettingsState(
    val enabled: Boolean,
    val visualNotes: Boolean,
    val speechExtend: Boolean,
    val loopsPerSync: Int,
)

class AutoAudioSettingsViewModel(
    store: PlatformPreferences,
    catalog: PlatformPreferences,
    host: NativePluginHost,
) : NativePluginSettingsViewModel<AutoAudioSettingsState>(
    NativePluginIds.AUTO_AUDIO, store, catalog, host,
    {
        AutoAudioSettingsState(
            enabled = this[AutoAudioCapture.enabled],
            visualNotes = this[AutoAudioCapture.visualNotesEnabled],
            speechExtend = this[AutoAudioCapture.speechExtendEnabled],
            loopsPerSync = this[AutoAudioCapture.loopsPerSync],
        )
    },
) {
    /**
     * Master switch and the "stop loop" button (which always passes `false`). Turning it off also
     * clears the catalog flag and stops capture; turning it on only saves the switch. The host then
     * asks for the microphone and notification permissions, marks the catalog with
     * [setCatalogEnabled] and starts capture.
     */
    fun onEnabledChange(enabled: Boolean) {
        save(AutoAudioCapture.enabled, enabled) { copy(enabled = it) }
        if (!enabled) {
            setCatalogEnabled(false)
            host.stopPlugin(pluginId)
        }
    }

    fun onSpeechExtendChange(enabled: Boolean) =
        save(AutoAudioCapture.speechExtendEnabled, enabled) { copy(speechExtend = it) }
    fun onVisualNotesChange(enabled: Boolean) =
        save(AutoAudioCapture.visualNotesEnabled, enabled) { copy(visualNotes = it) }
    fun onLoopsPerSyncChange(loops: Int) = save(AutoAudioCapture.loopsPerSync, loops) { copy(loopsPerSync = it) }

    /**
     * Read while composing, not cached in state: the capture service updates this preference on
     * its own, and the screen has always shown the value current at its latest recomposition.
     */
    fun readLastPauseReason(): String = read(AutoAudioCapture.lastPauseReason)
}
