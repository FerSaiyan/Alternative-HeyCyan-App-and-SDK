package com.fersaiyan.cyanbridge.shared.network.session

/** Why a group teardown runs. */
enum class TeardownKind {
    /** A workflow released its link and must not be reported finished until the group is gone. */
    RELEASE,

    /** A new claim clears a group that an abandoned link may have left behind. */
    PREFLIGHT,
}

/** Next step after a teardown attempt that could not confirm the group is gone. */
sealed interface TeardownStep {
    data class Retry(val nextAttempt: Int, val delayMs: Long) : TeardownStep

    /** Keep the link reserved; retry from attempt 1 after [retryAfterMs] or on Bluetooth reconnect. */
    data class Quarantine(val retryAfterMs: Long) : TeardownStep

    /** A preflight is best-effort: hand the link to the new claim anyway. */
    data object GiveUp : TeardownStep
}

/**
 * Group-removal timing shared by every glasses link. The values are the ones the HeyCyan media
 * sync, OTA and live-preview paths used; the periodic quarantine retry is the OTA variant, the
 * only one that could recover without a Bluetooth disconnect.
 */
data class P2pTeardownPolicy(
    /** How long to wait for the platform's remove-group callback before checking anyway. */
    val removeActionTimeoutMs: Long = 5_000L,
    /** How long to poll the group state after each removal attempt. */
    val confirmTimeoutMs: Long = 5_000L,
    val confirmPollIntervalMs: Long = 250L,
    val retryDelayMs: Long = 1_000L,
    val maxAttempts: Int = 3,
    val quarantineRetryMs: Long = 5_000L,
    /** How long a new claim waits for a previous owner's teardown before giving up. */
    val claimWaitMs: Long = 15_000L,
) {
    init {
        require(maxAttempts >= 1) { "maxAttempts must be at least 1" }
        require(confirmPollIntervalMs > 0L) { "confirmPollIntervalMs must be positive" }
    }

    val confirmPolls: Int
        get() = ((confirmTimeoutMs + confirmPollIntervalMs - 1) / confirmPollIntervalMs).toInt().coerceAtLeast(1)

    fun afterUnconfirmedAttempt(attempt: Int, canUseP2p: Boolean, kind: TeardownKind): TeardownStep {
        val exhausted = !canUseP2p || attempt >= maxAttempts
        return when {
            !exhausted -> TeardownStep.Retry(attempt + 1, retryDelayMs)
            kind == TeardownKind.PREFLIGHT -> TeardownStep.GiveUp
            else -> TeardownStep.Quarantine(quarantineRetryMs)
        }
    }
}
