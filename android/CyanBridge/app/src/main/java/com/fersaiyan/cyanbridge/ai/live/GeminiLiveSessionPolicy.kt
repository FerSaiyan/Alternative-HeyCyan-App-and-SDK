package com.fersaiyan.cyanbridge.ai.live

/** Session lifecycle policy independent of the local voice-action experiment. */
internal object GeminiLiveSessionPolicy {
    /** Starts only once Gemini's server has completed a model turn. */
    const val POST_RESPONSE_IDLE_MS = 30_000L

    /** Free and Economy can debit a daily session allowance; do not silently renew. */
    const val METERED_SESSION_LIMIT_MS = 5L * 60L * 1_000L

    fun requiresExplicitRestart(freeTier: Boolean, economy: Boolean): Boolean =
        freeTier || economy
}
