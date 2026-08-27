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
 *
 * An existing vault uses the granted tree as its root. A vault created by CyanBridge keeps
 * permission to the user-selected parent tree and stores the created vault's document id as the
 * logical root. This lets CyanBridge create a normal Obsidian-compatible folder without asking
 * for all-files access.
 */
object SafKnowledgeRepository {
    private const val MAX_TEXT_BYTES = 1_500_000
    private const val MAX_FILES_PER_SCAN = 2500
    private const val MANAGED_NOTES_DIR = "CyanBridge"

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

    data class CreatedVault(
        val permissionTreeUri: Uri,
        val rootDocumentId: String,
        val displayName: String,
    )

    data class PersistedAccess(
        val canRead: Boolean,
        val canWrite: Boolean,
    )

    fun persistTreePermission(context: Context, uri: Uri, writable: Boolean): Boolean {
        var flags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
        if (writable) flags = flags or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { context.contentResolver.takePersistableUriPermission(uri, flags) }
        return hasPersistedTreePermission(context, uri, writable)
    }

    fun persistedAccess(context: Context, uri: Uri): PersistedAccess {
        val permission = context.contentResolver.persistedUriPermissions.firstOrNull { it.uri == uri }
        return PersistedAccess(
            canRead = permission?.isReadPermission == true,
            canWrite = permission?.isWritePermission == true,
        )
    }

    fun hasPersistedTreePermission(context: Context, uri: Uri, writable: Boolean): Boolean {
        val access = persistedAccess(context, uri)
        return access.canRead && (!writable || access.canWrite)
    }

    /** Create a plain Markdown Obsidian vault folder under a parent chosen by the user. */
    fun createObsidianVault(context: Context, parentTreeUri: Uri, requestedName: String): CreatedVault {
        require(hasPersistedTreePermission(context, parentTreeUri, writable = true)) {
            "CyanBridge needs read and write access to the selected parent folder to create a vault."
        }
        val resolver = context.contentResolver
        val parentRootId = DocumentsContract.getTreeDocumentId(parentTreeUri)
        val parentRoot = DocumentsContract.buildDocumentUriUsingTree(parentTreeUri, parentRootId)
        val safeName = sanitizeDirectoryName(requestedName.ifBlank { "CyanBridge Vault" })
        val siblings = queryChildren(resolver, parentTreeUri, parentRootId, "")
        require(siblings.none { it.isDirectory && it.name.equals(safeName, ignoreCase = true) }) {
            "A folder named '$safeName' already exists there. Choose it as an existing vault or use another name."
        }
        val vaultDocument = DocumentsContract.createDocument(
            resolver,
            parentRoot,
            DocumentsContract.Document.MIME_TYPE_DIR,
            safeName,
        ) ?: error("Could not create the Obsidian vault folder.")
        val vaultRootId = DocumentsContract.getDocumentId(vaultDocument)
        ensureManagedNotesDirectory(resolver, parentTreeUri, vaultRootId)
        return CreatedVault(parentTreeUri, vaultRootId, safeName)
    }

    fun scanObsidian(
        context: Context,
        treeUri: Uri,
        rootDocumentId: String? = null,
    ): List<KnowledgeDocument> {
        return listTree(context.contentResolver, treeUri, resolveRootId(treeUri, rootDocumentId))
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

    fun listManagedObsidianNotes(
        context: Context,
        treeUri: Uri,
        rootDocumentId: String? = null,
        limit: Int = 30,
    ): List<SafEntry> {
        val resolver = context.contentResolver
        val rootId = resolveRootId(treeUri, rootDocumentId)
        val managedDir = findManagedNotesDirectory(resolver, treeUri, rootId) ?: return emptyList()
        return queryChildren(resolver, treeUri, managedDir.documentId, MANAGED_NOTES_DIR)
            .asSequence()
            .filterNot { it.isDirectory }
            .filter { it.name.endsWith(".md", ignoreCase = true) }
            .sortedByDescending { it.lastModified }
            .take(limit)
            .toList()
    }

    fun readManagedObsidianNote(context: Context, entry: SafEntry): String =
        readText(context.contentResolver, entry.uri) ?: error("Could not read ${entry.name}.")

    fun scanImportInbox(context: Context, treeUri: Uri): List<SafEntry> =
        listTree(context.contentResolver, treeUri, DocumentsContract.getTreeDocumentId(treeUri))
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

    /**
     * Save a CyanBridge-managed Markdown note. The source .md file intentionally remains plain
     * Markdown in the external Obsidian vault; CyanBridge Memory Vault encryption does not alter it.
     */
    fun saveObsidianNote(
        context: Context,
        treeUri: Uri,
        title: String,
        markdown: String,
        rootDocumentId: String? = null,
        existingUri: Uri? = null,
    ): Uri {
        require(hasPersistedTreePermission(context, treeUri, writable = true)) {
            "Write access to this Obsidian location is no longer available. Reconnect the vault."
        }
        val resolver = context.contentResolver
        val rootId = resolveRootId(treeUri, rootDocumentId)
        val cyanDir = ensureManagedNotesDirectory(resolver, treeUri, rootId)
        val safeTitle = sanitizeFileTitle(title)
        val fileName = if (safeTitle.endsWith(".md", true)) safeTitle else "$safeTitle.md"

        var target = existingUri
        if (target == null) {
            val dirId = DocumentsContract.getDocumentId(cyanDir)
            val existing = queryChildren(resolver, treeUri, dirId, MANAGED_NOTES_DIR)
                .firstOrNull { !it.isDirectory && it.name.equals(fileName, ignoreCase = true) }
            target = existing?.uri ?: DocumentsContract.createDocument(
                resolver,
                cyanDir,
                "text/markdown",
                fileName,
            ) ?: error("Could not create the note in the Obsidian vault.")
        } else {
            val currentName = queryDisplayName(resolver, target)
            if (currentName != null && !currentName.equals(fileName, ignoreCase = false)) {
                target = DocumentsContract.renameDocument(resolver, target, fileName) ?: target
            }
        }

        resolver.openOutputStream(target, "wt")?.use { output ->
            output.write(markdown.toByteArray(Charsets.UTF_8))
        } ?: error("Could not write the Obsidian note.")
        return target
    }

    private fun resolveRootId(treeUri: Uri, rootDocumentId: String?): String =
        rootDocumentId?.takeIf { it.isNotBlank() } ?: DocumentsContract.getTreeDocumentId(treeUri)

    private fun ensureManagedNotesDirectory(
        resolver: ContentResolver,
        treeUri: Uri,
        rootDocumentId: String,
    ): Uri {
        findManagedNotesDirectory(resolver, treeUri, rootDocumentId)?.let { return it.uri }
        val root = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootDocumentId)
        return DocumentsContract.createDocument(
            resolver,
            root,
            DocumentsContract.Document.MIME_TYPE_DIR,
            MANAGED_NOTES_DIR,
        ) ?: error("Could not create the CyanBridge folder in the Obsidian vault.")
    }

    private fun findManagedNotesDirectory(
        resolver: ContentResolver,
        treeUri: Uri,
        rootDocumentId: String,
    ): SafEntry? = queryChildren(resolver, treeUri, rootDocumentId, "")
        .firstOrNull { it.isDirectory && it.name == MANAGED_NOTES_DIR }

    private fun sanitizeDirectoryName(name: String): String = name.trim()
        .replace(Regex("[\\/:*?\"<>|]"), "-")
        .trim('.', ' ')
        .take(80)
        .ifBlank { "CyanBridge Vault" }

    private fun sanitizeFileTitle(title: String): String = title.trim().ifBlank { "CyanBridge note" }
        .replace(Regex("[\\/:*?\"<>|]"), "-")
        .take(100)

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

    private fun listTree(
        resolver: ContentResolver,
        treeUri: Uri,
        startDocumentId: String,
    ): List<SafEntry> {
        val queue = ArrayDeque<Pair<String, String>>()
        queue.add(startDocumentId to "")
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

    private fun queryDisplayName(resolver: ContentResolver, uri: Uri): String? = runCatching {
        resolver.query(
            uri,
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    }.getOrNull()

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
