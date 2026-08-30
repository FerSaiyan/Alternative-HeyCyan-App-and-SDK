package com.fersaiyan.cyanbridge.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Local Agent / Memory: a chunk of text we may want to retrieve later.
 *
 * MVP usage:
 * - Periodic accessibility screen snapshots (see LocalAgentMemoryStore).
 *
 * Stored as an append-only log. Full-text search is provided via [MemoryChunkFts].
 */
@Entity(
    tableName = "memory_chunks",
    indices = [
        Index(value = ["source"]),
        Index(value = ["tsMs"]),
    ]
)
data class MemoryChunk(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** e.g. "screen_capture" */
    val source: String,

    /** Optional finer-grained source identifier (e.g. a file/date key). */
    val sourceId: String? = null,

    /** Optional package name (useful for screen captures). */
    val packageName: String? = null,

    /** Original event timestamp. */
    val tsMs: Long,

    /** Text payload to index/search. */
    val text: String,

    /** DB bookkeeping timestamps. */
    val createdAt: Long,
    val updatedAt: Long,
)

object MemoryChunkSources {
    const val SCREEN_CAPTURE = "screen_capture"
    const val CYANBRIDGE_NOTE = "cyanbridge_note"
}
