package com.fersaiyan.cyanbridge.shared.prefs

/** Pro plan entitlement, Pro AI models, server account and donations. */
object ProPrefs {
    object Subscription : PrefsFile("pro_subscription_prefs") {
        val isSubscribed = boolean("is_subscribed", false)
        val plan = optionalString("plan").map({ it ?: "none" }, { it })
        val expiresAt = long("expires_at", 0L)
        val purchaseToken = optionalString("purchase_token")
        val provider = string("provider", "mock")
        val lastVerifiedAt = long("last_verified_at", 0L)
    }

    object Ai : PrefsFile("pro_subscription_ai_prefs") {
        val requestsModel = string("requests_model", "auto")
        val questionsModel = optionalString("questions_model")
        val tasksModel = string("tasks_model", "auto")
        val systemPrompt = optionalString("system_prompt")
        /** Relay models that accept audio input (lowercased ids), cached from /models. */
        val audioCapableModels = optionalStringSet("audio_capable_models")
        val audioCapableModelsAtMs = long("audio_capable_models_at_ms", 0L)
    }

    /** Settings screen toggles of the Pro plan. */
    object Settings : PrefsFile("pro_subscription_settings") {
        val cloudSync = boolean("cloud_sync", true)
        val prioritySupport = boolean("priority_support", true)
        val pluginRewards = boolean("plugin_rewards", true)
        val earlyAccessDevices = boolean("early_access_devices", true)
        val backupFrequencyIndex = int("backup_frequency_idx", 1)
        val supportChannelIndex = int("support_channel_idx", 0)
    }

    object Server : PrefsFile("pro_subscription_server_prefs") {
        val verifyUrl = string("verify_url", "")
        /** Plaintext copy written by old builds; moved to [ServerSecrets] on first read. */
        val legacyApiToken = string("api_token", "")
        val accountEmail = string("account_email", "")
        val verifiedAccountEmail = string("verified_account_email", "")
        val legacyCredentialsCleared = boolean("legacy_credentials_cleared", false)
    }

    object ServerSecrets : PrefsFile("pro_subscription_server_secrets", secure = true) {
        val apiToken = string("api_token", "")
        val webCallbackResult = string("web_callback_result", "")
        val webCallbackPurpose = string("web_callback_purpose", "")
        val webCallbackExpiresAtMs = long("web_callback_expires_at_ms", 0L)
    }

    /** Old checkout state; cleared once by the server prefs. */
    object LegacyCheckout : PrefsFile("pending_legacy_checkout_prefs")

    object Donation : PrefsFile("pending_donation_prefs") {
        val invoiceUrl = string("invoice_url", "")
        val statusUrl = string("status_url", "")
        val amount = string("amount", "")
        val awaitingReturn = boolean("awaiting_return", false)
        /** Credentials written by the legacy flow; only ever removed. */
        val legacyApiToken = optionalString("api_token")
        val legacyEmail = optionalString("email")
        val legacySubscriptionId = optionalString("subscription_id")
    }

    object DonationSecrets : PrefsFile("pending_donation_secrets", secure = true) {
        val invoiceUrl = string("invoice_url", "")
        val statusUrl = string("status_url", "")
        val amount = string("amount", "")
    }

    /** Pseudonymous installation id shared by analytics and Pro feedback. */
    object Feedback : PrefsFile("pro_feature_feedback") {
        val installationId = string("installation_id", "")
    }

    val files: List<PrefsFile> = listOf(
        Subscription, Ai, Settings, Server, ServerSecrets, LegacyCheckout, Donation, DonationSecrets, Feedback,
    )
}
