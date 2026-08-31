package com.fersaiyan.cyanbridge.ai.router

import android.content.Context
import com.fersaiyan.cyanbridge.R

object RelayErrorLocalizer {

    private val quotaPlanRegex = Regex("""plan\s+([a-zA-Z0-9_\-]+)""", RegexOption.IGNORE_CASE)

    fun localizedMessage(context: Context, throwable: Throwable): String {
        val raw = throwable.message?.trim().orEmpty()
        val lower = raw.lowercase()

        // Detect error type from message / HTTP code
        val isQuota = lower.contains("quota_exhausted") || lower.contains("quota exhausted") || lower.contains("402") && lower.contains("quota")
        val isSubscription = lower.contains("subscription_required") || lower.contains("subscription required") || lower.contains("403") && lower.contains("subscription")
        val isAuth = lower.contains("authentication_required") || lower.contains("authentication required") || lower.contains("401") && (lower.contains("auth") || lower.contains("token"))

        // Prefer quota with plan extraction
        if (isQuota) {
            val plan = quotaPlanRegex.find(raw)?.groupValues?.getOrNull(1)?.trim()?.ifBlank { null } ?: extractPlanFallback(lower)
            return if (plan != null) {
                try {
                    context.getString(R.string.relay_quota_exhausted, plan)
                } catch (_: Exception) {
                    context.getString(R.string.relay_quota_exhausted, plan)
                }
            } else {
                // No plan extracted; use same string with generic plan name
                try {
                    context.getString(R.string.relay_quota_exhausted, "Pro")
                } catch (_: Exception) {
                    "Monthly quota exhausted. Please wait until it resets or upgrade your subscription."
                }
            }
        }
        if (isSubscription) {
            return try {
                context.getString(R.string.relay_subscription_required)
            } catch (_: Exception) {
                "An active subscription is required. Please check your Pro subscription."
            }
        }
        if (isAuth) {
            return try {
                context.getString(R.string.relay_authentication_required)
            } catch (_: Exception) {
                "Authentication failed. Please sign in again."
            }
        }
        // Generic fallback: if message is not blank and not technical HTTP, show localized generic + detail
        val detail = raw.takeIf { it.isNotBlank() && !it.startsWith("Relay HTTP") } ?: raw
        return if (detail.isNotBlank() && !detail.equals("Relay HTTP 429", ignoreCase = true)) {
            // For 429 rate limit etc, map to generic but include detail via placeholder if needed
            try {
                context.getString(R.string.relay_error_generic, detail.take(120))
            } catch (_: Exception) {
                detail
            }
        } else {
            try {
                context.getString(R.string.relay_error_generic, raw.takeIf { it.isNotBlank() } ?: "unknown error")
            } catch (_: Exception) {
                raw.ifBlank { "Relay error: unknown error" }
            }
        }
    }

    private fun extractPlanFallback(lower: String): String? {
        // Check for known plan keywords
        val known = listOf("free_trial", "cheap", "standard", "max")
        for (p in known) if (lower.contains(p)) return p
        return null
    }

    fun isQuotaError(throwable: Throwable): Boolean {
        val msg = throwable.message?.lowercase().orEmpty()
        return msg.contains("quota_exhausted") || msg.contains("quota exhausted") || (msg.contains("402") && msg.contains("quota"))
    }
}
