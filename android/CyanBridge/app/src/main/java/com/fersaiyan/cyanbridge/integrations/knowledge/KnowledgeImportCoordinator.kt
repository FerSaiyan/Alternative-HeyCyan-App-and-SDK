package com.fersaiyan.cyanbridge.integrations.knowledge

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

object KnowledgeImportCoordinator {

    suspend fun importSelectedFile(
        context: Context,
        uri: Uri,
        expectedSource: KnowledgeSource? = null,
    ): List<KnowledgeImportResult> = withContext(Dispatchers.IO) {
        val name = displayName(context, uri) ?: uri.lastPathSegment.orEmpty()
        val bytes = SafKnowledgeRepository.readBytes(context, uri)
        val parsed = parseFile(bytes, name, expectedSource)
        indexParsed(context, parsed)
    }

    suspend fun syncObsidian(context: Context): KnowledgeImportResult? = withContext(Dispatchers.IO) {
        val tree = KnowledgeIntegrationPrefs.obsidianTree(context) ?: return@withContext null
        val docs = SafKnowledgeRepository.scanObsidian(context, tree)
        val count = ImportedKnowledgeIndex.replaceSource(context, KnowledgeSource.OBSIDIAN, docs)
        KnowledgeImportResult(KnowledgeSource.OBSIDIAN, docs.size, count)
    }

    suspend fun syncImportInbox(context: Context): List<KnowledgeImportResult> = withContext(Dispatchers.IO) {
        val tree = KnowledgeIntegrationPrefs.importInboxTree(context) ?: return@withContext emptyList()
        val grouped = linkedMapOf<KnowledgeSource, MutableList<KnowledgeDocument>>()
        val warnings = linkedMapOf<KnowledgeSource, MutableList<String>>()

        SafKnowledgeRepository.scanImportInbox(context, tree).forEach { entry ->
            runCatching {
                val bytes = SafKnowledgeRepository.readBytes(context, entry.uri)
                parseFile(bytes, entry.name, null)
            }.onSuccess { parsedItems ->
                parsedItems.forEach { parsed ->
                    grouped.getOrPut(parsed.source) { mutableListOf() }.addAll(parsed.documents)
                    warnings.getOrPut(parsed.source) { mutableListOf() }.addAll(parsed.warnings)
                }
            }.onFailure { error ->
                warnings.getOrPut(KnowledgeSource.IMPORT_INBOX) { mutableListOf() }
                    .add("${entry.name}: ${error.message ?: "could not import"}")
            }
        }

        grouped.map { (source, docs) ->
            val deduped = docs.distinctBy { "${it.sourceId}:${it.text.hashCode()}" }
            val chunks = ImportedKnowledgeIndex.replaceSource(context, source, deduped)
            KnowledgeImportResult(source, deduped.size, chunks, warnings[source].orEmpty())
        } + warnings
            .filterKeys { it !in grouped.keys }
            .map { (source, sourceWarnings) -> KnowledgeImportResult(source, 0, 0, sourceWarnings) }
    }

    suspend fun syncAll(context: Context): List<KnowledgeImportResult> = withContext(Dispatchers.IO) {
        val results = mutableListOf<KnowledgeImportResult>()
        syncObsidian(context)?.let(results::add)
        results += syncImportInbox(context)
        val summary = results.joinToString(" · ") { it.summary() }.ifBlank { "Nothing connected yet" }
        KnowledgeIntegrationPrefs.recordSync(context, summary)
        results
    }

    private suspend fun indexParsed(
        context: Context,
        parsed: List<ExternalAiExportParser.Parsed>,
    ): List<KnowledgeImportResult> {
        val grouped = parsed.groupBy { it.source }
        val out = mutableListOf<KnowledgeImportResult>()
        grouped.forEach { (source, entries) ->
            val docs = entries.flatMap { it.documents }.distinctBy { "${it.sourceId}:${it.text.hashCode()}" }
            val chunks = ImportedKnowledgeIndex.replaceSource(context, source, docs)
            out += KnowledgeImportResult(
                source = source,
                documentsImported = docs.size,
                chunksIndexed = chunks,
                warnings = entries.flatMap { it.warnings },
            )
        }
        KnowledgeIntegrationPrefs.recordSync(context, out.joinToString(" · ") { it.summary() })
        return out
    }

    private fun parseFile(
        bytes: ByteArray,
        filename: String,
        expectedSource: KnowledgeSource?,
    ): List<ExternalAiExportParser.Parsed> {
        val lower = filename.lowercase()
        if (lower.endsWith(".zip")) return parseZip(bytes, expectedSource)
        if (lower.endsWith(".md") || lower.endsWith(".txt")) {
            val source = expectedSource ?: KnowledgeSource.IMPORT_INBOX
            val text = bytes.toString(Charsets.UTF_8)
            return listOf(
                ExternalAiExportParser.Parsed(
                    source = source,
                    documents = listOf(
                        KnowledgeDocument(
                            source = source,
                            sourceId = filename,
                            title = filename.substringBeforeLast('.'),
                            text = text,
                            updatedAtMs = System.currentTimeMillis(),
                            userAuthoredText = if (source == KnowledgeSource.OBSIDIAN) text else "",
                        )
                    )
                )
            )
        }
        val parsed = ExternalAiExportParser.parseJson(bytes, sourceHintFilename(filename, expectedSource))
        return listOf(parsed)
    }

    private fun parseZip(bytes: ByteArray, expectedSource: KnowledgeSource?): List<ExternalAiExportParser.Parsed> {
        val parsed = mutableListOf<ExternalAiExportParser.Parsed>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && entry.name.lowercase().endsWith(".json")) {
                    val simple = entry.name.substringAfterLast('/')
                    val likelyConversationFile = simple.contains("conversation", true) ||
                        simple.contains("chat", true) || simple.contains("claude", true)
                    if (likelyConversationFile) {
                        val entryBytes = zip.readBytesLimited(40_000_000)
                        runCatching {
                            ExternalAiExportParser.parseJson(entryBytes, sourceHintFilename(simple, expectedSource))
                        }.getOrNull()?.let(parsed::add)
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        require(parsed.isNotEmpty()) {
            "No recognizable ChatGPT/Claude conversation JSON was found in this ZIP."
        }
        return parsed
    }

    private fun ZipInputStream.readBytesLimited(maxBytes: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val read = read(buffer)
            if (read <= 0) break
            total += read
            require(total <= maxBytes) { "Conversation JSON is too large for a single import entry." }
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    private fun sourceHintFilename(filename: String, expectedSource: KnowledgeSource?): String {
        return when (expectedSource) {
            KnowledgeSource.CHATGPT -> "chatgpt-$filename"
            KnowledgeSource.CLAUDE -> "claude-$filename"
            else -> filename
        }
    }

    private fun displayName(context: Context, uri: Uri): String? {
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) null else cursor.getString(0)
            }
        }.getOrNull()
    }
}
