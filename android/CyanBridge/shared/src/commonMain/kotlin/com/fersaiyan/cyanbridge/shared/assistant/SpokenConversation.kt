package com.fersaiyan.cyanbridge.shared.assistant

import com.fersaiyan.cyanbridge.shared.platform.platformElapsedRealtimeMillis
import kotlin.concurrent.Volatile

/**
 * The spoken-question conversation a model keeps open between turns (Android: the local LiteRT
 * engine session, localmodels/engine SessionTurn): follow-ups and a new question soon after the
 * last answer continue it, so earlier recordings and photos stay in the KV cache instead of being
 * prefilled again.
 */
object SpokenConversation {
    const val SESSION_KEY = "glasses_spoken"

    /** A new question this soon after the last answer continues the conversation. */
    const val CONTINUE_WITHIN_MS = 3 * 60_000L

    @Volatile private var lastTurnEndMs = 0L

    /** What the app said last turn without a model reply in the session (a web answer, a fixed sentence). */
    @Volatile var pendingNote: String? = null

    fun canContinue(now: Long = platformElapsedRealtimeMillis()): Boolean =
        lastTurnEndMs > 0L && now - lastTurnEndMs < CONTINUE_WITHIN_MS

    fun turnEnded(now: Long = platformElapsedRealtimeMillis()) {
        lastTurnEndMs = now
    }

    /** Next question starts a new conversation (also used by tests). */
    fun reset() {
        lastTurnEndMs = 0L
        pendingNote = null
    }

    fun takeNote(): String? = pendingNote.also { pendingNote = null }
}
