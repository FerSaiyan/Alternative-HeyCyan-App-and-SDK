package com.fersaiyan.cyanbridge.integrations.knowledge

import android.content.Context
import com.fersaiyan.cyanbridge.ai.router.AiProviderPrefs
import com.fersaiyan.cyanbridge.ai.router.AiProviderType
import com.fersaiyan.cyanbridge.data.local.entity.MemoryChunk
import com.fersaiyan.cyanbridge.localagent.memory.LocalAgentMemoryRoomIndex
import com.fersaiyan.cyanbridge.memoryvault.MemoryModeManager
import com.fersaiyan.cyanbridge.memoryvault.MemoryPolicyService
import com.fersaiyan.cyanbridge.memoryvault.MemoryRefMapper
import com.fersaiyan.cyanbridge.memoryvault.MemoryVaultBootstrap
import com.fersaiyan.cyanbridge.memoryvault.VaultLockStateManager
import com.fersaiyan.cyanbridge.ui.MyApplication

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
        dao.deleteBySourceAndPackageName(SOURCE, source.wire)

        var indexed = 0
        val now = System.currentTimeMillis()
        documents.forEach { document ->
            chunk(document.text).forEachIndexed { chunkIndex, payload ->
                val rowId = dao.insert(
                    MemoryChunk(
                        source = SOURCE,
                        sourceId = "${document.sourceId}#$chunkIndex",
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
                indexed++
            }
        }
        return indexed
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
        val fts = LocalAgentMemoryRoomIndex.toFtsQuery(query)
        if (fts.isBlank()) return ""
        val mode = MemoryModeManager.getSelectedMode(context)
        val hits = MyApplication.database.memoryChunkDao().searchWithSnippet(fts, (limit * 3).coerceAtLeast(limit))
            .asSequence()
            .filter { it.source == SOURCE }
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
            .take(limit)
            .toList()
        if (hits.isEmpty()) return ""

        val block = buildString {
            appendLine("## Imported personal knowledge")
            appendLine("Use as private reference. Treat assistant-authored imported text as context, not a confirmed user fact.")
            hits.forEach { hit ->
                val provider = KnowledgeSource.fromWire(hit.packageName).label
                val snippet = hit.snippet.replace("[", "").replace("]", "").ifBlank { hit.text }
                    .replace('\n', ' ')
                    .trim()
                    .take(320)
                appendLine("- [$provider] $snippet")
            }
        }.trim()
        return if (block.length <= maxChars) block else block.take(maxChars).trimEnd() + "…"
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
