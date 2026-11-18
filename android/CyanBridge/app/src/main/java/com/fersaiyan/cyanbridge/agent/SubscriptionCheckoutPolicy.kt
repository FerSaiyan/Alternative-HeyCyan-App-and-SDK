package com.fersaiyan.cyanbridge.agent

import android.content.Context
import android.net.Uri
import com.fersaiyan.cyanbridge.BuildConfig
import com.fersaiyan.cyanbridge.ai.router.AiProviderPrefs
import java.util.Locale

object SubscriptionCheckoutPolicy {
    const val CALLBACK_RESULT_PARAMETER = "result"

    private const val CALLBACK_SCHEME = "https"
    private const val CALLBACK_HOST = "cyanbridgelabs.com"
    private const val CALLBACK_PATH = "/web-subscribe/callback"
    private const val EMAIL_VERIFICATION_SCHEME = "fersaiyan"
    private const val EMAIL_VERIFICATION_HOST = "pro-sub"
    private const val EMAIL_VERIFICATION_PATH = "/restore"

    fun resolveWebCheckoutUrl(context: Context): String {
        val configured = BuildConfig.WEB_SUBSCRIBE_URL.trim()
        if (configured.isNotBlank()) return configured

        val relayBase = AiProviderPrefs.getRelayBaseUrl(context).trim().trimEnd('/')
        if (!relayBase.startsWith("http://") && !relayBase.startsWith("https://")) return ""
        return "$relayBase/web-subscribe"
    }

    fun isWebCheckoutEnabled(context: Context): Boolean {
        val url = resolveWebCheckoutUrl(context)
        if (url.isBlank()) return false

        val allowedCsv = BuildConfig.WEB_SUB_ALLOWED_COUNTRIES.trim()
        if (allowedCsv.isBlank()) {
            // Safe default for development: when URL exists and no country list is configured,
            // show both paths so integrations can be tested.
            return true
        }

        val allowed = allowedCsv
            .split(',')
            .map { it.trim().uppercase(Locale.US) }
            .filter { it.isNotBlank() }
            .toSet()

        if (allowed.isEmpty()) return false

        val country = context.resources.configuration.locales[0]?.country
            ?.uppercase(Locale.US)
            ?.trim()
            .orEmpty()

        return country in allowed
    }

    fun isGooglePlayCheckoutAllowed(
        changePlanRequested: Boolean,
        currentPlan: String,
        isSubscribed: Boolean,
    ): Boolean = !changePlanRequested || !isSubscribed || currentPlan == "free_trial"

    fun checkoutSessionEndpoint(checkoutPageUrl: String): String {
        val checkoutPageUri = Uri.parse(checkoutPageUrl.trim())
        require(checkoutPageUri.scheme != null && checkoutPageUri.host != null) {
            "Website checkout URL is invalid"
        }
        return checkoutPageUri.buildUpon()
            .path("/api/billing/checkout-sessions")
            .clearQuery()
            .fragment(null)
            .build()
            .toString()
    }

    fun isExpectedCheckoutSessionUrl(checkoutPageUrl: String, checkoutUrl: String): Boolean {
        val checkoutPageUri = Uri.parse(checkoutPageUrl.trim())
        val checkoutUri = Uri.parse(checkoutUrl.trim())
        return checkoutUri.scheme?.equals(checkoutPageUri.scheme, ignoreCase = true) == true &&
            checkoutUri.host?.equals(checkoutPageUri.host, ignoreCase = true) == true &&
            checkoutUri.port == checkoutPageUri.port &&
            checkoutUri.path == checkoutPageUri.path &&
            checkoutUri.userInfo == null &&
            checkoutUri.fragment == null &&
            checkoutUri.getQueryParameter("api_token") == null &&
            checkoutUri.getQueryParameter("checkout_session").isNullOrBlank().not()
    }

    fun createVerifiedCallbackUrl(result: String): String {
        require(result.isNotBlank()) { "Checkout callback result is required" }
        return Uri.Builder()
            .scheme(CALLBACK_SCHEME)
            .authority(CALLBACK_HOST)
            .path(CALLBACK_PATH)
            .appendQueryParameter(CALLBACK_RESULT_PARAMETER, result)
            .build()
            .toString()
    }

    fun callbackResultFrom(uri: Uri?): String? {
        val callbackUri = uri ?: return null
        if (!isTrustedCallbackUri(callbackUri)) return null
        return callbackUri.getQueryParameter(CALLBACK_RESULT_PARAMETER)?.trim()?.ifBlank { null }
    }

    fun isEmailVerificationReturn(uri: Uri?): Boolean {
        val returnUri = uri ?: return false
        return returnUri.scheme.equals(EMAIL_VERIFICATION_SCHEME, ignoreCase = true) &&
            returnUri.host.equals(EMAIL_VERIFICATION_HOST, ignoreCase = true) &&
            returnUri.path == EMAIL_VERIFICATION_PATH &&
            returnUri.port == -1 &&
            returnUri.userInfo == null
    }

    private fun isTrustedCallbackUri(uri: Uri?): Boolean {
        val callbackUri = uri ?: return false
        return callbackUri.scheme.equals(CALLBACK_SCHEME, ignoreCase = true) &&
            callbackUri.host.equals(CALLBACK_HOST, ignoreCase = true) &&
            callbackUri.path == CALLBACK_PATH &&
            callbackUri.port == -1 &&
            callbackUri.userInfo == null
    }
}
