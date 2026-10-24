package com.fersaiyan.cyanbridge.localagent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Mutable state of one [LocalAgentService] instance.
 *
 * [running] and [lastError] are process-wide (owned by the service companion) so callers can
 * query them without binding; the rest lives as long as the service instance.
 */
class LocalAgentSession(
    val running: AtomicBoolean,
    val lastError: AtomicReference<String?>,
) {
    var loopJob: Job? = null
    val cancelRequested = AtomicBoolean(false)
    val screenReadInProgress = AtomicBoolean(false)

    /** Signalled when the user approves a pending action; the loop awaits this. */
    var approvalDeferred: CompletableDeferred<Boolean>? = null

    /** Stored task state so the loop can resume after approval. */
    var pausedTaskState: LocalAgentTaskState? = null

    /** Rejects the approval the loop may be waiting for and forgets it. */
    fun rejectPendingApproval() {
        approvalDeferred?.complete(false)
        approvalDeferred = null
    }
}
