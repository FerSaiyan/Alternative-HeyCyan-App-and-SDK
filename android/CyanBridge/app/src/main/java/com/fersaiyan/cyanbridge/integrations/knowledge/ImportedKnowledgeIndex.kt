package com.fersaiyan.cyanbridge.integrations.knowledge

import android.content.Context
import com.fersaiyan.cyanbridge.ai.router.AiProviderPrefs
import com.fersaiyan.cyanbridge.ai.router.AiProviderType
import com.fersaiyan.cyanbridge.data.local.entity.MemoryChunk
import com.fersaiyan.cyanbridge.data.local.entity.MemoryChunkSources
import com.fersaiyan.cyanbridge.localagent.memory.LocalAgentMemoryRoomIndex
import com.fersaiyan.cyanbridge.memoryvault.MemoryModeManager
import com.fersaiyan.cyanbridge.memoryvault.LocalEmbeddingService
import com.fersaiyan.cyanbridge.memoryvault.MemoryPolicyService
import com.fersaiyan.cyanbridge.memoryvault.MemoryRefMapper
import com.fersaiyan.cyanbridge.memoryvault.MemoryVaultBootstrap
import com.fersaiyan.cyanbridge.memoryvault.VaultLockStateManager
import com.fersaiyan.cyanbridge.ui.MyApplication
import org.json.JSONArray

/** Local searchable index for inbound external knowledge. */
object ImportedKnowledgeIndex {
    const val SOURCE = "imported_text"
    private const val MAX_CHUNK_CHARS = 2400
    private const val CHUNK_OVERLAP = 240

    suspend fun replaceSource(
        context: Context,
        source: KnowledgeSource,
        documents: List<KnowledgeDocument>,
    ): Int {
        MemoryVaultBootstrap.ensureInitialized(context)
        val dao = MyApplication.database.memoryChunkDao()
        val existing = dao.listBySourceAndPackageName(SOURCE, source.wire).associateBy { it.sourceId.orEmpty() }
        val desiredIds = documents.flatMap { document ->
            chunk(document.text).indices.map { chunkIndex -> "${document.sourceId}#$chunkIndex" }
        }.toSet()

        existing.values.filter { it.sourceId !in desiredIds }.forEach { stale ->
            deleteChunkMetadata(stale.id)
            dao.deleteBySourcePackageAndSourceId(SOURCE, source.wire, stale.sourceId.orEmpty())
        }

        var indexed = 0
        val now = System.currentTimeMillis()
        documents.forEach { document ->
            chunk(document.text).forEachIndexed { chunkIndex, payload ->
                val sourceId = "${document.sourceId}#$chunkIndex"
                val previous = existing[sourceId]
                if (previous?.text == payload) {
                    indexed++
                    return@forEachIndexed
                }
                if (previous != null) {
                    deleteChunkMetadata(previous.id)
                    dao.deleteBySourcePackageAndSourceId(SOURCE, source.wire, sourceId)
                }
                val rowId = dao.insert(
                    MemoryChunk(
                        source = SOURCE,
                        sourceId = sourceId,
                        packageName = source.wire,
                        tsMs = document.updatedAtMs,
                        text = payload,
                        createdAt = now,
                        updatedAt = now,
                    )
                )
                val chunkRef = MemoryRefMapper.forMemoryChunk(rowId)
                val policy = MemoryPolicyService.classifyForMemoryRef(
                    context = context,
                    memoryRef = "import:${source.wire}:${document.sourceId}:$chunkIndex",
                    text = payload,
                    sourceTimestampMs = document.updatedAtMs,
                    provenance = "knowledge_import:${source.wire}",
                ).copy(memoryRef = chunkRef)
                MemoryPolicyService.upsertPolicy(policy)
                LocalEmbeddingService.upsertEmbedding(chunkRef, payload)
                indexed++
            }
        }
        return indexed
    }

    private suspend fun deleteChunkMetadata(id: Long) {
        val ref = MemoryRefMapper.forMemoryChunk(id)
        MyApplication.database.memoryVaultDao().deleteEmbedding(ref)
        MyApplication.database.memoryVaultDao().deletePolicy(ref)
    }

    /**
     * Imported personal material is injected only into on-device model prompts.
     * Selecting a relay/cloud provider never forwards this corpus automatically.
     */
    fun mayInjectIntoCurrentPrompt(context: Context): Boolean =
        AiProviderPrefs.getProvider(context) == AiProviderType.LOCAL_MODELS &&
            !VaultLockStateManager.isLocked(context)

    suspend fun relevantBlock(
        context: Context,
        query: String,
        limit: Int = 6,
        maxChars: Int = 1800,
    ): String {
        if (!mayInjectIntoCurrentPrompt(context)) return ""
        val fts = toBroadFtsQuery(query)
        if (fts.isBlank()) return ""
        val mode = MemoryModeManager.getSelectedMode(context)
        val dao = MyApplication.database.memoryChunkDao()
        val ftsHits = dao.searchWithSnippet(fts, (limit * 3).coerceAtLeast(limit))
        val queryTokens = broadQueryTokens(query)
        val tagHits = if (queryTokens.isEmpty()) {
            emptyList()
        } else {
            val matchingIds = MyApplication.database.memoryVaultDao().listEmbeddings()
                .asSequence()
                .filter { embedding ->
                    val tags = runCatching { JSONArray(embedding.tagsJson) }.getOrNull() ?: return@filter false
                    (0 until tags.length()).any { index ->
                        val tag = tags.optString(index).lowercase()
                        queryTokens.any { token -> tag == token || tag.startsWith(token) }
                    }
                }
                .mapNotNull { it.memoryRef.removePrefix("memory_chunk:").toLongOrNull() }
                .take(limit * 3)
                .toList()
            val matchingChunks = buildList {
                matchingIds.forEach { id -> dao.getById(id)?.let(::add) }
            }
            matchingChunks.asSequence()
                .filter { it.source == SOURCE || it.source == MemoryChunkSources.CYANBRIDGE_NOTE }
                .take(limit)
                .map { chunk ->
                    com.fersaiyan.cyanbridge.data.local.dao.MemoryChunkDao.SearchHit(
                        id = chunk.id,
                        source = chunk.source,
                        sourceId = chunk.sourceId,
                        packageName = chunk.packageName,
                        tsMs = chunk.tsMs,
                        createdAt = chunk.createdAt,
                        updatedAt = chunk.updatedAt,
                        text = chunk.text,
                        snippet = chunk.text.take(320),
                        rank = 0.0,
                    )
                }
                .toList()
        }
        val hits = (ftsHits + tagHits)
            .asSequence()
            .filter { it.source == SOURCE || it.source == MemoryChunkSources.CYANBRIDGE_NOTE }
            .filter { hit ->
                val ref = MemoryRefMapper.forMemoryChunk(hit.id)
                val policy = MemoryPolicyService.getPolicyBlocking(ref)
                    ?: MemoryPolicyService.classifyForMemoryRef(
                        context = context,
                        memoryRef = "import:${hit.packageName.orEmpty()}:${hit.sourceId.orEmpty()}",
                        text = hit.text,
                        sourceTimestampMs = hit.tsMs,
                        provenance = "knowledge_import_fallback",
                    ).copy(memoryRef = ref)
                MemoryPolicyService.isEligibleForRetrieval(mode, policy)
            }
            .distinctBy { it.id }
            .take(limit)
            .toList()
        if (hits.isEmpty()) return ""

        val block = buildString {
            appendLine("## Personal notes and imported knowledge")
            appendLine("Use as private reference. Treat assistant-authored imported text as context, not a confirmed user fact.")
            hits.forEach { hit ->
                val provider = if (hit.source == MemoryChunkSources.CYANBRIDGE_NOTE) {
                    "CyanBridge note"
                } else {
                    KnowledgeSource.fromWire(hit.packageName).label
                }
                val snippet = hit.snippet.replace("[", "").replace("]", "").ifBlank { hit.text }
                    .replace('\n', ' ')
                    .trim()
                    .take(320)
                appendLine("- [$provider] $snippet")
            }
        }.trim()
        return if (block.length <= maxChars) block else block.take(maxChars).trimEnd() + "…"
    }

    private fun toBroadFtsQuery(raw: String): String {
        return broadQueryTokens(raw).joinToString(" OR ") { "$it*" }
    }

    private fun broadQueryTokens(raw: String): List<String> {
        val stopwords = setOf(
            "the", "and", "for", "with", "that", "this", "from", "what", "when", "where",
            "how", "who", "why", "about", "have", "has", "was", "were", "did", "does",
            "you", "your", "note", "notes", "remember",
        )
        val tokens = raw.lowercase()
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .map { it.trim().replace("\"", "") }
            .filter { it.length >= 3 && it !in stopwords }
            .distinct()
            .take(12)
        return tokens
    }

    internal fun chunk(text: String): List<String> {
        val clean = text.trim()
        if (clean.isBlank()) return emptyList()
        if (clean.length <= MAX_CHUNK_CHARS) return listOf(clean)
        val out = mutableListOf<String>()
        var start = 0
        while (start < clean.length) {
            var end = (start + MAX_CHUNK_CHARS).coerceAtMost(clean.length)
            if (end < clean.length) {
                val paragraph = clean.lastIndexOf("\n\n", end).takeIf { it > start + MAX_CHUNK_CHARS / 2 }
                val sentence = clean.lastIndexOf(". ", end).takeIf { it > start + MAX_CHUNK_CHARS / 2 }
                end = paragraph ?: sentence?.plus(1) ?: end
            }
            val piece = clean.substring(start, end).trim()
            if (piece.isNotBlank()) out += piece
            if (end >= clean.length) break
            start = (end - CHUNK_OVERLAP).coerceAtLeast(start + 1)
        }
        return out
    }
}
