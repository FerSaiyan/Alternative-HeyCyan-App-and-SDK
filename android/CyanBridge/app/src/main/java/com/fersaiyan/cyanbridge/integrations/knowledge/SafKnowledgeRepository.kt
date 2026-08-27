package com.fersaiyan.cyanbridge.integrations.knowledge

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract

/** Scoped SAF writer compatible with the Obsidian integration branch's managed folder. */
object SafKnowledgeRepository {
    private const val MANAGED_NOTES_DIR = "CyanBridge"

    fun persistTreePermission(context: Context, uri: Uri): Boolean {
        val flags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
            android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { context.contentResolver.takePersistableUriPermission(uri, flags) }
        return hasPersistedTreePermission(context, uri, writable = true)
    }

    fun hasPersistedTreePermission(context: Context, uri: Uri, writable: Boolean): Boolean {
        val permission = context.contentResolver.persistedUriPermissions.firstOrNull { it.uri == uri }
        return permission?.isReadPermission == true &&
            (!writable || permission.isWritePermission)
    }

    fun saveObsidianNote(
        context: Context,
        treeUri: Uri,
        title: String,
        markdown: String,
        rootDocumentId: String? = null,
    ): Uri {
        require(hasPersistedTreePermission(context, treeUri, writable = true)) {
            "Write access to the Obsidian vault is no longer available. Reconnect it from Chats."
        }
        val resolver = context.contentResolver
        val rootId = rootDocumentId?.takeIf { it.isNotBlank() }
            ?: DocumentsContract.getTreeDocumentId(treeUri)
        val managedDirectory = findChild(
            context = context,
            treeUri = treeUri,
            parentDocumentId = rootId,
            displayName = MANAGED_NOTES_DIR,
            directory = true,
        ) ?: DocumentsContract.createDocument(
            resolver,
            DocumentsContract.buildDocumentUriUsingTree(treeUri, rootId),
            DocumentsContract.Document.MIME_TYPE_DIR,
            MANAGED_NOTES_DIR,
        ) ?: error("Could not create the CyanBridge folder in the Obsidian vault")

        val managedId = DocumentsContract.getDocumentId(managedDirectory)
        val fileName = "${sanitizeFileTitle(title)}.md"
        val target = findChild(
            context = context,
            treeUri = treeUri,
            parentDocumentId = managedId,
            displayName = fileName,
            directory = false,
        ) ?: DocumentsContract.createDocument(
            resolver,
            managedDirectory,
            "text/markdown",
            fileName,
        ) ?: error("Could not create the meeting note in the Obsidian vault")

        resolver.openOutputStream(target, "wt")?.use { output ->
            output.write(markdown.toByteArray(Charsets.UTF_8))
        } ?: error("Could not write the meeting note in the Obsidian vault")
        return target
    }

    private fun findChild(
        context: Context,
        treeUri: Uri,
        parentDocumentId: String,
        displayName: String,
        directory: Boolean,
    ): Uri? {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocumentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        return runCatching {
            context.contentResolver.query(children, projection, null, null, null)?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameIndex) ?: continue
                    val mime = cursor.getString(mimeIndex).orEmpty()
                    val isDirectory = mime == DocumentsContract.Document.MIME_TYPE_DIR
                    if (name.equals(displayName, ignoreCase = true) && isDirectory == directory) {
                        return@use DocumentsContract.buildDocumentUriUsingTree(
                            treeUri,
                            cursor.getString(idIndex),
                        )
                    }
                }
                null
            }
        }.getOrNull()
    }

    internal fun sanitizeFileTitle(title: String): String = title.trim()
        .ifBlank { "Meeting notes" }
        .replace(Regex("[\\/:*?\"<>|]"), "-")
        .trim('.', ' ')
        .take(100)
        .ifBlank { "Meeting notes" }
}
