package com.fersaiyan.cyanbridge.integrations.knowledge

import android.content.Context
import android.net.Uri

object KnowledgeIntegrationPrefs {
    private const val PREFS = "knowledge_integrations"
    private const val KEY_OBSIDIAN_TREE = "obsidian_tree_uri"
    private const val KEY_OBSIDIAN_ROOT_DOCUMENT_ID = "obsidian_root_document_id"
    private const val KEY_OBSIDIAN_DISPLAY_NAME = "obsidian_display_name"
    private const val KEY_IMPORT_INBOX_TREE = "import_inbox_tree_uri"
    private const val KEY_AUTO_SYNC = "auto_sync"
    private const val KEY_LAST_SYNC = "last_sync_ms"
    private const val KEY_LAST_SUMMARY = "last_summary"
    private const val KEY_ALLOW_CLOUD_ENRICHMENT = "allow_cloud_enrichment"

    data class ObsidianVaultAccess(
        val permissionTreeUri: Uri,
        val rootDocumentId: String?,
        val displayName: String?,
    )

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun obsidianTree(context: Context): Uri? =
        prefs(context).getString(KEY_OBSIDIAN_TREE, null)?.takeIf { it.isNotBlank() }?.let(Uri::parse)

    fun obsidianRootDocumentId(context: Context): String? =
        prefs(context).getString(KEY_OBSIDIAN_ROOT_DOCUMENT_ID, null)?.takeIf { it.isNotBlank() }

    fun obsidianDisplayName(context: Context): String? =
        prefs(context).getString(KEY_OBSIDIAN_DISPLAY_NAME, null)?.takeIf { it.isNotBlank() }

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
        prefs(context).edit()
            .putString(KEY_OBSIDIAN_TREE, permissionTreeUri?.toString())
            .putString(KEY_OBSIDIAN_ROOT_DOCUMENT_ID, rootDocumentId)
            .putString(KEY_OBSIDIAN_DISPLAY_NAME, displayName)
            .apply()
    }

    fun importInboxTree(context: Context): Uri? =
        prefs(context).getString(KEY_IMPORT_INBOX_TREE, null)?.takeIf { it.isNotBlank() }?.let(Uri::parse)

    fun setImportInboxTree(context: Context, uri: Uri?) {
        prefs(context).edit().putString(KEY_IMPORT_INBOX_TREE, uri?.toString()).apply()
    }

    fun autoSyncEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_AUTO_SYNC, true)

    fun setAutoSyncEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_SYNC, enabled).apply()
    }

    fun allowCloudEnrichment(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ALLOW_CLOUD_ENRICHMENT, false)

    fun setAllowCloudEnrichment(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ALLOW_CLOUD_ENRICHMENT, enabled).apply()
    }

    fun lastSyncMs(context: Context): Long = prefs(context).getLong(KEY_LAST_SYNC, 0L)

    fun lastSummary(context: Context): String = prefs(context).getString(KEY_LAST_SUMMARY, "") ?: ""

    fun recordSync(context: Context, summary: String, nowMs: Long = System.currentTimeMillis()) {
        prefs(context).edit()
            .putLong(KEY_LAST_SYNC, nowMs)
            .putString(KEY_LAST_SUMMARY, summary.take(500))
            .apply()
    }
}
