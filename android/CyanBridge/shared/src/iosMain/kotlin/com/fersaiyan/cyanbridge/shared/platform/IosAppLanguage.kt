package com.fersaiyan.cyanbridge.shared.platform

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.fersaiyan.cyanbridge.shared.ui.onboarding.OnboardingLanguageOption
import platform.Foundation.NSUserDefaults

/**
 * In-app language choice (Android: AppLanguage + AppLanguagePreferences).
 * Compose resources read the locale from NSLocale.preferredLanguages, which
 * follows the "AppleLanguages" default, so the host re-keys its content on
 * [selectedId] to reload every string without restarting the app.
 */
object IosAppLanguage {
    private class Language(val id: String, val tag: String, val label: String)

    private val languages = listOf(
        Language("SYSTEM", "", "System default"),
        Language("ENGLISH", "en", "English"),
        Language("PORTUGUESE_BRAZIL", "pt-BR", "Português (Brasil)"),
        Language("SPANISH", "es", "Español"),
        Language("GERMAN", "de", "Deutsch"),
        Language("FRENCH", "fr", "Français"),
        Language("ITALIAN", "it", "Italiano"),
        Language("CHINESE_SIMPLIFIED", "zh-CN", "中文（简体）"),
        Language("KOREAN", "ko", "한국어"),
        Language("RUSSIAN", "ru", "Русский"),
    )

    private const val KEY_LANGUAGE = "selected_language"
    private const val APPLE_LANGUAGES = "AppleLanguages"
    private val preferences = createPlatformPreferences("cyanbridge_app_language")

    var selectedId: String by mutableStateOf(
        preferences.getString(KEY_LANGUAGE, "SYSTEM").takeIf { id -> languages.any { it.id == id } } ?: "SYSTEM",
    )
        private set

    val options: List<OnboardingLanguageOption>
        get() = languages.map { OnboardingLanguageOption(id = it.id, label = it.label) }

    val selectedLabel: String
        get() = languages.first { it.id == selectedId }.label

    fun select(id: String) {
        val language = languages.firstOrNull { it.id == id } ?: return
        preferences.putString(KEY_LANGUAGE, language.id)
        val defaults = NSUserDefaults.standardUserDefaults
        if (language.tag.isEmpty()) {
            defaults.removeObjectForKey(APPLE_LANGUAGES)
        } else {
            defaults.setObject(listOf(language.tag), APPLE_LANGUAGES)
        }
        selectedId = language.id
    }
}
