package com.fersaiyan.cyanbridge.shared.ai

/**
 * Turns a relay failure into what the user is told. Each platform maps [Kind] to its
 * own localized text; [argument] fills the text's single placeholder.
 */
object RelayErrorClassifier {
    enum class Kind {
        /** "Monthly quota for plan %s is exhausted"; argument is the plan. */
        QUOTA_EXHAUSTED,

        /** "An active subscription is required"; no argument. */
        SUBSCRIPTION_REQUIRED,

        /** "Authentication failed"; no argument. */
        AUTHENTICATION_REQUIRED,

        /** "Relay error: %s"; argument is the detail. */
        GENERIC,
    }

    data class RelayError(val kind: Kind, val argument: String = "")

    private val quotaPlanRegex = Regex("""plan\s+([a-zA-Z0-9_\-]+)""", RegexOption.IGNORE_CASE)

    fun classify(throwable: Throwable): RelayError = classify(throwable.message)

    fun classify(message: String?): RelayError {
        val raw = message?.trim().orEmpty()
        val lower = raw.lowercase()

        val isQuota = isQuotaMessage(lower)
        val isSubscription = lower.contains("subscription_required") || lower.contains("subscription required") ||
            lower.contains("403") && lower.contains("subscription")
        val isAuth = lower.contains("authentication_required") || lower.contains("authentication required") ||
            lower.contains("401") && (lower.contains("auth") || lower.contains("token"))

        if (isQuota) {
            // Prefer the plan named in the message; with none, the text names "Pro".
            val plan = quotaPlanRegex.find(raw)?.groupValues?.getOrNull(1)?.trim()?.ifBlank { null }
                ?: extractPlanFallback(lower)
            return RelayError(Kind.QUOTA_EXHAUSTED, plan ?: "Pro")
        }
        if (isSubscription) return RelayError(Kind.SUBSCRIPTION_REQUIRED)
        if (isAuth) return RelayError(Kind.AUTHENTICATION_REQUIRED)
        return RelayError(Kind.GENERIC, if (raw.isBlank()) "unknown error" else raw.take(120))
    }

    fun isQuotaError(throwable: Throwable): Boolean = isQuotaMessage(throwable.message?.lowercase().orEmpty())

    private fun isQuotaMessage(lower: String): Boolean =
        lower.contains("quota_exhausted") || lower.contains("quota exhausted") ||
            (lower.contains("402") && lower.contains("quota"))

    private fun extractPlanFallback(lower: String): String? {
        val known = listOf("free_trial", "cheap", "standard", "max")
        for (p in known) if (lower.contains(p)) return p
        return null
    }
}
