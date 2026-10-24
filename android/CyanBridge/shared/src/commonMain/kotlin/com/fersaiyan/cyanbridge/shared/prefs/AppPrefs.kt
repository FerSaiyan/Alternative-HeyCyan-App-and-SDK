package com.fersaiyan.cyanbridge.shared.prefs

import com.fersaiyan.cyanbridge.shared.appearance.AccentProfiles
import com.fersaiyan.cyanbridge.shared.appearance.ThemeMode
import com.fersaiyan.cyanbridge.shared.settings.AgentProviderType
import com.fersaiyan.cyanbridge.shared.settings.MemoryPrivacyMode

/** Onboarding, appearance, analytics, devices and app-wide screen state. */
object AppPrefs {
    object Main : PrefsFile("cyanbridge_prefs") {
        val onboardingCompleted = boolean("onboarding_completed", false)
        val onboardingModelId = optionalString("onboarding_model_id")
        val batteryGuideCompleted = boolean("battery_opt_guide_completed", false)
        val batteryGuideSuppressed = boolean("battery_opt_guide_suppress", false)
        val batteryGuideRemindAfter = long("battery_opt_guide_remind_after", 0L)
    }

    /** iOS only: the welcome screen was dismissed. */
    object IosOnboarding : PrefsFile("cyanbridge_onboarding") {
        val welcomeDone = boolean("welcome_done", false)
    }

    object Appearance : PrefsFile("appearance_settings", iosName = "cyanbridge_appearance") {
        val themeMode = string("theme_mode", ThemeMode.SYSTEM.name)
        val accentProfile = string("accent_profile", AccentProfiles.CYAN_ID)
        val dynamicColor = boolean("dynamic_color", false)
        val highContrast = boolean("high_contrast", false)
    }

    /** Android chat screens; unset colors follow the theme. */
    object ChatAppearance : PrefsFile("chat_appearance_prefs") {
        val userBubbleColor = optionalInt("user_bubble_color")
        val assistantBubbleColor = optionalInt("assistant_bubble_color")
        val wallpaperUri = string("chat_wallpaper_uri", "")
    }

    /** Shared chat screens: palette indexes, -1 for the theme color. */
    object SharedChatAppearance : PrefsFile("cyanbridge_chat_appearance") {
        val userBubble = int("user_bubble", -1)
        val assistantBubble = int("assistant_bubble", -1)
        val wallpaper = boolean("wallpaper", false)
    }

    /** Tags typed on shared note screens; `*` is the note id. */
    object NoteTags : PrefsFile("cyanbridge_note_tags") {
        val tags = stringFamily("*", "")
    }

    /** Settings of the shared settings screen (reader: ui/SharedDestinationScreen). */
    object SharedSettings : PrefsFile("cyanbridge_shared_settings") {
        val providerType = string("provider_type", AgentProviderType.PRO_SUBSCRIPTION.name)
        val memoryMode = string("memory_mode", MemoryPrivacyMode.PRIVATE_LOCAL.name)
        val syncExplicit = boolean("sync_explicit", true)
        val syncDaily = boolean("sync_daily", true)
        val syncOcr = boolean("sync_ocr", false)
        val syncDerived = boolean("sync_derived", false)
        val vaultLocked = boolean("vault_locked", false)
        val transcriptStorage = boolean("transcript_storage", true)
        val redactNames = boolean("redact_names", true)
        val fullTranscriptExports = boolean("full_transcript_exports", false)
        val defaultImageQuestion = string("default_image_question", "")
    }

    /** Product analytics consent and delivery state. */
    object Analytics : PrefsFile("cyanbridge_product_analytics") {
        val sharingEnabled = boolean("sharing_enabled", true)
        val sharingChoiceMade = boolean("sharing_choice_made", false)
        val lastHeartbeatDay = string("last_heartbeat_day", "")
        val acquisitionComplete = boolean("acquisition_complete", false)
        val pendingAcquisition = string("pending_acquisition", "")
    }

    /** Last selected glasses; `*` in overrides is the MAC with ':' replaced by '_'. */
    object DeviceProfile : PrefsFile("device_profile") {
        val lastMac = optionalString("last_mac")
        val lastName = optionalString("last_name")
        val lastDetectedClass = optionalString("last_detected_class")
        val lastSelectedClass = optionalString("last_selected_class")
        val lastUserOverridden = boolean("last_user_overridden", false)
        val classOverride = stringFamily("override_*", null)
    }

    object VersionCheck : PrefsFile("version_check") {
        val lastCheckTime = long("last_version_check_time", 0L)
        val remindedVersion = string("reminded_version", "")
    }

    /** Relay used for firmware patches; unset means the default relay. */
    object RelayServer : PrefsFile("relay_server") {
        val baseUrl = optionalString("base_url")
    }

    /** `*` is an Android permission that was requested at least once. */
    object MetaPermissionRequests : PrefsFile("meta_permission_requests") {
        val requested = booleanFamily("*", false)
    }

    /** Expanded sections of Settings (reader: ui/SettingsActivity); `*` is the section key. */
    object SettingsSections : PrefsFile("settings_sections") {
        val expanded = booleanFamily("*", true)
    }

    object LocalModelsSections : PrefsFile("local_models_sections") {
        val expanded = booleanFamily("section_*", false)
    }

    val files: List<PrefsFile> = listOf(
        Main, IosOnboarding, Appearance, ChatAppearance, SharedChatAppearance, NoteTags, SharedSettings,
        Analytics, DeviceProfile, VersionCheck, RelayServer, MetaPermissionRequests, SettingsSections,
        LocalModelsSections,
    )
}

/** Every preferences file the apps read or write. */
object AllPrefs {
    val files: List<PrefsFile> =
        AppPrefs.files + AssistantPrefs.files + AgentPrefs.files + ProPrefs.files +
            MemoryPrefs.files + MediaPrefs.files + PluginPrefs.files
}
