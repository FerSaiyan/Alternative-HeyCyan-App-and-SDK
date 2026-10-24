package com.fersaiyan.cyanbridge.integrations.knowledge

import android.content.Context
import android.net.Uri
import com.fersaiyan.cyanbridge.shared.prefs.MemoryPrefs.Knowledge
import com.fersaiyan.cyanbridge.shared.prefs.edit
import com.fersaiyan.cyanbridge.shared.prefs.get
import com.fersaiyan.cyanbridge.shared.prefs.set

object KnowledgeIntegrationPrefs {
    data class ObsidianVaultAccess(
        val permissionTreeUri: Uri,
        val rootDocumentId: String?,
        val displayName: String?,
    )

    fun obsidianTree(context: Context): Uri? =
        Knowledge.obsidianTreeUri[context]?.takeIf { it.isNotBlank() }?.let(Uri::parse)

    fun obsidianRootDocumentId(context: Context): String? =
        Knowledge.obsidianRootDocumentId[context]?.takeIf { it.isNotBlank() }

    fun obsidianDisplayName(context: Context): String? =
        Knowledge.obsidianDisplayName[context]?.takeIf { it.isNotBlank() }

    fun obsidianVault(context: Context): ObsidianVaultAccess? {
        val tree = obsidianTree(context) ?: return null
        return ObsidianVaultAccess(
            permissionTreeUri = tree,
            rootDocumentId = obsidianRootDocumentId(context),
            displayName = obsidianDisplayName(context),
        )
    }

    /** Existing vault: the selected tree itself is the vault root. */
    fun setObsidianTree(context: Context, uri: Uri?) {
        setObsidianVault(context, uri, rootDocumentId = null, displayName = null)
    }

    /**
     * Store the scoped tree permission plus an optional logical root document id. The latter is
     * used when CyanBridge creates a new vault folder under a user-selected parent directory.
     */
    fun setObsidianVault(
        context: Context,
        permissionTreeUri: Uri?,
        rootDocumentId: String?,
        displayName: String?,
    ) {
        Knowledge.edit(context) {
            this[Knowledge.obsidianTreeUri] = permissionTreeUri?.toString()
            this[Knowledge.obsidianRootDocumentId] = rootDocumentId
            this[Knowledge.obsidianDisplayName] = displayName
        }
    }

    fun importInboxTree(context: Context): Uri? =
        Knowledge.importInboxTreeUri[context]?.takeIf { it.isNotBlank() }?.let(Uri::parse)

    fun setImportInboxTree(context: Context, uri: Uri?) {
        Knowledge.importInboxTreeUri[context] = uri?.toString()
    }

    fun autoSyncEnabled(context: Context): Boolean = Knowledge.autoSync[context]

    fun setAutoSyncEnabled(context: Context, enabled: Boolean) {
        Knowledge.autoSync[context] = enabled
    }

    fun allowCloudEnrichment(context: Context): Boolean =
        Knowledge.allowCloudEnrichment[context]

    fun setAllowCloudEnrichment(context: Context, enabled: Boolean) {
        Knowledge.allowCloudEnrichment[context] = enabled
    }

    fun lastSyncMs(context: Context): Long = Knowledge.lastSyncMs[context]

    fun lastSummary(context: Context): String = Knowledge.lastSummary[context]

    fun recordSync(context: Context, summary: String, nowMs: Long = System.currentTimeMillis()) {
        Knowledge.edit(context) {
            this[Knowledge.lastSyncMs] = nowMs
            this[Knowledge.lastSummary] = summary.take(500)
        }
    }
}
