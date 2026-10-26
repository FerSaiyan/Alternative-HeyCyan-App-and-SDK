package com.fersaiyan.cyanbridge.shared.accessibility

import com.fersaiyan.cyanbridge.shared.platform.PlatformPreferences
import com.fersaiyan.cyanbridge.shared.prefs.AppPrefs
import com.fersaiyan.cyanbridge.shared.prefs.get
import com.fersaiyan.cyanbridge.shared.prefs.set

/**
 * The user's answer to "Do you need vision assistance?".
 *
 * UNSET means the question was never answered, so the app may still offer it once when a screen
 * reader is running. DISABLED means the user declined, so the offer is not shown again; Settings
 * can still turn it on.
 */
enum class VisionAssistanceChoice {
    UNSET,
    ENABLED,
    DISABLED;

    companion object {
        fun fromRaw(raw: String?): VisionAssistanceChoice =
            entries.firstOrNull { it.name == raw?.trim()?.uppercase() } ?: UNSET
    }
}

/**
 * Stores the vision-assistance choice on the device only ([AppPrefs.Accessibility]). The choice
 * reveals a disability, which counts as sensitive personal data, so it must not be synced or sent
 * to analytics. It lives apart from the other shared settings so an unrelated save never turns
 * UNSET into DISABLED.
 */
object VisionAssistancePreferences {
    private val choicePref get() = AppPrefs.Accessibility.visionAssistanceChoice

    /** The platform store holding the choice. */
    fun store(): PlatformPreferences = AppPrefs.Accessibility.store()

    fun choice(preferences: PlatformPreferences): VisionAssistanceChoice =
        VisionAssistanceChoice.fromRaw(preferences[choicePref])

    fun isEnabled(preferences: PlatformPreferences): Boolean =
        choice(preferences) == VisionAssistanceChoice.ENABLED

    fun setEnabled(preferences: PlatformPreferences, enabled: Boolean) {
        val choice = if (enabled) VisionAssistanceChoice.ENABLED else VisionAssistanceChoice.DISABLED
        preferences[choicePref] = choice.name
    }

    /** Offer the mode only to screen-reader users who have not answered yet; never switch it silently. */
    fun shouldOffer(preferences: PlatformPreferences, screenReaderRunning: Boolean): Boolean =
        screenReaderRunning && choice(preferences) == VisionAssistanceChoice.UNSET
}
