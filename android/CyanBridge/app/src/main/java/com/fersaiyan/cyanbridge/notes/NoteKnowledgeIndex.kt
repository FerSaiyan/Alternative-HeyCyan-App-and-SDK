package com.fersaiyan.cyanbridge.notes

import android.content.Context
import com.fersaiyan.cyanbridge.data.local.entity.MemoryChunk
import com.fersaiyan.cyanbridge.data.local.entity.MemoryChunkSources
import com.fersaiyan.cyanbridge.data.local.entity.Note
import com.fersaiyan.cyanbridge.memoryvault.LocalEmbeddingService
import com.fersaiyan.cyanbridge.memoryvault.MemoryPolicyService
import com.fersaiyan.cyanbridge.memoryvault.MemoryRefMapper
import com.fersaiyan.cyanbridge.memoryvault.MemoryVaultBootstrap
import com.fersaiyan.cyanbridge.ui.MyApplication

/** Keeps CyanBridge notes searchable without waiting for deferred AI enrichment. */
object NoteKnowledgeIndex {
    suspend fun index(context: Context, note: Note) {
        MemoryVaultBootstrap.ensureInitialized(context)
        val dao = MyApplication.database.memoryChunkDao()
        val sourceId = note.id.toString()
        val searchable = buildString {
            appendLine(note.title.trim())
            val allTags = listOf(note.tags, note.generatedTags)
                .filterNotNull()
                .flatMap { it.split(',') }
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()
            if (allTags.isNotEmpty()) appendLine("Tags: ${allTags.joinToString(", ")}")
            append(note.summary.trim())
        }.trim()
        if (searchable.isBlank()) return

        val previous = dao.listBySourceAndSourceId(MemoryChunkSources.CYANBRIDGE_NOTE, sourceId)
        if (previous.size == 1 && previous.single().text == searchable && previous.single().tsMs == note.updatedAt) {
            return
        }
        previous.forEach { chunk ->
            val ref = MemoryRefMapper.forMemoryChunk(chunk.id)
            MyApplication.database.memoryVaultDao().deleteEmbedding(ref)
            MyApplication.database.memoryVaultDao().deletePolicy(ref)
        }
        dao.deleteBySourceAndSourceId(MemoryChunkSources.CYANBRIDGE_NOTE, sourceId)

        val now = System.currentTimeMillis()
        val rowId = dao.insert(
            MemoryChunk(
                source = MemoryChunkSources.CYANBRIDGE_NOTE,
                sourceId = sourceId,
                packageName = "cyanbridge",
                tsMs = note.updatedAt,
                text = searchable,
                createdAt = now,
                updatedAt = now,
            ),
        )
        val ref = MemoryRefMapper.forMemoryChunk(rowId)
        MemoryPolicyService.upsertPolicy(
            MemoryPolicyService.classifyForMemoryRef(
                context = context,
                memoryRef = "note:$sourceId",
                text = searchable,
                sourceTimestampMs = note.updatedAt,
                provenance = "cyanbridge_note",
            ).copy(memoryRef = ref),
        )
        LocalEmbeddingService.upsertEmbedding(ref, searchable)
    }
}
