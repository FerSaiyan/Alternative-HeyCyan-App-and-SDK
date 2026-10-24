package com.fersaiyan.cyanbridge.shared.appearance

import com.fersaiyan.cyanbridge.shared.platform.PlatformPreferences
import com.fersaiyan.cyanbridge.shared.prefs.AppPrefs.Appearance
import com.fersaiyan.cyanbridge.shared.prefs.get
import com.fersaiyan.cyanbridge.shared.prefs.set

/**
 * Persists the platform-neutral appearance choices in [Appearance]. Platforms can disable
 * dynamic color when their UI toolkit does not provide it.
 */
class AppearanceSettingsStore(
    private val preferences: PlatformPreferences,
    private val dynamicColorAvailable: Boolean = true,
) {
    fun load(): AppearanceSettings {
        val themeMode = runCatching {
            ThemeMode.valueOf(preferences[Appearance.themeMode])
        }.getOrDefault(ThemeMode.SYSTEM)
        return AppearanceSettings(
            themeMode = themeMode,
            accentProfileId = AccentProfiles.find(preferences[Appearance.accentProfile]).id,
            useDynamicColor = preferences[Appearance.dynamicColor] && dynamicColorAvailable,
            highContrast = preferences[Appearance.highContrast],
        )
    }

    fun save(settings: AppearanceSettings) {
        val normalized = settings.copy(
            accentProfileId = AccentProfiles.find(settings.accentProfileId).id,
            useDynamicColor = settings.useDynamicColor && dynamicColorAvailable,
        )
        preferences[Appearance.themeMode] = normalized.themeMode.name
        preferences[Appearance.accentProfile] = normalized.accentProfileId
        preferences[Appearance.dynamicColor] = normalized.useDynamicColor
        preferences[Appearance.highContrast] = normalized.highContrast
    }

    fun reset() = save(AppearanceSettings())
}
