package com.fersaiyan.cyanbridge.shared.billing

import com.fersaiyan.cyanbridge.shared.platform.platformCurrentTimeMillis

/** The locally stored Pro entitlement (plan, expiry and where it came from). */
interface ProEntitlementStore {
    var isSubscribed: Boolean
    var plan: String
    var expiresAt: Long
    var purchaseToken: String
    var provider: String
    var lastVerifiedAt: Long

    fun clear(provider: String, clearPurchaseToken: Boolean)
}

fun ProEntitlementStore.isActiveLocally(nowMs: Long = platformCurrentTimeMillis()): Boolean =
    isSubscribed && (expiresAt <= 0L || expiresAt > nowMs)

/** Paid plans (not the free trial) unlock the direct Gemini Live route. */
fun ProEntitlementStore.hasPaidPlan(): Boolean =
    isActiveLocally() && plan.lowercase() in setOf("cheap", "standard", "max")

/** Stores a server cancellation answer as the new entitlement. */
fun ProEntitlementStore.applyCancellation(cancel: ProCancelResult) {
    isSubscribed = cancel.active
    plan = cancel.plan
    expiresAt = cancel.expiresAtMs
    provider = "server_verified"
    lastVerifiedAt = platformCurrentTimeMillis()
    if (!cancel.active) purchaseToken = ""
}

data class ProEntitlementStatus(
    val active: Boolean,
    val plan: String,
    val expiresAtMs: Long,
    val message: String,
)

data class ProAccount(
    val apiToken: String,
    val email: String,
    val emailVerified: Boolean,
    val plan: String,
    val subscriptionStatus: String,
)

data class ProEmailVerification(
    /** The server matched the email to the account without sending a code. */
    val emailMatched: Boolean,
    val message: String,
    val verificationUrl: String?,
)

data class ProCancelResult(
    val active: Boolean,
    val plan: String,
    val expiresAtMs: Long,
    val message: String,
)

data class ProTrialActivation(
    val ok: Boolean,
    val message: String,
    val expiresAtMs: Long,
    val plan: String,
)

data class ProDonation(
    val invoiceUrl: String,
    val statusUrl: String,
    val amount: String,
)

data class ProDonationStatus(val confirmed: Boolean, val message: String)

data class ProQuota(
    val used: Int,
    val limit: Int,
    val remaining: Int,
    val resetAtMs: Long,
    val model: String,
)

data class ProModelOption(val id: String, val label: String, val supportsVision: Boolean)

data class ProLiveMode(val id: String, val displayLabel: String, val description: String)

data class ProModelCatalog(val models: List<ProModelOption>, val liveModes: List<ProLiveMode>)

data class ProBetaSignup(val message: String, val interestedCount: Int?)

/**
 * The Pro account services the subscription screens use: the local entitlement, the account
 * email and the CyanBridge server. Server calls block; ViewModels run them off the main thread.
 */
interface ProAccountPlatform {
    val entitlement: ProEntitlementStore

    fun localStatus(): ProEntitlementStatus
    fun verifyNow(strict: Boolean, applyActivationRouting: Boolean = false): ProEntitlementStatus

    /** Debug builds verify Play and verification-pending entitlements strictly. */
    fun forceStrictVerification(): Boolean

    /** Makes Pro the AI provider after an activation; returns a note when that changed anything. */
    fun applyActivationRouting(): String
    fun hasVerifyUrl(): Boolean
    fun isWebCheckoutEnabled(): Boolean
    fun relayConfigured(): Boolean
    fun relayUnavailableHint(error: Throwable): String?

    fun accountEmail(): String
    fun setAccountEmail(email: String)
    fun normalizeAccountEmail(raw: String): String
    fun isValidAccountEmail(email: String): Boolean
    fun isAccountEmailVerified(email: String): Boolean
    fun setVerifiedAccountEmail(email: String)
    fun apiToken(): String

    fun fetchAccount(): Result<ProAccount>
    fun requestEmailVerification(email: String): Result<ProEmailVerification>
    fun verifyEmailCode(code: String): Result<Unit>
    fun cancelSubscription(): Result<ProCancelResult>
    fun createWebCheckout(plan: String, provider: BillingProvider, changePlan: Boolean): Result<String>
    fun activateTrial(email: String): ProTrialActivation
    suspend fun sendRestoreFailureLogs(email: String): Result<String>

    fun createDonation(amount: String, email: String): ProDonation
    fun checkDonation(donation: ProDonation): ProDonationStatus
    fun pendingDonation(): ProDonation?
    fun isAwaitingDonationReturn(): Boolean
    fun setAwaitingDonationReturn(awaiting: Boolean)
    fun clearDonation()

    fun fetchQuota(model: String): Result<ProQuota>
    fun fetchModelCatalog(): Result<ProModelCatalog>
    fun registerBetaCloudInterest(): Result<ProBetaSignup>
}

/**
 * The app store's subscription billing (Google Play on Android). Callbacks may arrive on any
 * thread.
 */
interface ProStoreBilling {
    interface Listener {
        fun onReady()
        fun onPurchasesUpdated(purchases: List<ProStorePurchase>)
        fun onError(message: String)
    }

    fun start(listener: Listener)

    /** Localized price labels by plan, and the plans whose configured offer can be bought. */
    fun queryOffers(onResult: (prices: Map<String, String>, purchasablePlans: Set<String>) -> Unit)

    /** Reports the store's current purchases; failed queries report nothing. */
    fun queryActivePurchases(onResult: (List<ProStorePurchase>) -> Unit)
    fun acknowledge(purchase: ProStorePurchase)
    fun canPurchase(plan: String): Boolean

    /** Opens the store's purchase sheet over [host], the platform's screen host. */
    fun launchPurchase(host: Any, plan: String)
    fun destroy()
}

data class ProStorePurchase(
    val purchased: Boolean,
    /** The plan of the first product this app knows, or blank. */
    val plan: String,
    val purchaseToken: String,
    val handle: Any? = null,
)
