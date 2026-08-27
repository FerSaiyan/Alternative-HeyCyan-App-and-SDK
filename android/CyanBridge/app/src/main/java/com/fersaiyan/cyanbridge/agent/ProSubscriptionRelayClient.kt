package com.fersaiyan.cyanbridge.agent

import android.content.Context
import android.net.Uri
import com.fersaiyan.cyanbridge.BuildConfig
import com.fersaiyan.cyanbridge.ai.router.AiProviderPrefs
import com.fersaiyan.cyanbridge.shared.billing.BillingProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.net.UnknownHostException
import java.util.UUID

object ProSubscriptionRelayClient {
    data class ModelOption(
        val id: String,
        val label: String,
        val quotaMultiplier: Int,
        val supportsVision: Boolean = false,
    )

    data class QuotaInfo(
        val used: Int,
        val limit: Int,
        val remaining: Int,
        val resetAtMs: Long,
        val model: String,
    )

    data class BetaInterestResult(
        val accepted: Boolean,
        val interestedCount: Int?,
        val message: String,
    )

    data class AccountInfo(
        val apiToken: String,
        val email: String,
        val emailVerified: Boolean,
        val plan: String,
        val subscriptionStatus: String,
        val expiresAtMs: Long,
    )

    data class CancelResult(
        val active: Boolean,
        val plan: String,
        val expiresAtMs: Long,
        val message: String,
    )

    data class EmailVerificationResult(
        val method: String,
        val message: String,
        val verificationUrl: String? = null,
    ) {
        val isEmailMatchFallback: Boolean
            get() = method == "email_match"

        val hasDirectVerificationLink: Boolean
            get() = verificationUrl != null
    }

    private const val CONNECT_TIMEOUT_MS = 7000
    private const val READ_TIMEOUT_MS = 15000
    private const val RELAY_DOWN_HINT =
        "Server may be down or this app may need an update to use the new server address."

    private const val FEEDBACK_PREFS = "pro_feature_feedback"
    private const val KEY_INSTALLATION_ID = "installation_id"

    fun fetchAvailableModels(context: Context): Result<List<ModelOption>> = runCatching {
        val candidates = listOf("/models", "/v1/models")
        val seen = linkedMapOf<String, ModelOption>()

        for (path in candidates) {
            val parsed = runCatching {
                val json = requestGetJson(context, endpoint(context, path))
                parseModels(json)
            }.getOrDefault(emptyList())
            parsed.forEach { option ->
                val key = option.id.trim().lowercase()
                if (key.isNotBlank() && !seen.containsKey(key)) {
                    seen[key] = option
                }
            }
        }

        if (seen.isEmpty()) {
            throw IllegalStateException("No models returned by relay")
        }
        seen.values.toList()
    }

    fun fetchQuota(context: Context, model: String): Result<QuotaInfo> = runCatching {
        val encodedModel = URLEncoder.encode(model, Charsets.UTF_8.name())
        val getPaths = listOf(
            "/pro/quota?model=$encodedModel",
            "/quota?model=$encodedModel",
            "/usage/quota?model=$encodedModel",
            "/pro/quota",
            "/quota",
            "/usage/quota",
        )

        var lastError: Throwable? = null
        for (path in getPaths) {
            val res = runCatching {
                val payload = requestGetJson(context, endpoint(context, path))
                parseQuota(payload, model)
            }
            if (res.isSuccess) return@runCatching res.getOrThrow()
            lastError = res.exceptionOrNull()
        }

        val postPaths = listOf("/pro/quota", "/quota", "/usage/quota")
        for (path in postPaths) {
            val res = runCatching {
                val payload = requestPostJson(
                    context = context,
                    url = endpoint(context, path),
                    body = JSONObject().put("model", model)
                )
                parseQuota(payload, model)
            }
            if (res.isSuccess) return@runCatching res.getOrThrow()
            lastError = res.exceptionOrNull()
        }

        throw lastError ?: IllegalStateException("Quota endpoint unavailable")
    }

    fun registerBetaCloudInterest(context: Context): Result<BetaInterestResult> = runCatching {
        val installationId = installationId(context)
        val payload = JSONObject()
            .put("installation_id", installationId)
            .put("package_name", context.packageName)
            .put("plan", ProSubscriptionPrefs.getPlan(context))
            .put("provider", ProSubscriptionPrefs.getProvider(context))
            .put("requests_model", ProSubscriptionAiPrefs.getRequestsModel(context))
            .put("questions_model", ProSubscriptionAiPrefs.getQuestionsModel(context))
            .put("tasks_model", ProSubscriptionAiPrefs.getTasksModel(context))
            .put("platform", "android")

        val paths = listOf(
            "/pro/beta-cloud-interest",
            "/beta-cloud-interest",
            "/pro/beta-cloud/signup",
            "/beta-cloud/signup",
        )

        var lastError: Throwable? = null
        for (path in paths) {
            val res = runCatching {
                val json = requestPostJson(context, endpoint(context, path), payload)
                val accepted = json.optBoolean("accepted", true)
                val count = intOrNull(json, "interested_count")
                    ?: intOrNull(json, "count")
                    ?: intOrNull(json, "total_interested")
                val message = json.optString("message").ifBlank {
                    if (accepted) "Interest registered" else "Interest request rejected"
                }
                BetaInterestResult(
                    accepted = accepted,
                    interestedCount = count,
                    message = message,
                )
            }
            if (res.isSuccess) return@runCatching res.getOrThrow()
            lastError = res.exceptionOrNull()
        }

        throw lastError ?: IllegalStateException("Beta cloud interest endpoint unavailable")
    }

    fun fetchAccountInfo(context: Context): Result<AccountInfo> = runCatching {
        val existingToken = ProSubscriptionServerPrefs.getApiToken(context).trim()

        val payload = if (existingToken.isNotBlank()) {
            runCatching {
                requestGetJson(context, endpoint(context, "/auth/me"))
            }.getOrElse {
                requestPostJson(
                    context,
                    endpoint(context, "/auth/register"),
                    JSONObject().put("api_token", existingToken)
                )
            }
        } else {
            requestPostJson(context, endpoint(context, "/auth/register"), JSONObject())
        }

        parseAccount(payload).also { account ->
            if (account.apiToken.isNotBlank()) {
                ProSubscriptionServerPrefs.setApiToken(context, account.apiToken)
            }
            if (account.email.isNotBlank()) {
                ProSubscriptionServerPrefs.setAccountEmail(context, account.email)
                if (account.emailVerified) {
                    ProSubscriptionServerPrefs.setVerifiedAccountEmail(context, account.email)
                }
            }
        }
    }

    fun createWebCheckoutSession(
        context: Context,
        plan: String,
        provider: BillingProvider,
        returnUrl: String,
        changePlan: Boolean = false,
    ): Result<String> = runCatching {
        check(provider == BillingProvider.ASAAS || provider == BillingProvider.PADDLE) {
            "Website checkout requires Asaas or Paddle"
        }
        val apiToken = ProSubscriptionServerPrefs.getApiToken(context).trim().ifBlank {
            fetchAccountInfo(context).getOrThrow().apiToken.trim()
        }
        check(apiToken.isNotBlank()) { "Server account token unavailable" }

        check(SubscriptionCheckoutPolicy.callbackResultFrom(Uri.parse(returnUrl)) != null) {
            "Checkout must return through the verified app link"
        }
        val checkoutPageUrl = SubscriptionCheckoutPolicy.resolveWebCheckoutUrl(context).trim()
        val checkoutPageUri = Uri.parse(checkoutPageUrl)
        check(
            checkoutPageUri.scheme?.equals("https", ignoreCase = true) == true ||
                (BuildConfig.DEBUG && checkoutPageUri.scheme?.equals("http", ignoreCase = true) == true),
        ) {
            "Website checkout must use HTTPS"
        }

        val checkoutSessionEndpoint = SubscriptionCheckoutPolicy.checkoutSessionEndpoint(checkoutPageUrl)
        val response = requestPostJson(
            context = context,
            url = checkoutSessionEndpoint,
            body = JSONObject()
                .put("plan", plan)
                .put("provider", provider.wireName)
                .put("return_url", returnUrl)
                .put("change_plan", changePlan),
        )
        val checkoutUrl = response.optString("checkout_url").trim()
        check(checkoutUrl.isNotBlank()) { "Checkout session did not return a checkout URL" }

        check(SubscriptionCheckoutPolicy.isExpectedCheckoutSessionUrl(checkoutPageUrl, checkoutUrl)) {
            "Checkout session returned an unexpected checkout URL"
        }
        checkoutUrl
    }

    fun requestAccountEmailVerification(context: Context, email: String): Result<EmailVerificationResult> = runCatching {
        val apiToken = ProSubscriptionServerPrefs.getApiToken(context).trim().ifBlank {
            fetchAccountInfo(context).getOrThrow().apiToken.trim()
        }
        check(apiToken.isNotBlank()) { "Server account token unavailable" }
        val payload = requestPostJson(
            context = context,
            url = endpoint(context, "/api/auth/relay/access-link"),
            body = JSONObject().put("email", email),
        )
        check(payload.optBoolean("ok", false)) { payload.optString("message", "Unable to send verification email.") }
        val verificationUrl = payload.optString("verification_url").trim().ifBlank { null }
        check(verificationUrl == null || verificationUrl.startsWith("https://")) {
            "The server returned an invalid verification link."
        }
        val method = payload.optString("verification_method").trim().ifBlank {
            if (verificationUrl != null) "direct_link" else "magic_link"
        }
        check(method == "magic_link" || method == "email_match" || method == "direct_link") {
            "Unsupported email verification response."
        }
        if (method == "email_match") {
            check(payload.optString("email").trim().equals(email.trim(), ignoreCase = true)) {
                "The server confirmed a different email address."
            }
        }
        EmailVerificationResult(
            method = method,
            message = payload.optString("message", "Check your email to verify this CyanBridge account."),
            verificationUrl = verificationUrl,
        )
    }

    fun cancelSubscription(context: Context): Result<CancelResult> = runCatching {
        val serverToken = ProSubscriptionServerPrefs.getApiToken(context).trim().ifBlank {
            fetchAccountInfo(context).getOrThrow().apiToken.trim()
        }

        val payload = JSONObject()
            .put("api_token", serverToken)

        val json = requestPostJson(context, endpoint(context, "/web-subscribe/cancel"), payload)
        CancelResult(
            active = json.optBoolean("active", false),
            plan = json.optString("plan").trim().ifBlank { "none" },
            expiresAtMs = json.optLong("expires_at_ms", 0L),
            message = json.optString("message").trim().ifBlank {
                if (json.optBoolean("ok", false)) "Subscription updated" else "Subscription cancel failed"
            },
        )
    }

    private fun parseModels(payload: JSONObject): List<ModelOption> {
        val out = linkedMapOf<String, ModelOption>()

        fun putOption(id: String, label: String, quotaMultiplier: Int, supportsVision: Boolean = false) {
            val cleanId = id.trim()
            if (cleanId.isBlank()) return
            val key = cleanId.lowercase()
            if (out.containsKey(key)) return

            val cleanLabel = label.trim().ifBlank { cleanId }
            out[key] = ModelOption(
                id = cleanId,
                label = cleanLabel,
                quotaMultiplier = quotaMultiplier.coerceAtLeast(1),
                supportsVision = supportsVision,
            )
        }

        fun readModelArray(array: JSONArray?) {
            if (array == null) return
            for (i in 0 until array.length()) {
                val item = array.opt(i)
                when (item) {
                    is String -> {
                        val clean = item.trim()
                        if (clean.isNotBlank()) putOption(clean, clean, 1)
                    }

                    is JSONObject -> {
                        val id = item.optString("id").trim()
                        val model = item.optString("model").trim()
                        val name = item.optString("name").trim()
                        val label = item.optString("label").trim()
                        val displayName = item.optString("display_name").trim()
                        val multiplier = intOrNull(item, "quota_multiplier")
                            ?: intOrNull(item, "multiplier")
                            ?: 1
                        val supportsVision = booleanOrNull(item, "supports_vision")
                            ?: booleanOrNull(item, "supportsVision")
                            ?: jsonArrayContains(item.optJSONArray("input_modalities"), "image")
                            ?: jsonArrayContains(item.optJSONArray("inputModalities"), "image")
                            ?: false
                        val pick = when {
                            id.isNotBlank() -> id
                            model.isNotBlank() -> model
                            name.isNotBlank() -> name
                            else -> ""
                        }
                        val pickLabel = when {
                            label.isNotBlank() -> label
                            displayName.isNotBlank() -> displayName
                            name.isNotBlank() -> name
                            else -> pick
                        }
                        if (pick.isNotBlank()) {
                            putOption(pick, pickLabel, multiplier, supportsVision)
                        }
                    }
                }
            }
        }

        readModelArray(payload.optJSONArray("data"))
        readModelArray(payload.optJSONArray("models"))
        readModelArray(payload.optJSONObject("result")?.optJSONArray("models"))

        return out.values.toList()
    }

    private fun parseAccount(payload: JSONObject): AccountInfo {
        return AccountInfo(
            apiToken = payload.optString("api_token").trim(),
            email = payload.optString("email").trim(),
            emailVerified = payload.optBoolean("email_verified", false),
            plan = payload.optString("plan").trim().ifBlank { "free" },
            subscriptionStatus = payload.optString("subscription_status").trim().ifBlank { "inactive" },
            expiresAtMs = payload.optLong("expires_at_ms", 0L),
        )
    }

    private fun parseQuota(payload: JSONObject, fallbackModel: String): QuotaInfo {
        val remaining = intOrNull(payload, "remaining")
            ?: intOrNull(payload, "remaining_requests")
            ?: intOrNull(payload, "quota_remaining")

        val limit = intOrNull(payload, "limit")
            ?: intOrNull(payload, "request_limit")
            ?: intOrNull(payload, "quota_limit")

        val used = intOrNull(payload, "used")
            ?: intOrNull(payload, "used_requests")
            ?: intOrNull(payload, "quota_used")

        val model = payload.optString("model").ifBlank { fallbackModel }
        val resetAtMs = payload.optLong("reset_at_ms", payload.optLong("resetAtMs", 0L))

        val finalUsed = used ?: if (limit != null && remaining != null) (limit - remaining).coerceAtLeast(0) else 0
        val finalLimit = limit ?: if (used != null && remaining != null) used + remaining else 0
        val finalRemaining = remaining ?: if (finalLimit > 0) (finalLimit - finalUsed).coerceAtLeast(0) else 0

        return QuotaInfo(
            used = finalUsed,
            limit = finalLimit,
            remaining = finalRemaining,
            resetAtMs = resetAtMs,
            model = model,
        )
    }

    private fun intOrNull(json: JSONObject, key: String): Int? {
        if (!json.has(key) || json.isNull(key)) return null
        val raw = json.opt(key)
        return when (raw) {
            is Number -> raw.toInt()
            is String -> raw.trim().toIntOrNull()
            else -> null
        }
    }

    private fun booleanOrNull(json: JSONObject, key: String): Boolean? {
        if (!json.has(key) || json.isNull(key)) return null
        return when (val raw = json.opt(key)) {
            is Boolean -> raw
            is String -> raw.trim().lowercase().let { value ->
                when (value) {
                    "true" -> true
                    "false" -> false
                    else -> null
                }
            }
            else -> null
        }
    }

    private fun jsonArrayContains(array: JSONArray?, expected: String): Boolean? {
        if (array == null) return null
        return (0 until array.length()).any { index ->
            array.optString(index).equals(expected, ignoreCase = true)
        }
    }

    private fun installationId(context: Context): String {
        val prefs = context.getSharedPreferences(FEEDBACK_PREFS, Context.MODE_PRIVATE)
        val existing = prefs.getString(KEY_INSTALLATION_ID, "").orEmpty().trim()
        if (existing.isNotBlank()) return existing

        val generated = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_INSTALLATION_ID, generated).apply()
        return generated
    }

    private fun endpoint(context: Context, path: String): String {
        val base = AiProviderPrefs.getRelayBaseUrl(context).trim().trimEnd('/')
        require(base.startsWith("http://") || base.startsWith("https://")) {
            "Relay URL must start with http:// or https://"
        }
        return "$base$path"
    }

    private fun requestGetJson(context: Context, url: String): JSONObject {
        val conn = (URL(url).openConnection() as HttpURLConnection)
        conn.requestMethod = "GET"
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout = READ_TIMEOUT_MS
        conn.setRequestProperty("Accept", "application/json")
        addAuthHeader(context, conn)

        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val body = BufferedReader(InputStreamReader(stream ?: conn.inputStream)).use { it.readText() }
        conn.disconnect()

        if (code !in 200..299) {
            throw IllegalStateException("HTTP $code: $body")
        }
        return JSONObject(body.ifBlank { "{}" })
    }

    private fun requestPostJson(context: Context, url: String, body: JSONObject): JSONObject {
        val conn = (URL(url).openConnection() as HttpURLConnection)
        conn.requestMethod = "POST"
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout = READ_TIMEOUT_MS
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        conn.setRequestProperty("Accept", "application/json")
        addAuthHeader(context, conn)
        OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body.toString()) }

        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val response = BufferedReader(InputStreamReader(stream ?: conn.inputStream)).use { it.readText() }
        conn.disconnect()

        if (code !in 200..299) {
            throw IllegalStateException("HTTP $code: $response")
        }
        return JSONObject(response.ifBlank { "{}" })
    }

    private fun addAuthHeader(context: Context, conn: HttpURLConnection) {
        val token = ProSubscriptionServerPrefs.getApiToken(context)
        if (token.isNotBlank()) {
            conn.setRequestProperty("Authorization", "Bearer $token")
        }
    }

    fun relayUnavailableHint(error: Throwable?): String? {
        var cur = error
        while (cur != null) {
            when (cur) {
                is UnknownHostException,
                is ConnectException,
                is SocketTimeoutException,
                is IOException,
                -> return RELAY_DOWN_HINT
            }
            cur = cur.cause
        }

        val msg = error?.message.orEmpty().lowercase()
        if (
            msg.contains("failed to connect") ||
            msg.contains("connection refused") ||
            msg.contains("timeout") ||
            msg.contains("unreachable") ||
            msg.contains("unable to resolve host") ||
            msg.contains("no address associated") ||
            msg.contains("relay unavailable")
        ) {
            return RELAY_DOWN_HINT
        }
        return null
    }

    fun relayUnavailableHintFromText(text: String): String? {
        return relayUnavailableHint(IllegalStateException(text))
    }
}
