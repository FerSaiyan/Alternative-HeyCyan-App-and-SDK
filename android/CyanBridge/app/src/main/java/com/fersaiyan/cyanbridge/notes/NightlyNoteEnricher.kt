package com.fersaiyan.cyanbridge.notes

import android.content.Context
import com.fersaiyan.cyanbridge.agent.LocalAgentPrefs
import com.fersaiyan.cyanbridge.agent.ProSubscriptionAiPrefs
import com.fersaiyan.cyanbridge.ai.router.AiProviderPrefs
import com.fersaiyan.cyanbridge.ai.router.AiProviderType
import com.fersaiyan.cyanbridge.ai.router.CliRelayClient
import com.fersaiyan.cyanbridge.integrations.knowledge.KnowledgeIntegrationPrefs
import com.fersaiyan.cyanbridge.integrations.knowledge.ImportedKnowledgeIndex
import com.fersaiyan.cyanbridge.localmodels.provider.LocalModelRequestPriority
import com.fersaiyan.cyanbridge.localmodels.provider.LocalModelsProvider
import com.fersaiyan.cyanbridge.memoryvault.LocalEmbeddingService
import com.fersaiyan.cyanbridge.shared.settings.AgentProviderType
import com.fersaiyan.cyanbridge.ui.MyApplication
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

class NightlyEnrichmentDeferredException : IllegalStateException("Nightly enrichment paused for power or foreground work")

/** Adds bounded AI tags to changed notes while leaving user-authored tags untouched. */
object NightlyNoteEnricher {
    private const val MAX_NOTES_PER_RUN = 30
    private const val MAX_INPUT_CHARS = 6_000
    private const val MAX_OUTPUT_TOKENS = 96
    private val localModels = LocalModelsProvider()

    data class Result(val indexed: Int, val tagged: Int, val importedTagged: Int)

    suspend fun run(
        context: Context,
        shouldContinue: () -> Boolean = { true },
    ): Result {
        val dao = MyApplication.database.noteDao()
        val route = resolveRoute(context)
        var indexed = 0
        var tagged = 0

        for (note in dao.getAllNotesOnce()) {
            if (!shouldContinue()) throw NightlyEnrichmentDeferredException()
            runCatching { NoteKnowledgeIndex.index(context, note) }
            indexed++

            val hash = contentHash(note.title, note.summary, note.tags)
            if (note.taggedContentHash == hash && note.taggingModelVersion == route.version) continue
            if (tagged >= MAX_NOTES_PER_RUN) continue

            val enriched = generateTags(context, note.title, note.summary, route)
            val updated = note.copy(
                generatedTags = enriched.tags.joinToString(", ").ifBlank { null },
                taggedContentHash = hash,
                taggingModelVersion = enriched.version,
                taggedAt = System.currentTimeMillis(),
            )
            dao.updateNote(updated)
            runCatching { NoteKnowledgeIndex.index(context, updated) }
            tagged++
        }

        var importedTagged = 0
        val chunkDao = MyApplication.database.memoryChunkDao()
        val importedDocuments = chunkDao.listBySource(ImportedKnowledgeIndex.SOURCE)
            .groupBy { it.sourceId.orEmpty().substringBeforeLast('#') }
            .values
        for (chunks in importedDocuments) {
            if (!shouldContinue()) throw NightlyEnrichmentDeferredException()
            val latestUpdate = chunks.maxOfOrNull { it.updatedAt } ?: 0L
            val alreadyTagged = chunks.all { chunk ->
                val embedding = MyApplication.database.memoryVaultDao()
                    .getEmbedding(com.fersaiyan.cyanbridge.memoryvault.MemoryRefMapper.forMemoryChunk(chunk.id))
                embedding?.modelVersion == route.version && embedding.updatedAt >= latestUpdate
            }
            if (alreadyTagged) continue
            if (importedTagged >= MAX_NOTES_PER_RUN) break

            val sourceId = chunks.firstOrNull()?.sourceId.orEmpty().substringBeforeLast('#')
            val text = chunks.joinToString("\n") { it.text }.take(MAX_INPUT_CHARS)
            val enriched = generateTags(context, sourceId, text, route)
            chunks.forEach { chunk ->
                LocalEmbeddingService.upsertEmbedding(
                    memoryRef = com.fersaiyan.cyanbridge.memoryvault.MemoryRefMapper.forMemoryChunk(chunk.id),
                    text = chunk.text,
                    tags = enriched.tags,
                    modelVersion = enriched.version,
                )
            }
            importedTagged++
        }
        return Result(indexed = indexed, tagged = tagged, importedTagged = importedTagged)
    }

    private data class Route(val type: Type, val version: String)
    private enum class Type { LOCAL, PRO, KEYWORD }
    private data class Generated(val tags: List<String>, val version: String)

    private fun resolveRoute(context: Context): Route {
        val cloudAllowed = KnowledgeIntegrationPrefs.allowCloudEnrichment(context)
        return when (LocalAgentPrefs.getProviderType(context)) {
            AgentProviderType.LOCAL_AGENT -> Route(Type.LOCAL, "note_tags_local_v1")
            AgentProviderType.PRO_SUBSCRIPTION -> if (cloudAllowed) {
                Route(Type.PRO, "note_tags_pro_v1")
            } else {
                Route(Type.KEYWORD, "note_tags_keyword_v1")
            }
            AgentProviderType.TASKER -> when (AiProviderPrefs.getProvider(context)) {
                AiProviderType.LOCAL_MODELS -> Route(Type.LOCAL, "note_tags_local_v1")
                AiProviderType.CLI_RELAY -> if (cloudAllowed) {
                    Route(Type.PRO, "note_tags_pro_v1")
                } else {
                    Route(Type.KEYWORD, "note_tags_keyword_v1")
                }
                else -> Route(Type.KEYWORD, "note_tags_keyword_v1")
            }
        }
    }

    private suspend fun generateTags(context: Context, title: String, summary: String, route: Route): Generated {
        if (route.type == Type.KEYWORD) return Generated(keywordTags("$title\n$summary"), route.version)
        val prompt = buildString {
            appendLine("Generate 3 to 8 concise tags for this personal note.")
            appendLine("Prefer concrete topics, projects, people, and note type. Avoid synonyms and generic tags.")
            appendLine("Return JSON only: {\"tags\":[\"tag-one\",\"tag-two\"]}")
            appendLine("TITLE: ${title.trim().take(300)}")
            appendLine("NOTE:")
            append(summary.trim().take(MAX_INPUT_CHARS))
        }
        val raw = runCatching {
            when (route.type) {
                Type.LOCAL -> localModels.streamChat(
                    context = context,
                    messages = listOf(mapOf("role" to "User", "content" to prompt)),
                    requestPriority = LocalModelRequestPriority.LOW,
                    maxTokens = MAX_OUTPUT_TOKENS,
                )
                Type.PRO -> CliRelayClient.voiceQuery(
                    context = context,
                    prompt = prompt,
                    modelOverride = ProSubscriptionAiPrefs.getTasksModel(context),
                ).getOrThrow()
                Type.KEYWORD -> ""
            }
        }.getOrNull()
        val parsed = raw?.let(::parseTags).orEmpty()
        return if (parsed.isNotEmpty()) {
            Generated(parsed, route.version)
        } else {
            Generated(keywordTags("$title\n$summary"), "note_tags_keyword_v1")
        }
    }

    internal fun parseTags(raw: String): List<String> {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return emptyList()
        val array = runCatching { JSONObject(raw.substring(start, end + 1)).optJSONArray("tags") }
            .getOrNull() ?: JSONArray()
        return normalizeTags((0 until array.length()).map { array.optString(it) })
    }

    internal fun normalizeTags(values: List<String>): List<String> = values
        .map { it.trim().removePrefix("#").lowercase() }
        .map { it.replace(Regex("\\s+"), "-") }
        .map { it.replace(Regex("[^\\p{L}\\p{N}_/-]"), "-").trim('-') }
        .filter { it.length in 2..40 }
        .distinct()
        .take(8)

    private fun keywordTags(text: String): List<String> =
        normalizeTags(LocalEmbeddingService.extractTags(text))

    internal fun contentHash(title: String, summary: String, userTags: String?): String {
        val payload = "$title\n$summary\n${userTags.orEmpty()}".toByteArray(Charsets.UTF_8)
        return MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) }
    }
}
