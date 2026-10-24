package com.fersaiyan.cyanbridge.agent

import android.content.Context
import com.fersaiyan.cyanbridge.BuildConfig
import com.fersaiyan.cyanbridge.ai.router.AiProviderPrefs
import com.fersaiyan.cyanbridge.shared.billing.BillingProvider
import com.fersaiyan.cyanbridge.shared.billing.ProAccount
import com.fersaiyan.cyanbridge.shared.billing.ProAccountPlatform
import com.fersaiyan.cyanbridge.shared.billing.ProBetaSignup
import com.fersaiyan.cyanbridge.shared.billing.ProCancelResult
import com.fersaiyan.cyanbridge.shared.billing.ProDonation
import com.fersaiyan.cyanbridge.shared.billing.ProDonationStatus
import com.fersaiyan.cyanbridge.shared.billing.ProEmailVerification
import com.fersaiyan.cyanbridge.shared.billing.ProEntitlementStatus
import com.fersaiyan.cyanbridge.shared.billing.ProEntitlementStore
import com.fersaiyan.cyanbridge.shared.billing.ProLiveMode
import com.fersaiyan.cyanbridge.shared.billing.ProModelCatalog
import com.fersaiyan.cyanbridge.shared.billing.ProModelOption
import com.fersaiyan.cyanbridge.shared.billing.ProQuota
import com.fersaiyan.cyanbridge.shared.billing.ProTrialActivation
import com.fersaiyan.cyanbridge.ui.debug.DebugLogSupport
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/** The Pro account services on Android: typed preferences, the verifier and the relay server. */
class AndroidProAccountPlatform(context: Context) : ProAccountPlatform {
    private val context = context.applicationContext

    override val entitlement = object : ProEntitlementStore {
        override var isSubscribed: Boolean
            get() = ProSubscriptionPrefs.isSubscribed(context)
            set(value) = ProSubscriptionPrefs.setSubscribed(context, value)
        override var plan: String
            get() = ProSubscriptionPrefs.getPlan(context)
            set(value) = ProSubscriptionPrefs.setPlan(context, value)
        override var expiresAt: Long
            get() = ProSubscriptionPrefs.getExpiresAt(context)
            set(value) = ProSubscriptionPrefs.setExpiresAt(context, value)
        override var purchaseToken: String
            get() = ProSubscriptionPrefs.getPurchaseToken(context)
            set(value) = ProSubscriptionPrefs.setPurchaseToken(context, value)
        override var provider: String
            get() = ProSubscriptionPrefs.getProvider(context)
            set(value) = ProSubscriptionPrefs.setProvider(context, value)
        override var lastVerifiedAt: Long
            get() = ProSubscriptionPrefs.getLastVerifiedAt(context)
            set(value) = ProSubscriptionPrefs.setLastVerifiedAt(context, value)

        override fun clear(provider: String, clearPurchaseToken: Boolean) =
            ProSubscriptionPrefs.clearEntitlement(context, provider, clearPurchaseToken)
    }

    private fun ProSubscriptionVerifier.Result.toStatus() = ProEntitlementStatus(active, plan, expiresAtMs, message)

    override fun localStatus() = ProSubscriptionVerifier.localStatus(context).toStatus()

    override fun verifyNow(strict: Boolean, applyActivationRouting: Boolean) =
        ProSubscriptionVerifier.verifyNow(context, strict, applyActivationRouting).toStatus()

    override fun forceStrictVerification(): Boolean {
        if (!BuildConfig.DEBUG) return false
        return ProSubscriptionPrefs.getProvider(context) in setOf("play_billing", "verification_required")
    }

    override fun applyActivationRouting(): String =
        ProSubscriptionRoutingPolicy.actionNote(ProSubscriptionRoutingPolicy.applyAfterActivation(context))

    override fun hasVerifyUrl() =
        ProSubscriptionServerPrefs.getVerifyUrl(context).ifBlank { BuildConfig.PRO_SUB_VERIFY_URL }.isNotBlank()

    override fun isWebCheckoutEnabled() = SubscriptionCheckoutPolicy.isWebCheckoutEnabled(context)

    private fun relayBaseUrl() = AiProviderPrefs.getRelayBaseUrl(context).trimEnd('/')

    override fun relayConfigured() = relayBaseUrl().isNotBlank()

    override fun relayUnavailableHint(error: Throwable) = ProSubscriptionRelayClient.relayUnavailableHint(error)

    override fun accountEmail() = ProSubscriptionServerPrefs.getAccountEmail(context)
    override fun setAccountEmail(email: String) = ProSubscriptionServerPrefs.setAccountEmail(context, email)
    override fun normalizeAccountEmail(raw: String) = ProSubscriptionServerPrefs.normalizeAccountEmail(raw)
    override fun isValidAccountEmail(email: String) = ProSubscriptionServerPrefs.isUsableAccountEmail(email)
    override fun isAccountEmailVerified(email: String) = ProSubscriptionServerPrefs.isAccountEmailVerified(context, email)
    override fun setVerifiedAccountEmail(email: String) = ProSubscriptionServerPrefs.setVerifiedAccountEmail(context, email)
    override fun apiToken() = ProSubscriptionServerPrefs.getApiToken(context)

    override fun fetchAccount() = ProSubscriptionRelayClient.fetchAccountInfo(context).map {
        ProAccount(it.apiToken, it.email, it.emailVerified, it.plan, it.subscriptionStatus)
    }

    override fun requestEmailVerification(email: String) =
        ProSubscriptionRelayClient.requestAccountEmailVerification(context, email).map {
            ProEmailVerification(it.isEmailMatchFallback, it.message, it.verificationUrl)
        }

    override fun verifyEmailCode(code: String) =
        ProSubscriptionRelayClient.verifyAccountEmailCode(context, code).map { }

    override fun cancelSubscription() = ProSubscriptionRelayClient.cancelSubscription(context).map {
        ProCancelResult(it.active, it.plan, it.expiresAtMs, it.message)
    }

    override fun createWebCheckout(plan: String, provider: BillingProvider, changePlan: Boolean) = runCatching {
        val callbackResult = ProSubscriptionServerPrefs.createWebCallbackResult(
            context = context,
            purpose = ProSubscriptionServerPrefs.WebCallbackPurpose.SUBSCRIPTION,
        )
        ProSubscriptionRelayClient.createWebCheckoutSession(
            context = context,
            plan = plan,
            provider = provider,
            returnUrl = SubscriptionCheckoutPolicy.createVerifiedCallbackUrl(callbackResult),
            changePlan = changePlan,
        ).getOrThrow()
    }

    /** The server account token, fetched from the relay when none is stored yet. */
    private fun serverToken(): String = ProSubscriptionServerPrefs.getApiToken(context).ifBlank {
        ProSubscriptionRelayClient.fetchAccountInfo(context).getOrThrow().apiToken.trim()
    }

    override fun activateTrial(email: String): ProTrialActivation {
        val token = serverToken()
        check(token.isNotBlank()) { "Server account token unavailable" }

        val conn = URL("${relayBaseUrl()}/pro/activate-trial").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 10000
        conn.readTimeout = 15000
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Authorization", "Bearer $token")
        conn.doOutput = true
        conn.outputStream.write(JSONObject().put("email", email).toString().toByteArray())

        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val responseBody = (stream ?: conn.inputStream).bufferedReader().use { it.readText() }
        conn.disconnect()

        if (code !in 200..299) {
            val message = runCatching { JSONObject(responseBody).optString("error") }.getOrDefault("")
                .ifBlank { runCatching { JSONObject(responseBody).optString("message") }.getOrDefault("") }
                .ifBlank { "HTTP $code" }
            throw IllegalStateException(message)
        }

        val json = JSONObject(responseBody)
        return ProTrialActivation(
            ok = json.optBoolean("ok", false),
            message = json.optString("message", "Unknown response"),
            expiresAtMs = json.optLong("expires_at_ms", 0L),
            plan = json.optString("plan", "free_trial"),
        )
    }

    override suspend fun sendRestoreFailureLogs(email: String): Result<String> {
        val localStatus = ProSubscriptionVerifier.localStatus(context)
        return DebugLogSupport.sendLogsToServer(
            context = context,
            issueType = "pro_subscription_restore_failed",
            description = "User tried to recover an existing Pro subscription for $email, but no active subscription was found after email verification.",
            logs = DebugLogSupport.collectLogcat(),
            deviceInfo = DebugLogSupport.buildDeviceInfo(
                context = context,
                extraInfo = linkedMapOf(
                    "Pro recovery email" to email,
                    "Recovery result" to "No active subscription found",
                    "Local subscription active" to localStatus.active.toString(),
                    "Local subscription plan" to localStatus.plan,
                    "Local subscription provider" to ProSubscriptionPrefs.getProvider(context),
                ),
            ),
            contactEmail = email,
            requestMetadata = "Pro recovery failed for verified email: $email",
        )
    }

    override fun createDonation(amount: String, email: String): ProDonation {
        val apiToken = serverToken()
        val callbackResult = ProSubscriptionServerPrefs.createWebCallbackResult(
            context = context,
            purpose = ProSubscriptionServerPrefs.WebCallbackPurpose.DONATION,
        )
        val callbackUrl = SubscriptionCheckoutPolicy.createVerifiedCallbackUrl(callbackResult)

        val conn = URL("${relayBaseUrl()}/api/donate").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 15000
        conn.readTimeout = 20000
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Accept", "application/json")
        conn.doOutput = true
        val requestBody = JSONObject()
            .put("email", email)
            .put("amount", amount.toIntOrNull() ?: 5)
            .put("return_url", callbackUrl)
            .put("api_token", apiToken)
            .toString()
        conn.outputStream.write(requestBody.toByteArray())

        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        conn.disconnect()

        if (code !in 200..299) {
            val msg = runCatching { JSONObject(body).optString("message") }.getOrDefault("HTTP $code")
            throw IllegalStateException(msg)
        }

        val json = JSONObject(body)
        val invoiceUrl = json.optString("invoice_url").trim()
        val statusUrl = json.optString("status_url").trim()
        if (invoiceUrl.isBlank() || statusUrl.isBlank()) {
            throw IllegalStateException("Donation setup did not return checkout links.")
        }
        PendingDonationPrefs.save(context, PendingDonationPrefs.PendingDonation(invoiceUrl, statusUrl, amount))
        return ProDonation(invoiceUrl, statusUrl, amount)
    }

    override fun checkDonation(donation: ProDonation): ProDonationStatus {
        val conn = URL(donation.statusUrl).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 15000
        conn.readTimeout = 20000
        conn.setRequestProperty("Accept", "application/json")
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val json = JSONObject(stream?.bufferedReader()?.use { it.readText() }.orEmpty().ifBlank { "{}" })
        if (code !in 200..299) {
            throw IllegalStateException(json.optString("message", "Unable to verify donation yet."))
        }
        val confirmed = json.optBoolean("confirmed", false)
        return ProDonationStatus(
            confirmed = confirmed,
            message = json.optString("message", if (confirmed) "Donation confirmed. Thank you!" else "Donation pending."),
        )
    }

    override fun pendingDonation() = PendingDonationPrefs.get(context)?.let { ProDonation(it.invoiceUrl, it.statusUrl, it.amount) }
    override fun isAwaitingDonationReturn() = PendingDonationPrefs.isAwaitingReturn(context)
    override fun setAwaitingDonationReturn(awaiting: Boolean) = PendingDonationPrefs.setAwaitingReturn(context, awaiting)
    override fun clearDonation() = PendingDonationPrefs.clear(context)

    override fun fetchQuota(model: String) = ProSubscriptionRelayClient.fetchQuota(context, model).map {
        ProQuota(it.used, it.limit, it.remaining, it.resetAtMs, it.model)
    }

    override fun fetchModelCatalog() = ProSubscriptionRelayClient.fetchModelCatalog(context).map { catalog ->
        ProModelCatalog(
            models = catalog.models.map { ProModelOption(it.id, it.label, it.supportsVision) },
            liveModes = catalog.liveModes.map { it.toShared() },
        )
    }

    override fun registerBetaCloudInterest() = ProSubscriptionRelayClient.registerBetaCloudInterest(context).map {
        ProBetaSignup(it.message, it.interestedCount)
    }
}

internal fun ProSubscriptionRelayClient.LiveModeOption.toShared() = ProLiveMode(id, displayLabel, description)
