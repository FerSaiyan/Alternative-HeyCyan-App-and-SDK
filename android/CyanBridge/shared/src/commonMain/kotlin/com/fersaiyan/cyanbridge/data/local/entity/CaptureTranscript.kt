package com.fersaiyan.cyanbridge.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Transcription persisted separately from notes (Chapter 6).
 *
 * Storage is gated behind a privacy toggle (default OFF).
 */
@Entity(tableName = "capture_transcripts")
data class CaptureTranscript(
    /**
     * 1:1 mapping with capture session.
     * Using captureSessionId as the PK allows simple upsert via REPLACE.
     */
    @PrimaryKey
    val captureSessionId: Long,

    val createdAt: Long,
    val updatedAt: Long,

    /** Provider identifier (e.g., "fake", "http"). */
    val provider: String,

    /** Optional language hint (BCP-47 or provider-specific). */
    val language: String?,

    /** Full transcript text. */
    val transcript: String,
)
