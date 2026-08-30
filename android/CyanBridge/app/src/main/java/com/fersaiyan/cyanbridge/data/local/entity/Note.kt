package com.fersaiyan.cyanbridge.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Chapter 7: Notes entity.
 *
 * Aligns with AGENTS.md storage guidance: store summary + optional transcript fields.
 */
@Entity(tableName = "notes")
data class Note(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val title: String,

    /** Structured formatted summary (stable headings) */
    val summary: String,

    /** Optional raw transcript (privacy setting controls this in Chapter 8) */
    val transcript: String? = null,

    /** Optional redacted transcript (Chapter 8) */
    val redactedTranscript: String? = null,

    val createdAt: Long,
    val updatedAt: Long,

    val durationSec: Long? = null,
    val deviceClass: String? = null,

    /** CSV tags for MVP */
    val tags: String? = null,

    /** AI-generated tags are separate so user-authored tags are never overwritten. */
    val generatedTags: String? = null,

    /** Checkpoint metadata used to enrich only notes whose searchable content changed. */
    val taggedContentHash: String? = null,
    val taggingModelVersion: String? = null,
    val taggedAt: Long? = null,
)
