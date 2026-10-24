package com.fersaiyan.cyanbridge.ui.appearance

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import com.fersaiyan.cyanbridge.shared.appearance.AppearanceSettings
import com.fersaiyan.cyanbridge.shared.appearance.AppearanceSettingsStore
import com.fersaiyan.cyanbridge.shared.platform.PlatformPreferences
import com.fersaiyan.cyanbridge.shared.prefs.AppPrefs
import com.fersaiyan.cyanbridge.shared.prefs.sharedPreferences

/** Android appearance settings: the shared store over the `appearance_settings` file. */
class AppearancePreferences(context: Context) {
    private val preferences = AppPrefs.Appearance.sharedPreferences(context.applicationContext)
    private val platformPreferences = PlatformPreferences(preferences)
    private val store = AppearanceSettingsStore(platformPreferences)

    fun load(): AppearanceSettings = store.load()

    /** One edit, so listeners never see a half-saved appearance. */
    fun save(settings: AppearanceSettings) {
        platformPreferences.edit { store.save(settings) }
    }

    fun reset() = save(AppearanceSettings())

    fun registerListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        preferences.registerOnSharedPreferenceChangeListener(listener)
    }

    fun unregisterListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        preferences.unregisterOnSharedPreferenceChangeListener(listener)
    }
}

@Composable
fun rememberAppearanceSettings(preferences: AppearancePreferences): State<AppearanceSettings> {
    val state = remember(preferences) { mutableStateOf(preferences.load()) }
    DisposableEffect(preferences) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            state.value = preferences.load()
        }
        preferences.registerListener(listener)
        onDispose { preferences.unregisterListener(listener) }
    }
    return state
}
