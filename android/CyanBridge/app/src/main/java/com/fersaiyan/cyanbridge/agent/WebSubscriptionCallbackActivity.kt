package com.fersaiyan.cyanbridge.agent

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/**
 * Handles browser return for web checkout and maps callback params to local entitlement state.
 * Expected query params (all optional, backend-defined):
 * - status: success|cancel|error
 * - plan: free_trial|cheap|standard|max
 * - token: entitlement/session token
 * - expires_at_ms: epoch millis
 * - message: short user-facing message
 */
class WebSubscriptionCallbackActivity : AppCompatActivity() {

    private data class CallbackResult(
        val success: Boolean,
        val message: String,
    )

    private fun normalizePlan(rawPlan: String): String {
        return when (rawPlan.trim().lowercase()) {
            "free_trial", "trial", "free" -> "free_trial"
            "cheap", "budget", "low" -> "cheap"
            "max", "premium", "yearly" -> "max"
            "standard", "monthly" -> "standard"
            else -> "standard"
        }
    }

    private fun fallbackExpiryMs(plan: String, now: Long): Long {
        return when (plan) {
            "max" -> now + 365L * 24L * 60L * 60L * 1000L
            "free_trial" -> now + 30L * 24L * 60L * 60L * 1000L
            else -> now + 31L * 24L * 60L * 60L * 1000L
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val data = intent?.data
        val result = applyCallback(data)

        val destination = if (result.success) {
            ProSubscriptionSettingsActivity::class.java
        } else {
            ProSubscriptionActivity::class.java
        }

        startActivity(Intent(this, destination).apply {
            putExtra(ProSubscriptionActivity.EXTRA_CALLBACK_MESSAGE, result.message)
            putExtra(ProSubscriptionActivity.EXTRA_FROM_WEB_CALLBACK, true)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        })
        finish()
    }

    private fun applyCallback(data: Uri?): CallbackResult {
        if (data == null) return CallbackResult(success = false, message = "Subscription callback missing data")

        val status = data.getQueryParameter("status")?.trim()?.lowercase().orEmpty()
        val plan = normalizePlan(data.getQueryParameter("plan").orEmpty())
        val token = data.getQueryParameter("token")?.trim().orEmpty()
        val apiToken = data.getQueryParameter("api_token")?.trim().orEmpty()
        val email = data.getQueryParameter("email")?.trim().orEmpty()
        val expiresAtMs = data.getQueryParameter("expires_at_ms")?.trim()?.toLongOrNull()
        val message = data.getQueryParameter("message")?.trim().orEmpty()

        if (apiToken.isNotBlank()) {
            ProSubscriptionServerPrefs.setApiToken(this, apiToken)
        }
        if (email.isNotBlank()) {
            ProSubscriptionServerPrefs.setAccountEmail(this, email)
        }

        if (status == "success") {
            val now = System.currentTimeMillis()
            val fallback = fallbackExpiryMs(plan, now)
            val finalExpiry = expiresAtMs ?: fallback
            val isActive = finalExpiry <= 0L || finalExpiry > now

            ProSubscriptionPrefs.setSubscribed(this, isActive)
            ProSubscriptionPrefs.setPlan(this, plan)
            ProSubscriptionPrefs.setPurchaseToken(this, token)
            ProSubscriptionPrefs.setProvider(this, if (isActive) "web_checkout" else "web_checkout_expired")
            ProSubscriptionPrefs.setExpiresAt(this, finalExpiry)
            ProSubscriptionPrefs.setLastVerifiedAt(this, System.currentTimeMillis())

            val routeNote = if (isActive) {
                ProSubscriptionRoutingPolicy.actionNote(
                    ProSubscriptionRoutingPolicy.applyAfterActivation(this),
                )
            } else {
                ""
            }

            val finalMessage = when {
                message.isNotBlank() && routeNote.isNotBlank() -> "$message · $routeNote"
                message.isNotBlank() -> message
                isActive && routeNote.isNotBlank() -> "Web subscription completed · $routeNote"
                isActive -> "Web subscription completed"
                else -> "Subscription is not active. Please renew to continue Pro access."
            }

            return CallbackResult(
                success = isActive,
                message = finalMessage,
            )
        }

        val failureMessage = if (message.isNotBlank()) {
            message
        } else {
            when (status) {
                "cancel" -> "Subscription canceled"
                "error" -> "Subscription failed"
                else -> "Subscription not completed"
            }
        }

        return CallbackResult(success = false, message = failureMessage)
    }
}
