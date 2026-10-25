package com.fersaiyan.cyanbridge.shared.prefs

import com.fersaiyan.cyanbridge.ai.image.ImageThumbnailQuality
import com.fersaiyan.cyanbridge.shared.ai.AiProviderType
import com.fersaiyan.cyanbridge.shared.ai.CliRelayBackend
import com.fersaiyan.cyanbridge.shared.ai.DEFAULT_RELAY_URL
import com.fersaiyan.cyanbridge.shared.glasses.AiWakeWordRoute

/** The iOS app keeps its glasses settings together in this suite. */
internal const val IOS_GLASSES_SUITE = "cyanbridge_ios_glasses"
private const val IOS_REMOTE_OPENAI_SUITE = "cyanbridge_remote_openai"

/** Assistant routing, wake word, image questions, language and remote model server. */
object AssistantPrefs {
    object Provider : PrefsFile("ai_provider_prefs") {
        val provider = string("provider", AiProviderType.CLI_RELAY.wire)
        val relayBaseUrl = string("relay_base_url", DEFAULT_RELAY_URL)
        val relayBackend = string("relay_backend", CliRelayBackend.GEMINI.wire)
    }

    object WakeWord : PrefsFile("ai_wake_word") {
        val route = optionalString("route", iosFile = IOS_GLASSES_SUITE, iosKey = "ai_wake_word_route")
            .map(AiWakeWordRoute::fromRaw) { it.name }
    }

    /** Android reader: ai/vision/ImageQuestionPreferences. */
    object ImageQuestions : PrefsFile("image_questions") {
        val defaultQuestion = optionalString("default_question")
        val thumbnailQuality = int(
            "thumbnail_quality",
            ImageThumbnailQuality.CLEARER.sdkValue,
            iosFile = IOS_GLASSES_SUITE,
            iosKey = "image_thumbnail_quality",
        )
        val legacyVisionProfileMigrated = boolean("legacy_vision_profile_migrated", false)
    }

    /** Replaced by [ImageQuestions.defaultQuestion]; read once for migration. */
    object LegacyVisionProfile : PrefsFile("vision_profile") {
        val customInstructions = optionalString("custom_instructions")
    }

    /** Stores the AppLanguage enum name; absent means the system language. */
    object Language : PrefsFile("app_language", iosName = "cyanbridge_app_language") {
        val selected = optionalString("selected_language")
    }

    object GeminiLive : PrefsFile("gemini_live") {
        /** Economy is the default Live route for Pro users. */
        val proEconomy = boolean("pro_economy", true)
        val imageDelaySeconds = int("live_image_delay", 0)
    }

    /** Custom transcription endpoint of old builds, with a plaintext API key; deleted at startup. */
    object LegacyTranscriptionEndpoint : PrefsFile("transcription_endpoint")

    /**
     * OpenAI-compatible server behind the Local provider. The API key is in [RemoteOpenAiSecrets]
     * on Android and in the Keychain on iOS.
     */
    object RemoteOpenAi : PrefsFile("remote_openai_prefs") {
        val baseUrl = string("base_url", "", iosFile = IOS_REMOTE_OPENAI_SUITE)
        /** Plaintext copy written by old builds; moved to [RemoteOpenAiSecrets] on first read. */
        val legacyApiKey = string("api_key", "")
        val model = string("model", "", iosFile = IOS_REMOTE_OPENAI_SUITE)
        val enabled = boolean("enabled", false, iosFile = IOS_REMOTE_OPENAI_SUITE)
        val studioBridgeEnabled = boolean("studio_bridge_enabled", false)
    }

    object RemoteOpenAiSecrets : PrefsFile("remote_openai_secrets", secure = true) {
        val apiKey = string("api_key", "")
    }

    val files: List<PrefsFile> = listOf(
        Provider, WakeWord, ImageQuestions, LegacyVisionProfile, Language, GeminiLive,
        LegacyTranscriptionEndpoint, RemoteOpenAi, RemoteOpenAiSecrets,
    )
}
