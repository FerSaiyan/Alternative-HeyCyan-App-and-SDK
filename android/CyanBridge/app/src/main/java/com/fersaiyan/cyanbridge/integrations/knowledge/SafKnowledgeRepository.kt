package com.fersaiyan.cyanbridge.integrations.knowledge

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.ByteArrayOutputStream
import java.util.ArrayDeque

/**
 * Storage Access Framework bridge used for Obsidian and a user-selected import inbox.
 * CyanBridge never requests broad storage access; it can only see the tree the user grants.
 */
object SafKnowledgeRepository {
    private const val MAX_TEXT_BYTES = 1_500_000
    private const val MAX_FILES_PER_SCAN = 2500

    data class SafEntry(
        val uri: Uri,
        val documentId: String,
        val name: String,
        val mimeType: String,
        val lastModified: Long,
        val size: Long,
        val relativePath: String,
        val isDirectory: Boolean,
    )

    fun persistTreePermission(context: Context, uri: Uri, writable: Boolean) {
        var flags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
        if (writable) flags = flags or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { context.contentResolver.takePersistableUriPermission(uri, flags) }
    }

    fun scanObsidian(context: Context, treeUri: Uri): List<KnowledgeDocument> {
        return listTree(context.contentResolver, treeUri)
            .asSequence()
            .filterNot { it.isDirectory }
            .filter { it.name.endsWith(".md", ignoreCase = true) }
            .filterNot { it.relativePath.split('/').any { part -> part == ".obsidian" || part.startsWith(".") } }
            .mapNotNull { entry ->
                val text = readText(context.contentResolver, entry.uri) ?: return@mapNotNull null
                KnowledgeDocument(
                    source = KnowledgeSource.OBSIDIAN,
                    sourceId = entry.relativePath,
                    title = entry.name.removeSuffix(".md"),
                    text = text,
                    updatedAtMs = entry.lastModified.takeIf { it > 0 } ?: System.currentTimeMillis(),
                    userAuthoredText = text,
                )
            }
            .toList()
    }

    fun scanImportInbox(context: Context, treeUri: Uri): List<SafEntry> =
        listTree(context.contentResolver, treeUri)
            .filterNot { it.isDirectory }
            .filter { entry ->
                val name = entry.name.lowercase()
                name.endsWith(".json") || name.endsWith(".zip") || name.endsWith(".md") || name.endsWith(".txt")
            }

    fun readBytes(context: Context, uri: Uri, maxBytes: Int = 30_000_000): ByteArray {
        val resolver = context.contentResolver
        resolver.openInputStream(uri)?.use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                total += read
                require(total <= maxBytes) { "Selected file is larger than ${maxBytes / 1_000_000} MB." }
                out.write(buffer, 0, read)
            }
            return out.toByteArray()
        }
        error("Unable to open selected file.")
    }

    fun saveObsidianNote(
        context: Context,
        treeUri: Uri,
        title: String,
        markdown: String,
    ): Uri {
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        val root = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootId)
        val rootChildren = queryChildren(context.contentResolver, treeUri, rootId, "")
        val cyanDir = rootChildren.firstOrNull { it.isDirectory && it.name == "CyanBridge" }?.uri
            ?: DocumentsContract.createDocument(
                context.contentResolver,
                root,
                DocumentsContract.Document.MIME_TYPE_DIR,
                "CyanBridge",
            )
            ?: error("Could not create the CyanBridge folder in the Obsidian vault.")

        val safeTitle = title.trim().ifBlank { "CyanBridge note" }
            .replace(Regex("[\\/:*?\"<>|]"), "-")
            .take(100)
        val fileName = if (safeTitle.endsWith(".md", true)) safeTitle else "$safeTitle.md"
        val dirId = DocumentsContract.getDocumentId(cyanDir)
        val existing = queryChildren(context.contentResolver, treeUri, dirId, "CyanBridge")
            .firstOrNull { !it.isDirectory && it.name == fileName }
        val target = existing?.uri ?: DocumentsContract.createDocument(
            context.contentResolver,
            cyanDir,
            "text/markdown",
            fileName,
        ) ?: error("Could not create the note in the Obsidian vault.")

        context.contentResolver.openOutputStream(target, "wt")?.use { output ->
            output.write(markdown.toByteArray(Charsets.UTF_8))
        } ?: error("Could not write the Obsidian note.")
        return target
    }

    private fun readText(resolver: ContentResolver, uri: Uri): String? = runCatching {
        resolver.openInputStream(uri)?.use { input ->
            val bytes = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                total += read
                if (total > MAX_TEXT_BYTES) break
                bytes.write(buffer, 0, read)
            }
            bytes.toString(Charsets.UTF_8.name())
        }
    }.getOrNull()

    private fun listTree(resolver: ContentResolver, treeUri: Uri): List<SafEntry> {
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        val queue = ArrayDeque<Pair<String, String>>()
        queue.add(rootId to "")
        val out = mutableListOf<SafEntry>()
        while (queue.isNotEmpty() && out.size < MAX_FILES_PER_SCAN) {
            val (parentId, parentPath) = queue.removeFirst()
            val children = queryChildren(resolver, treeUri, parentId, parentPath)
            children.forEach { entry ->
                if (out.size >= MAX_FILES_PER_SCAN) return@forEach
                out += entry
                if (entry.isDirectory) queue.add(entry.documentId to entry.relativePath)
            }
        }
        return out
    }

    private fun queryChildren(
        resolver: ContentResolver,
        treeUri: Uri,
        parentDocumentId: String,
        parentPath: String,
    ): List<SafEntry> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocumentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_SIZE,
        )
        return runCatching {
            resolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val modifiedIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                val sizeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
                buildList {
                    while (cursor.moveToNext()) {
                        val id = cursor.getString(idIndex)
                        val name = cursor.getString(nameIndex) ?: continue
                        val mime = cursor.getString(mimeIndex).orEmpty()
                        val relative = if (parentPath.isBlank()) name else "$parentPath/$name"
                        add(
                            SafEntry(
                                uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id),
                                documentId = id,
                                name = name,
                                mimeType = mime,
                                lastModified = if (cursor.isNull(modifiedIndex)) 0L else cursor.getLong(modifiedIndex),
                                size = if (cursor.isNull(sizeIndex)) 0L else cursor.getLong(sizeIndex),
                                relativePath = relative,
                                isDirectory = mime == DocumentsContract.Document.MIME_TYPE_DIR,
                            )
                        )
                    }
                }
            }.orEmpty()
        }.getOrDefault(emptyList())
    }
}
