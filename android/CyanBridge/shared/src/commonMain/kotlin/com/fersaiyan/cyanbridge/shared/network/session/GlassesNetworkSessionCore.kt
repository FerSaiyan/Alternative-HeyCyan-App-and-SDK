package com.fersaiyan.cyanbridge.shared.network.session

import com.fersaiyan.cyanbridge.shared.devices.DeviceClass
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Platform group operations used by the shared teardown. Android implements it with
 * `WifiP2pManager`; an iOS adapter can map it to `NEHotspotConfigurationManager`.
 * Callbacks may arrive on any thread and may never arrive at all.
 */
interface P2pGroupDriver {
    fun removeGroup(onResult: (accepted: Boolean) -> Unit)

    /** Requests fresh group state; [onState] receives whether a group is still formed. */
    fun requestGroupState(onState: (formed: Boolean) -> Unit)

    /**
     * Group state the platform adapter already tracks for this owner (null when it does not
     * track one). `true` means nothing is connected or connecting.
     */
    fun trackedLinkIdle(): Boolean? = null

    fun canUseP2p(): Boolean

    /** Called once when the teardown stops using this driver (confirmed, given up or abandoned). */
    fun onTeardownFinished() {}
}

sealed interface GlassesLinkTeardownEvent {
    data object Confirmed : GlassesLinkTeardownEvent

    /** The link stays reserved; teardown retries after [retryAfterMs] or on Bluetooth reconnect. */
    data class Quarantined(val retryAfterMs: Long) : GlassesLinkTeardownEvent

    /** Preflight only: the stale group could not be confirmed gone; the claim proceeds. */
    data object GaveUp : GlassesLinkTeardownEvent

    /** Bluetooth loss abandoned the teardown; the next claim removes the group first. */
    data object Abandoned : GlassesLinkTeardownEvent
}

fun interface GlassesLinkTeardownListener {
    fun onTeardownEvent(event: GlassesLinkTeardownEvent)
}

/**
 * Platform-neutral owner of the glasses link: claim arbitration ([GlassesLinkArbiter]) plus the
 * one group-removal algorithm every workflow uses — remove, wait for the callback (bounded),
 * poll for confirmation, retry, then quarantine with periodic retries.
 *
 * [scope] must use a single-threaded dispatcher (Android main thread); every teardown step runs
 * there so its bookkeeping needs no locks.
 */
class GlassesNetworkSessionCore(
    private val scope: CoroutineScope,
    val policy: P2pTeardownPolicy = P2pTeardownPolicy(),
    private val log: (String) -> Unit = {},
) {
    val arbiter = GlassesLinkArbiter(policy)
    val snapshot: StateFlow<GlassesLinkSnapshot> get() = arbiter.snapshot

    private inner class Run(
        val ticket: GlassesLinkTeardown,
        val driver: P2pGroupDriver,
    ) {
        val listeners = mutableListOf<GlassesLinkTeardownListener>()
        val job = SupervisorJob(scope.coroutineContext[Job])
        val runScope = CoroutineScope(scope.coroutineContext + job)
        var quarantineJob: Job? = null

        @Volatile
        var groupGone = false
    }

    private var running: Run? = null

    fun tryClaim(client: GlassesLinkClient): GlassesLinkClaimResult = arbiter.requestClaim(client)

    /**
     * Claims the link, waiting up to [waitMs] for a previous owner's teardown and running the
     * preflight removal of a stale group with [preflightDriver] before returning.
     */
    suspend fun awaitClaim(
        client: GlassesLinkClient,
        preflightDriver: () -> P2pGroupDriver?,
        waitMs: Long = policy.claimWaitMs,
    ): GlassesLinkClaimResult {
        val waitResult = withTimeoutOrNull(waitMs) {
            var attempt = arbiter.requestClaim(client)
            while (attempt is GlassesLinkClaimResult.AwaitingTeardown) {
                log("${client.label} waiting for ${attempt.owner.label} to finish its link teardown")
                snapshot.first { !it.phase.isTearingDown }
                attempt = arbiter.requestClaim(client)
            }
            attempt
        }
        val result = waitResult ?: arbiter.requestClaim(client)
        if (result !is GlassesLinkClaimResult.Granted || result.preflight == null) return result

        val preflight = result.preflight
        val driver = preflightDriver()
        if (driver == null) {
            log("${client.label}: no driver for the stale-group preflight; continuing")
            arbiter.onTeardownAttemptFailed(preflight, policy.maxAttempts, canUseP2p = false)
            return GlassesLinkClaimResult.Granted(result.claim, null)
        }
        log("${client.label}: removing a group left by an abandoned link before connecting")
        val outcome = CompletableDeferred<GlassesLinkTeardownEvent>()
        startTeardown(preflight, driver) { event -> outcome.complete(event) }
        val event = try {
            outcome.await()
        } catch (cancelled: CancellationException) {
            // Hand the running preflight over as a normal release so the link is not stranded.
            withContext(NonCancellable) { release(result.claim, GlassesLinkReleaseMode.CONFIRM_TEARDOWN, driver) }
            throw cancelled
        }
        return when (event) {
            GlassesLinkTeardownEvent.Confirmed,
            GlassesLinkTeardownEvent.GaveUp,
            -> GlassesLinkClaimResult.Granted(result.claim, null)
            GlassesLinkTeardownEvent.Abandoned -> GlassesLinkClaimResult.Interrupted(client)
            is GlassesLinkTeardownEvent.Quarantined -> GlassesLinkClaimResult.Granted(result.claim, null)
        }
    }

    /**
     * Releases [claim]. [GlassesLinkReleaseMode.CONFIRM_TEARDOWN] runs the shared removal with
     * [driver] and reports progress to [listener]; without a driver it behaves like NO_GROUP.
     * Returns null for a stale claim.
     */
    fun release(
        claim: GlassesLinkClaim,
        mode: GlassesLinkReleaseMode,
        driver: P2pGroupDriver? = null,
        listener: GlassesLinkTeardownListener? = null,
    ): GlassesLinkRelease? {
        val effectiveMode = if (mode == GlassesLinkReleaseMode.CONFIRM_TEARDOWN && driver == null) {
            GlassesLinkReleaseMode.NO_GROUP
        } else {
            mode
        }
        val release = arbiter.release(claim, effectiveMode) ?: return null
        val ticket = release.teardown
        if (ticket == null) {
            if (mode == GlassesLinkReleaseMode.CONFIRM_TEARDOWN) {
                listener?.onTeardownEvent(GlassesLinkTeardownEvent.Confirmed)
            }
            return release
        }
        startTeardown(ticket, requireNotNull(driver), listener)
        return release
    }

    /** Releases with confirmation and suspends until the group is gone (true) or quarantined. */
    suspend fun releaseAndAwait(claim: GlassesLinkClaim, driver: P2pGroupDriver): Boolean {
        val outcome = CompletableDeferred<GlassesLinkTeardownEvent>()
        release(claim, GlassesLinkReleaseMode.CONFIRM_TEARDOWN, driver) { event -> outcome.complete(event) }
            ?: return false
        return outcome.await() == GlassesLinkTeardownEvent.Confirmed
    }

    /** The platform reported the group is gone (disconnect broadcast); confirms the running teardown. */
    fun signalGroupGone() {
        scope.launch {
            val run = running ?: return@launch
            run.groupGone = true
            if (arbiter.isCurrent(run.ticket)) {
                log("${run.ticket.client.label}: group removal confirmed by a disconnect event")
                finish(run, GlassesLinkTeardownEvent.Confirmed)
            }
        }
    }

    /** Applies each link owner's Bluetooth-loss rule ([GlassesLinkClient.onBleLoss]). */
    fun onBluetoothDisconnected(device: DeviceClass): BleLossOutcome {
        val outcome = arbiter.onBleLost(device)
        if (outcome is BleLossOutcome.Abandoned) {
            log("${outcome.owner.label}: Bluetooth lost; abandoning the glasses link")
            stopAbandonedTeardown(outcome.teardown)
        }
        return outcome
    }

    /** The owner of [claim] abandons its link (claimed or still tearing down). */
    fun abandon(claim: GlassesLinkClaim): BleLossOutcome.Abandoned? {
        val outcome = arbiter.abandon(claim) ?: return null
        log("${claim.client.label}: abandoning the glasses link without confirming group removal")
        stopAbandonedTeardown(outcome.teardown)
        return outcome
    }

    private fun stopAbandonedTeardown(dropped: GlassesLinkTeardown?) {
        if (dropped == null) return
        scope.launch {
            val run = running
            if (run != null && run.ticket === dropped) finish(run, GlassesLinkTeardownEvent.Abandoned)
        }
    }

    /** Retries a quarantined teardown of [device]'s link immediately. */
    fun onBluetoothConnected(device: DeviceClass) {
        scope.launch {
            val run = running ?: return@launch
            if (run.ticket.client.device != device) return@launch
            if (arbiter.snapshot.value.phase != GlassesLinkPhase.QUARANTINED) return@launch
            log("${run.ticket.client.label}: retrying the quarantined link teardown after Bluetooth reconnect")
            run.quarantineJob?.cancel()
            run.quarantineJob = null
            resumeQuarantined(run)
        }
    }

    fun markConnectRequested(claim: GlassesLinkClaim): Boolean = arbiter.onConnectRequested(claim)
    fun markGroupFormed(claim: GlassesLinkClaim): Boolean = arbiter.onGroupFormed(claim)
    fun markGroupLost(claim: GlassesLinkClaim): Boolean = arbiter.onGroupLost(claim)
    fun markRouteBound(claim: GlassesLinkClaim, bound: Boolean): Boolean = arbiter.onRouteBound(claim, bound)
    fun isActive(claim: GlassesLinkClaim?): Boolean = arbiter.isActive(claim)

    private fun startTeardown(
        ticket: GlassesLinkTeardown,
        driver: P2pGroupDriver,
        listener: GlassesLinkTeardownListener?,
    ) {
        scope.launch {
            val existing = running
            if (existing != null && existing.ticket === ticket) {
                // A claim released during its own preflight: keep the running removal.
                listener?.let(existing.listeners::add)
                return@launch
            }
            if (existing != null) {
                existing.job.cancel()
                running = null
            }
            val run = Run(ticket, driver)
            listener?.let(run.listeners::add)
            running = run
            startAttempt(run, attempt = 1)
        }
    }

    private fun isCurrent(run: Run): Boolean = running === run && arbiter.isCurrent(run.ticket)

    private fun startAttempt(run: Run, attempt: Int) {
        if (!isCurrent(run)) return
        if (run.groupGone) {
            finish(run, GlassesLinkTeardownEvent.Confirmed)
            return
        }
        arbiter.onTeardownAttemptStarted(run.ticket, attempt)
        val label = run.ticket.client.label
        var handled = false
        run.runScope.launch {
            delay(policy.removeActionTimeoutMs)
            if (!handled && isCurrent(run)) {
                handled = true
                log("$label: group removal gave no callback on attempt $attempt; checking group state")
                awaitConfirmation(run, attempt)
            }
        }
        try {
            run.driver.removeGroup { accepted ->
                run.runScope.launch {
                    if (handled || !isCurrent(run)) {
                        log("$label: ignoring late group removal callback for attempt $attempt")
                        return@launch
                    }
                    handled = true
                    if (accepted) {
                        log("$label: group removal accepted on attempt $attempt; waiting for disconnect")
                    } else {
                        log("$label: group removal failed on attempt $attempt; checking whether the group is already gone")
                    }
                    awaitConfirmation(run, attempt)
                }
            }
        } catch (error: Exception) {
            if (!handled) {
                handled = true
                log("$label: group removal threw on attempt $attempt (${error.message}); checking group state")
                awaitConfirmation(run, attempt)
            }
        }
    }

    private fun awaitConfirmation(run: Run, attempt: Int) {
        run.runScope.launch {
            val label = run.ticket.client.label
            repeat(policy.confirmPolls) {
                if (!isCurrent(run)) return@launch
                try {
                    run.driver.requestGroupState { formed -> if (!formed) run.groupGone = true }
                } catch (error: Exception) {
                    log("$label: group state request failed: ${error.message}")
                }
                delay(policy.confirmPollIntervalMs)
                if (!isCurrent(run)) return@launch
                if (run.groupGone || run.driver.trackedLinkIdle() == true) {
                    log("$label: confirmed the group is gone after teardown attempt $attempt")
                    finish(run, GlassesLinkTeardownEvent.Confirmed)
                    return@launch
                }
            }
            val step = arbiter.onTeardownAttemptFailed(run.ticket, attempt, run.driver.canUseP2p())
                ?: return@launch
            when (step) {
                is TeardownStep.Retry -> {
                    log("$label: group still present after teardown attempt $attempt; keeping the link reserved and retrying")
                    run.runScope.launch {
                        delay(step.delayMs)
                        startAttempt(run, step.nextAttempt)
                    }
                }
                is TeardownStep.Quarantine -> {
                    log(
                        "$label: group teardown could not be confirmed; keeping the link quarantined " +
                            "and retrying every ${step.retryAfterMs}ms or on Bluetooth reconnect",
                    )
                    run.listeners.forEach { it.onTeardownEvent(GlassesLinkTeardownEvent.Quarantined(step.retryAfterMs)) }
                    run.quarantineJob = run.runScope.launch {
                        delay(step.retryAfterMs)
                        run.quarantineJob = null
                        resumeQuarantined(run)
                    }
                }
                TeardownStep.GiveUp -> {
                    log("$label: stale group could not be confirmed gone; continuing with the new link")
                    finish(run, GlassesLinkTeardownEvent.GaveUp, arbiterFinished = true)
                }
            }
        }
    }

    private fun resumeQuarantined(run: Run) {
        if (running !== run) return
        if (!arbiter.retryQuarantined(run.ticket)) return
        log("${run.ticket.client.label}: retrying quarantined group teardown")
        startAttempt(run, attempt = 1)
    }

    private fun finish(run: Run, event: GlassesLinkTeardownEvent, arbiterFinished: Boolean = false) {
        if (running !== run) return
        running = null
        if (event == GlassesLinkTeardownEvent.Confirmed && !arbiterFinished) {
            arbiter.onTeardownConfirmed(run.ticket)
        }
        run.job.cancel()
        try {
            run.driver.onTeardownFinished()
        } catch (error: Exception) {
            log("${run.ticket.client.label}: driver cleanup failed: ${error.message}")
        }
        run.listeners.forEach { it.onTeardownEvent(event) }
    }

}
