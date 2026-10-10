package com.fersaiyan.cyanbridge.ai.live

import com.fersaiyan.cyanbridge.glasses.HeyCyanPhotoReadyTimeoutException
import com.fersaiyan.cyanbridge.glasses.GlassesPhotoTransferException

/** Nonbillable acquisition diagnostics; queued means accepted by OkHttp, not processed by Gemini. */
internal class GeminiLiveVisionDiagnostics {
    data class Snapshot(
        val revision: Int,
        val captureAttempts: Int,
        val capturesCompleted: Int,
        val captureFailures: Int,
        val imagesQueued: Int,
        val sendFailures: Int,
        val photoReadyTimeouts: Int,
        val transferFailures: Int,
    )
    private var attempts = 0
    private var completed = 0
    private var failed = 0
    private var queued = 0
    private var rejected = 0
    private var revision = 0
    private var photoReadyTimeouts = 0
    private var transferFailures = 0

    @Synchronized fun reset() {
        attempts = 0; completed = 0; failed = 0; queued = 0; rejected = 0; revision = 0
        photoReadyTimeouts = 0; transferFailures = 0
    }
    @Synchronized fun captureStarted() { attempts++; revision++ }
    @Synchronized fun captureFinished(success: Boolean, error: Throwable? = null) {
        if (success) completed++ else failed++
        if (!success && error is HeyCyanPhotoReadyTimeoutException) photoReadyTimeouts++
        if (!success && error is GlassesPhotoTransferException) transferFailures++
        revision++
    }
    @Synchronized fun snapshot() = Snapshot(revision, attempts, completed, failed, queued, rejected, photoReadyTimeouts, transferFailures)

    /** Serialize limit checks, socket enqueues and counters across initial/manual/automatic callers. */
    @Synchronized fun send(ready: Boolean, bytes: ByteArray, limit: Int, enqueue: () -> Boolean): Boolean {
        val accepted = ready && bytes.isNotEmpty() && queued < limit && enqueue()
        if (accepted) queued++ else rejected++
        revision++
        return accepted
    }
}
