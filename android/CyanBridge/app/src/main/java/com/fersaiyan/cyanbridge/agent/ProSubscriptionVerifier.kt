package com.fersaiyan.cyanbridge.agent

import android.content.Context
import com.fersaiyan.cyanbridge.BuildConfig
import com.fersaiyan.cyanbridge.ai.router.AiProviderPrefs
import com.fersaiyan.cyanbridge.ai.router.RelayServerCapabilitiesClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

object ProSubscriptionVerifier {

    data class Result(
        val active: Boolean,
        val plan: String,
        val expiresAtMs: Long,
        val source: String,
        val message: String,
    )

    private val client = OkHttpClient()

    fun localStatus(context: Context, nowMs: Long = System.currentTimeMillis()): Result {
        val subscribed = ProSubscriptionPrefs.isSubscribed(context)
        val expiresAt = ProSubscriptionPrefs.getExpiresAt(context)
        val active = subscribed && (expiresAt <= 0L || expiresAt > nowMs)

        val msg = when {
            !subscribed -> "No active subscription"
            expiresAt > 0L && expiresAt <= nowMs -> "Subscription expired"
            else -> "Subscription active (local status)"
        }

        return Result(
            active = active,
            plan = ProSubscriptionPrefs.getPlan(context),
            expiresAtMs = expiresAt,
            source = "local",
            message = msg,
        )
    }

    fun verifyNow(
        context: Context,
        strictForTesting: Boolean = shouldStrictVerify(context),
    ): Result {
        val base = localStatus(context)
        if (ProSubscriptionPrefs.getProvider(context) == "play_billing") {
            ProSubscriptionPrefs.setLastVerifiedAt(context, System.currentTimeMillis())
            return base.copy(message = "Using local Play Billing status")
        }

        val url = resolveVerifyUrl(context)
        val token = ProSubscriptionPrefs.getPurchaseToken(context).trim()
        if (url.isBlank() || token.isBlank()) {
            if (strictForTesting && base.active) {
                return strictFailure(
                    context = context,
                    message = "Verification required in debug test mode (missing verify URL or token)",
                    fallbackPlan = base.plan,
                )
            }
            ProSubscriptionPrefs.setLastVerifiedAt(context, System.currentTimeMillis())
            return base.copy(message = "Using local status (server verifier not configured)")
        }

        val relayBase = AiProviderPrefs.getRelayBaseUrl(context).trim().trimEnd('/')
        if (relayBase.isNotBlank() && url.startsWith(relayBase)) {
            val caps = RelayServerCapabilitiesClient.get(context).getOrNull()
            if (caps != null && !caps.subscriptionVerify) {
                if (strictForTesting && base.active) {
                    return strictFailure(
                        context = context,
                        message = "Verification required in debug test mode (server does not support subscription verification)",
                        fallbackPlan = base.plan,
                    )
                }
                ProSubscriptionPrefs.setLastVerifiedAt(context, System.currentTimeMillis())
                return base.copy(message = "Using local status (server does not support subscription verification)")
            }
        }

        val serverToken = ProSubscriptionServerPrefs.getApiToken(context)

        return runCatching {
            val payload = JSONObject()
                .put("purchase_token", token)
                .put("package_name", context.packageName)
                .put("plan", ProSubscriptionPrefs.getPlan(context))
                .put("provider", ProSubscriptionPrefs.getProvider(context))

            val req = Request.Builder()
                .url(url)
                .apply {
                    if (serverToken.isNotBlank()) {
                        header("Authorization", "Bearer $serverToken")
                    }
                }
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(req).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IllegalStateException("verify_http_${response.code}")
                }

                val body = response.body?.string().orEmpty().ifBlank { "{}" }
                val json = JSONObject(body)
                val active = json.optBoolean("active", false)
                val plan = json.optString("plan", ProSubscriptionPrefs.getPlan(context))
                val expires = json.optLong("expires_at_ms", ProSubscriptionPrefs.getExpiresAt(context))

                ProSubscriptionPrefs.setSubscribed(context, active)
                ProSubscriptionPrefs.setPlan(context, plan)
                ProSubscriptionPrefs.setExpiresAt(context, expires)
                ProSubscriptionPrefs.setProvider(context, "server_verified")
                ProSubscriptionPrefs.setLastVerifiedAt(context, System.currentTimeMillis())

                Result(
                    active = active,
                    plan = plan,
                    expiresAtMs = expires,
                    source = "server",
                    message = if (active) "Subscription verified" else "Subscription inactive",
                )
            }
        }.getOrElse {
            if (strictForTesting && base.active) {
                return strictFailure(
                    context = context,
                    message = "Verification failed in debug test mode: ${it.message}",
                    fallbackPlan = base.plan,
                )
            }
            ProSubscriptionPrefs.setLastVerifiedAt(context, System.currentTimeMillis())
            base.copy(message = "Verification failed, using local status: ${it.message}")
        }
    }

    private fun strictFailure(
        context: Context,
        message: String,
        fallbackPlan: String,
    ): Result {
        ProSubscriptionPrefs.setSubscribed(context, false)
        ProSubscriptionPrefs.setExpiresAt(context, 0L)
        ProSubscriptionPrefs.setPlan(context, fallbackPlan.ifBlank { "none" })
        ProSubscriptionPrefs.setProvider(context, "verification_required")
        ProSubscriptionPrefs.setLastVerifiedAt(context, System.currentTimeMillis())
        return Result(
            active = false,
            plan = fallbackPlan.ifBlank { "none" },
            expiresAtMs = 0L,
            source = "strict",
            message = message,
        )
    }

    private fun shouldStrictVerify(context: Context): Boolean {
        if (!BuildConfig.DEBUG) return false
        return when (ProSubscriptionPrefs.getProvider(context)) {
            "play_billing", "debug_mock", "server_verified", "verification_required" -> true
            else -> false
        }
    }

    private fun resolveVerifyUrl(context: Context): String {
        val override = ProSubscriptionServerPrefs.getVerifyUrl(context)
        if (override.isNotBlank()) {
            return override
        }

        val buildUrl = BuildConfig.PRO_SUB_VERIFY_URL.trim()
        if (buildUrl.isNotBlank()) {
            return buildUrl
        }

        val relayBase = AiProviderPrefs.getRelayBaseUrl(context).trim().trimEnd('/')
        return if (relayBase.startsWith("http://") || relayBase.startsWith("https://")) {
            "$relayBase/pro/verify"
        } else {
            ""
        }
    }
}
