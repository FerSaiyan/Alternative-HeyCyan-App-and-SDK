package com.fersaiyan.cyanbridge.data.local.entity

/**
 * FTS5 index for [MemoryChunk].
 *
 * NOTE: Room (2.6.x) only provides annotations for FTS3/FTS4 virtual tables.
 * We still want FTS5 (bm25/snippet), so the index is created via SQL in
 * [com.fersaiyan.cyanbridge.data.local.AppDatabase.MIGRATION_4_5] and ensured
 * for fresh installs via a RoomDatabase.Callback.
 */
object MemoryChunkFtsSchema {
    const val TABLE = "memory_chunks_fts"
}
