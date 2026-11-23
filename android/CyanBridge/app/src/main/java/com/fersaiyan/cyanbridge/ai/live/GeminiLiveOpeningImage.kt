package com.fersaiyan.cyanbridge.ai.live

import android.os.SystemClock
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicReference

/**
 * The photo a Live start waits for: a photo question chose [live] before its photo was in. Live starts at once (session
 * setup and, in live-video mode, the glasses stream); the service takes this at its start and sends the photo as the
 * opening turn when it comes by [Pending.deadlineMs], else the plain prompt. Process-wide hand-off between the question
 * flow and GeminiLiveForegroundService (the start goes through the permission activity).
 */
internal object GeminiLiveOpeningImage {
    class Pending(
        val photo: Deferred<String?>,
        /** SystemClock.elapsedRealtime() by which the photo must be in. */
        val deadlineMs: Long,
        val promptWithPhoto: String,
        val promptWithoutPhoto: String,
    )

    private val pending = AtomicReference<Pending?>(null)

    fun expect(next: Pending) = pending.set(next)

    /** The waiting photo of the session being started, once; null when none (or a stale one, a minute past its deadline). */
    fun take(): Pending? = pending.getAndSet(null)?.takeIf { SystemClock.elapsedRealtime() < it.deadlineMs + STALE_AFTER_MS }

    /** The opening turn: the photo with its prompt when it came in time, else the plain prompt. */
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun Pending.openingTurn(): LiveQuestionTurn {
        val wait = (deadlineMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
        val path = withTimeoutOrNull(wait) { photo.await() }
            ?: runCatching { photo.takeIf { it.isCompleted }?.getCompleted() }.getOrNull()
        return if (path != null) LiveQuestionTurn(path, promptWithPhoto) else LiveQuestionTurn(null, promptWithoutPhoto)
    }

    private const val STALE_AFTER_MS = 60_000L
}
