package com.fersaiyan.cyanbridge.shared.billing

enum class ProSubscriptionAction {
    SUBSCRIBE,
    DONATE,
}

/** Safe fallback used when a host has no verified checkout implementation. */
fun unavailableProSubscriptionStatus(action: ProSubscriptionAction): String = when (action) {
    ProSubscriptionAction.SUBSCRIBE ->
        "Subscription checkout is unavailable on this host. No payment was started and no Pro access was granted."
    ProSubscriptionAction.DONATE ->
        "Donations are unavailable on this host. No payment was started."
}

/** Platform-neutral summary state for the subscription checkout screen. */
data class ProSubscriptionUiState(
    val status: String = "Not subscribed",
    val selectedPlan: String = "free_trial",
    val webCheckoutAvailable: Boolean = false,
    val isSubscribed: Boolean = false,
    /** Localized Google Play labels queried for each configured subscription offer. */
    val playPriceLabels: Map<String, String> = emptyMap(),
    /** Plans whose configured Google Play offer is currently available for purchase. */
    val playCheckoutAvailablePlans: Set<String> = emptySet(),
    /** Web subscriptions can only be changed through a web checkout session. */
    val googlePlayCheckoutAllowed: Boolean = true,
)

/** Platform-neutral presentation state for the subscription settings screen. */
data class ProSubscriptionSettingsUiState(
    val planStatus: String = "Status: loading...",
    val plan: String = "Plan: -",
    val expires: String = "Expires: -",
    val verified: String = "Last verified: -",
    val accountEmail: String = "Email: -",
    val accountToken: String = "API token: -",
    val accountSubscription: String = "Subscription: -",
    val quotaStatus: String = "Quota: -",
    val quotaBreakdown: String = "",
    val quotaProgress: Int? = null,
    val betaStatus: String = "",
    val cloudSync: Boolean = true,
    val prioritySupport: Boolean = true,
    val pluginRewards: Boolean = true,
    val earlyAccessDevices: Boolean = true,
    val backupFrequencyIndex: Int = 1,
    val supportChannelIndex: Int = 0,
    val modelOptions: List<String> = emptyList(),
    val requestsModel: String = "",
    val questionsModel: String = "",
    val tasksModel: String = "",
    val systemPrompt: String = "",
)
