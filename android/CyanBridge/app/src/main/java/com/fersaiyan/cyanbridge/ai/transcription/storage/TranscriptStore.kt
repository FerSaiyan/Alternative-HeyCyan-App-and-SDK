package com.fersaiyan.cyanbridge.ai.transcription.storage

/**
 * Transcript persistence abstraction.
 *
 * Implementations must respect the user's transcript-storage preference.
 */
interface TranscriptStore {
    suspend fun maybePersist(
        captureSessionId: Long?,
        provider: String,
        language: String?,
        transcript: String,
    ): Boolean
}
