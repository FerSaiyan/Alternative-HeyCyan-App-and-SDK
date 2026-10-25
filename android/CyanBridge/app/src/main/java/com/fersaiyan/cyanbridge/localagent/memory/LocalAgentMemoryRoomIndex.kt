package com.fersaiyan.cyanbridge.localagent.memory

import android.content.Context
import android.util.Log
import com.fersaiyan.cyanbridge.data.local.entity.MemoryChunk
import com.fersaiyan.cyanbridge.data.local.entity.MemoryChunkSources
import com.fersaiyan.cyanbridge.memoryvault.MemoryPolicyService
import com.fersaiyan.cyanbridge.memoryvault.MemoryRefMapper
import com.fersaiyan.cyanbridge.memoryvault.MemoryVaultBootstrap
import com.fersaiyan.cyanbridge.memoryvault.VaultLockStateManager
import com.fersaiyan.cyanbridge.ui.MyApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Room-backed index for Local Agent memory.
 *
 * This complements the JSONL file store in [LocalAgentMemoryStore].
 * - Files remain the simple source-of-truth for inspection/export.
 * - Room FTS5 provides fast lookup for agent retrieval.
 */
object LocalAgentMemoryRoomIndex {
    private const val TAG = "LocalAgentMemoryIdx"

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Fire-and-forget indexing for a screen capture.
     *
     * Safe to call from non-suspending contexts (e.g., Tasker AutoDiary broadcasts).
     */
    fun indexScreenCaptureAsync(
        context: Context,
        packageName: String,
        text: String,
        tsMs: Long,
    ) {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return

        ioScope.launch {
            runCatching {
                MemoryVaultBootstrap.ensureInitialized(context)
                val now = System.currentTimeMillis()
                val id = MyApplication.database.memoryChunkDao().insert(
                    MemoryChunk(
                        source = MemoryChunkSources.SCREEN_CAPTURE,
                        sourceId = null,
                        packageName = packageName,
                        tsMs = tsMs,
                        text = trimmed,
                        createdAt = now,
                        updatedAt = now,
                    )
                )

                val ref = MemoryRefMapper.forMemoryChunk(id)
                val policy = MemoryPolicyService.classifyForMemoryRef(
                    context = context,
                    memoryRef = ref,
                    text = text,
                    sourceTimestampMs = tsMs,
                    provenance = "accessibility_screen_capture",
                )
                MemoryPolicyService.upsertPolicy(policy)
            }.onFailure {
                Log.w(TAG, "Indexing failed: ${it.message}")
            }
        }
    }

    /**
     * A simple keyword-to-FTS query normalizer.
     *
     * For most plain text queries, we AND tokens and add prefix matching.
     * Example: "open ai" -> "open* AND ai*".
     */
    fun toFtsQuery(raw: String): String {
        val tokens = raw
            .trim()
            .split(Regex("\\s+"))
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .take(12) // prevent absurdly long queries

        if (tokens.isEmpty()) return ""

        return tokens.joinToString(" AND ") { t ->
            val cleaned = t.replace("\"", "").replace("'", "")
            if (cleaned.endsWith("*") || cleaned.contains(":") || cleaned.equals("AND", true) || cleaned.equals("OR", true)) {
                cleaned
            } else {
                "$cleaned*"
            }
        }
    }

    suspend fun searchScreenCaptures(
        query: String,
        limit: Int = 20,
        context: Context = MyApplication.CONTEXT,
    ): List<com.fersaiyan.cyanbridge.data.local.dao.MemoryChunkDao.SearchHit> {
        if (VaultLockStateManager.isLocked(context)) return emptyList()
        val q = toFtsQuery(query)
        if (q.isBlank()) return emptyList()
        val mode = com.fersaiyan.cyanbridge.memoryvault.MemoryModeManager.getSelectedMode(context)
        return MyApplication.database.memoryChunkDao().searchWithSnippet(q, limit)
            .filter { it.source == MemoryChunkSources.SCREEN_CAPTURE }
            .filter { hit ->
                val ref = MemoryRefMapper.forMemoryChunk(hit.id)
                val policy = MemoryPolicyService.getPolicyBlocking(ref)
                    ?: MemoryPolicyService.classifyForMemoryRef(
                        context = context,
                        memoryRef = ref,
                        text = hit.text,
                        sourceTimestampMs = hit.tsMs,
                        provenance = "fts_fallback_policy",
                    )
                MemoryPolicyService.isEligibleForRetrieval(mode, policy)
            }
    }
}
