package com.fersaiyan.cyanbridge.shared.network.session

import com.fersaiyan.cyanbridge.shared.devices.DeviceClass
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Lifecycle of the phone's single local link to the glasses. */
enum class GlassesLinkPhase {
    IDLE,

    /** Claimed; a group left by an abandoned link is being removed before the owner may use it. */
    PREFLIGHT_TEARDOWN,
    DISCOVERING,
    CONNECTING,
    CONNECTED,

    /** Released; the owner's group removal has not been confirmed yet. */
    TEARING_DOWN,

    /** Group removal could not be confirmed; retried periodically and on Bluetooth reconnect. */
    QUARANTINED,
    ;

    val holdsClaim: Boolean
        get() = this == PREFLIGHT_TEARDOWN || this == DISCOVERING || this == CONNECTING || this == CONNECTED

    val isTearingDown: Boolean
        get() = this == TEARING_DOWN || this == QUARANTINED
}

data class GlassesLinkSnapshot(
    val phase: GlassesLinkPhase = GlassesLinkPhase.IDLE,
    /** The claim holder, or the released owner whose teardown is still running. */
    val owner: GlassesLinkClient? = null,
    val generation: Long = 0L,
    /** The owner has bound the process (or its sockets) to the link's network. */
    val routeBound: Boolean = false,
    val teardownKind: TeardownKind? = null,
    val teardownAttempt: Int = 0,
    /** A link was abandoned without confirming its group was removed. */
    val staleGroupSuspected: Boolean = false,
)

/** Proof of link ownership. Stale claims (from before a release or BLE loss) are inert. */
class GlassesLinkClaim internal constructor(
    val client: GlassesLinkClient,
    internal val generation: Long,
) {
    override fun toString(): String = "GlassesLinkClaim(${client.name}#$generation)"
}

/** Identifies one teardown run; it stays valid across retries and quarantine. */
class GlassesLinkTeardown internal constructor(
    val client: GlassesLinkClient,
    internal val generation: Long,
) {
    override fun toString(): String = "GlassesLinkTeardown(${client.name}#$generation)"
}

sealed interface GlassesLinkClaimResult {
    /** [preflight] is non-null when a stale group must be removed before the link is usable. */
    data class Granted(val claim: GlassesLinkClaim, val preflight: GlassesLinkTeardown?) : GlassesLinkClaimResult

    data class Busy(val owner: GlassesLinkClient) : GlassesLinkClaimResult

    data class AwaitingTeardown(val owner: GlassesLinkClient, val quarantined: Boolean) : GlassesLinkClaimResult

    /** Bluetooth loss abandoned the claim while its preflight teardown was running. */
    data class Interrupted(val client: GlassesLinkClient) : GlassesLinkClaimResult
}

enum class GlassesLinkReleaseMode {
    /** Remove the group and keep the link reserved until removal is confirmed. */
    CONFIRM_TEARDOWN,

    /** Drop the link now (Bluetooth loss); the next claim removes any stale group first. */
    ABANDON,

    /** No group was ever formed (access-point link or discovery never started). */
    NO_GROUP,
}

data class GlassesLinkRelease(
    /** Non-null for [GlassesLinkReleaseMode.CONFIRM_TEARDOWN]. */
    val teardown: GlassesLinkTeardown?,
    /** The caller must restore the default process network. */
    val routeWasBound: Boolean,
)

sealed interface BleLossOutcome {
    data object NotAffected : BleLossOutcome

    data class KeptForTeardown(val owner: GlassesLinkClient) : BleLossOutcome

    data class Abandoned(
        val owner: GlassesLinkClient,
        val routeWasBound: Boolean,
        /** A teardown (release or preflight) was running and has been dropped. */
        val teardown: GlassesLinkTeardown?,
    ) : BleLossOutcome
}

/**
 * The single source of truth for who owns the glasses link and in which phase it is.
 *
 * Rules:
 * - One claim at a time; there is no preemption between workflows. A second claim is [Busy].
 * - A released link stays reserved until its group removal is confirmed; new claims see
 *   [GlassesLinkClaimResult.AwaitingTeardown] meanwhile. Unconfirmed removal quarantines the
 *   link (periodic retry) instead of silently handing a live group to the next workflow.
 * - Bluetooth loss of the owner's device abandons the link unless the owner must confirm its
 *   own teardown (firmware update). An abandoned link marks a stale group, which the next claim
 *   removes first.
 */
class GlassesLinkArbiter(val policy: P2pTeardownPolicy = P2pTeardownPolicy()) {
    private val lock = SynchronizedObject()
    private val state = MutableStateFlow(GlassesLinkSnapshot())
    val snapshot: StateFlow<GlassesLinkSnapshot> = state.asStateFlow()

    private var activeClaim: GlassesLinkClaim? = null
    private var teardown: GlassesLinkTeardown? = null
    private var nextGeneration = 0L

    fun requestClaim(client: GlassesLinkClient): GlassesLinkClaimResult {
        return synchronized(lock) {
            val current = state.value
            val owner = current.owner
            if (current.phase.isTearingDown && owner != null) {
                return GlassesLinkClaimResult.AwaitingTeardown(owner, current.phase == GlassesLinkPhase.QUARANTINED)
            }
            if (current.phase.holdsClaim && owner != null) {
                return GlassesLinkClaimResult.Busy(owner)
            }
            val generation = ++nextGeneration
            val claim = GlassesLinkClaim(client, generation)
            activeClaim = claim
            if (current.staleGroupSuspected) {
                val preflight = GlassesLinkTeardown(client, generation)
                teardown = preflight
                state.value = GlassesLinkSnapshot(
                    phase = GlassesLinkPhase.PREFLIGHT_TEARDOWN,
                    owner = client,
                    generation = generation,
                    teardownKind = TeardownKind.PREFLIGHT,
                    teardownAttempt = 0,
                    staleGroupSuspected = true,
                )
                GlassesLinkClaimResult.Granted(claim, preflight)
            } else {
                teardown = null
                state.value = GlassesLinkSnapshot(
                    phase = GlassesLinkPhase.DISCOVERING,
                    owner = client,
                    generation = generation,
                )
                GlassesLinkClaimResult.Granted(claim, null)
            }
        }
    }

    /** The claim still owns the link (including while its preflight runs). */
    fun isActive(claim: GlassesLinkClaim?): Boolean = synchronized(lock) {
        claim != null && activeClaim === claim && state.value.phase.holdsClaim
    }

    /** The claim owns a link that is ready for use (no preflight pending). */
    fun isReady(claim: GlassesLinkClaim?): Boolean = synchronized(lock) {
        isActive(claim) && state.value.phase != GlassesLinkPhase.PREFLIGHT_TEARDOWN
    }

    fun isCurrent(ticket: GlassesLinkTeardown?): Boolean = synchronized(lock) {
        ticket != null && teardown === ticket &&
            (state.value.phase.isTearingDown || state.value.phase == GlassesLinkPhase.PREFLIGHT_TEARDOWN)
    }

    fun onConnectRequested(claim: GlassesLinkClaim): Boolean = synchronized(lock) {
        updateReady(claim) { it.copy(phase = GlassesLinkPhase.CONNECTING) }
    }

    fun onGroupFormed(claim: GlassesLinkClaim): Boolean = synchronized(lock) {
        updateReady(claim) { it.copy(phase = GlassesLinkPhase.CONNECTED) }
    }

    /** The group dropped while still claimed; any process binding no longer routes anywhere. */
    fun onGroupLost(claim: GlassesLinkClaim): Boolean = synchronized(lock) {
        updateReady(claim) { it.copy(phase = GlassesLinkPhase.DISCOVERING) }
    }

    fun onRouteBound(claim: GlassesLinkClaim, bound: Boolean): Boolean = synchronized(lock) {
        updateReady(claim) { it.copy(routeBound = bound) }
    }

    fun release(claim: GlassesLinkClaim, mode: GlassesLinkReleaseMode): GlassesLinkRelease? {
        return synchronized(lock) {
            if (!isActive(claim)) return null
            val current = state.value
            val routeWasBound = current.routeBound
            activeClaim = null
            when (mode) {
                GlassesLinkReleaseMode.CONFIRM_TEARDOWN -> {
                    // A claim released during its own preflight keeps that teardown running.
                    val ticket = teardown?.takeIf { current.phase == GlassesLinkPhase.PREFLIGHT_TEARDOWN }
                        ?: GlassesLinkTeardown(claim.client, claim.generation)
                    teardown = ticket
                    state.value = current.copy(
                        phase = GlassesLinkPhase.TEARING_DOWN,
                        routeBound = false,
                        teardownKind = TeardownKind.RELEASE,
                        teardownAttempt = 0,
                    )
                    GlassesLinkRelease(ticket, routeWasBound)
                }
                GlassesLinkReleaseMode.ABANDON -> {
                    teardown = null
                    state.value = GlassesLinkSnapshot(
                        generation = current.generation,
                        staleGroupSuspected = true,
                    )
                    GlassesLinkRelease(null, routeWasBound)
                }
                GlassesLinkReleaseMode.NO_GROUP -> {
                    teardown = null
                    state.value = GlassesLinkSnapshot(
                        generation = current.generation,
                        staleGroupSuspected = current.staleGroupSuspected &&
                            current.phase == GlassesLinkPhase.PREFLIGHT_TEARDOWN,
                    )
                    GlassesLinkRelease(null, routeWasBound)
                }
            }
        }
    }

    fun teardownKind(ticket: GlassesLinkTeardown): TeardownKind? = synchronized(lock) {
        if (isCurrent(ticket)) state.value.teardownKind else null
    }

    fun onTeardownAttemptStarted(ticket: GlassesLinkTeardown, attempt: Int): Boolean {
        return synchronized(lock) {
            if (!isCurrent(ticket)) return false
            state.value = state.value.copy(teardownAttempt = attempt)
            true
        }
    }

    /** Null when [ticket] is stale. */
    fun onTeardownAttemptFailed(ticket: GlassesLinkTeardown, attempt: Int, canUseP2p: Boolean): TeardownStep? {
        return synchronized(lock) {
            if (!isCurrent(ticket)) return null
            val current = state.value
            val kind = current.teardownKind ?: TeardownKind.RELEASE
            val step = policy.afterUnconfirmedAttempt(attempt, canUseP2p, kind)
            when (step) {
                is TeardownStep.Retry -> Unit
                is TeardownStep.Quarantine -> state.value = current.copy(phase = GlassesLinkPhase.QUARANTINED)
                TeardownStep.GiveUp -> finishTeardown(confirmed = false)
            }
            step
        }
    }

    /** Group removal confirmed. Returns false when [ticket] is stale. */
    fun onTeardownConfirmed(ticket: GlassesLinkTeardown): Boolean {
        return synchronized(lock) {
            if (!isCurrent(ticket)) return false
            finishTeardown(confirmed = true)
            true
        }
    }

    /** Moves a quarantined teardown back to an active attempt. */
    fun retryQuarantined(ticket: GlassesLinkTeardown): Boolean {
        return synchronized(lock) {
            if (!isCurrent(ticket) || state.value.phase != GlassesLinkPhase.QUARANTINED) return false
            state.value = state.value.copy(phase = GlassesLinkPhase.TEARING_DOWN, teardownAttempt = 0)
            true
        }
    }

    fun onBleLost(device: DeviceClass): BleLossOutcome {
        return synchronized(lock) {
            val current = state.value
            val owner = current.owner ?: return BleLossOutcome.NotAffected
            if (current.phase == GlassesLinkPhase.IDLE) return BleLossOutcome.NotAffected
            when (owner.onBleLoss(device)) {
                BleLossLinkAction.NOT_AFFECTED -> BleLossOutcome.NotAffected
                BleLossLinkAction.KEEP_CONFIRMED_TEARDOWN -> BleLossOutcome.KeptForTeardown(owner)
                BleLossLinkAction.ABANDON -> {
                    val droppedTeardown = teardown
                    activeClaim = null
                    teardown = null
                    state.value = GlassesLinkSnapshot(
                        generation = current.generation,
                        staleGroupSuspected = true,
                    )
                    BleLossOutcome.Abandoned(owner, current.routeBound, droppedTeardown)
                }
            }
        }
    }

    /**
     * Abandons [claim]'s link whether it is still claimed or already tearing down (the owner's
     * own Bluetooth-loss handling). Null when the link has moved on to another owner.
     */
    fun abandon(claim: GlassesLinkClaim): BleLossOutcome.Abandoned? {
        return synchronized(lock) {
            val current = state.value
            val ownsTeardown = teardown?.generation == claim.generation && current.phase.isTearingDown
            if (!isActive(claim) && !ownsTeardown) return null
            val droppedTeardown = teardown
            activeClaim = null
            teardown = null
            state.value = GlassesLinkSnapshot(
                generation = current.generation,
                staleGroupSuspected = true,
            )
            BleLossOutcome.Abandoned(claim.client, current.routeBound, droppedTeardown)
        }
    }

    private fun finishTeardown(confirmed: Boolean) {
        val current = state.value
        teardown = null
        if (current.phase == GlassesLinkPhase.PREFLIGHT_TEARDOWN) {
            // The new owner may now use the link; an unconfirmed preflight is best-effort.
            state.value = current.copy(
                phase = GlassesLinkPhase.DISCOVERING,
                teardownKind = null,
                teardownAttempt = 0,
                staleGroupSuspected = false,
            )
        } else {
            state.value = GlassesLinkSnapshot(
                generation = current.generation,
                staleGroupSuspected = !confirmed,
            )
        }
    }

    private inline fun updateReady(
        claim: GlassesLinkClaim,
        transform: (GlassesLinkSnapshot) -> GlassesLinkSnapshot,
    ): Boolean {
        if (!isReady(claim)) return false
        state.value = transform(state.value)
        return true
    }
}
