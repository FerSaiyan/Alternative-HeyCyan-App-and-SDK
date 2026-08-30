package com.fersaiyan.cyanbridge.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import com.fersaiyan.cyanbridge.data.local.entity.MemoryChunk

@Dao
interface MemoryChunkDao {
    data class ChunkRef(
        val id: Long,
        val source: String,
        val sourceId: String?,
        val packageName: String?,
        val tsMs: Long,
    )

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(chunk: MemoryChunk): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(chunks: List<MemoryChunk>): List<Long>

    @Query("DELETE FROM memory_chunks")
    suspend fun deleteAll()

    @Query("DELETE FROM memory_chunks WHERE source = :source")
    suspend fun deleteBySource(source: String): Int

    @Query("DELETE FROM memory_chunks WHERE source = :source AND packageName = :packageName")
    suspend fun deleteBySourceAndPackageName(source: String, packageName: String): Int

    @Query("SELECT * FROM memory_chunks WHERE source = :source AND sourceId = :sourceId")
    suspend fun listBySourceAndSourceId(source: String, sourceId: String): List<MemoryChunk>

    @Query("DELETE FROM memory_chunks WHERE source = :source AND sourceId = :sourceId")
    suspend fun deleteBySourceAndSourceId(source: String, sourceId: String): Int

    @Query("SELECT * FROM memory_chunks WHERE source = :source AND packageName = :packageName")
    suspend fun listBySourceAndPackageName(source: String, packageName: String): List<MemoryChunk>

    @Query("DELETE FROM memory_chunks WHERE source = :source AND packageName = :packageName AND sourceId = :sourceId")
    suspend fun deleteBySourcePackageAndSourceId(source: String, packageName: String, sourceId: String): Int

    @Query("DELETE FROM memory_chunks WHERE source = :source AND tsMs < :beforeTs")
    suspend fun deleteSourceOlderThan(source: String, beforeTs: Long): Int

    @Query("SELECT id, source, sourceId, packageName, tsMs FROM memory_chunks")
    suspend fun listChunkRefs(): List<ChunkRef>

    @Query("SELECT * FROM memory_chunks WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): MemoryChunk?

    @Query("SELECT * FROM memory_chunks WHERE source = :source ORDER BY updatedAt DESC")
    suspend fun listBySource(source: String): List<MemoryChunk>

    // --- FTS search (FTS5 virtual table created via SQL migration/callback) ---

    data class SearchHit(
        val id: Long,
        val source: String,
        val sourceId: String?,
        val packageName: String?,
        val tsMs: Long,
        val createdAt: Long,
        val updatedAt: Long,
        val text: String,
        val snippet: String,
        val rank: Double,
    )

    @RawQuery(observedEntities = [MemoryChunk::class])
    suspend fun searchHitsRaw(query: SupportSQLiteQuery): List<SearchHit>

    @RawQuery(observedEntities = [MemoryChunk::class])
    suspend fun searchChunksRaw(query: SupportSQLiteQuery): List<MemoryChunk>

    suspend fun search(query: String, limit: Int = 20): List<MemoryChunk> {
        val sql = """
            SELECT mc.*
            FROM memory_chunks mc
            JOIN memory_chunks_fts ON mc.id = memory_chunks_fts.rowid
            WHERE memory_chunks_fts MATCH ?
            ORDER BY bm25(memory_chunks_fts)
            LIMIT ?
        """.trimIndent()

        return searchChunksRaw(SimpleSQLiteQuery(sql, arrayOf<Any>(query, limit)))
    }

    suspend fun searchWithSnippet(query: String, limit: Int = 20): List<SearchHit> {
        val sql = """
            SELECT 
                mc.*, 
                snippet(memory_chunks_fts, 0, '[', ']', '…', 12) AS snippet,
                bm25(memory_chunks_fts) AS rank
            FROM memory_chunks mc
            JOIN memory_chunks_fts ON mc.id = memory_chunks_fts.rowid
            WHERE memory_chunks_fts MATCH ?
            ORDER BY rank
            LIMIT ?
        """.trimIndent()

        return searchHitsRaw(SimpleSQLiteQuery(sql, arrayOf<Any>(query, limit)))
    }
}
