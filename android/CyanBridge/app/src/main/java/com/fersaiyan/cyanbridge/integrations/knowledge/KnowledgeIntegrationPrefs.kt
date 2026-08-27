package com.fersaiyan.cyanbridge.integrations.knowledge

import android.content.Context
import android.net.Uri

/** Preference contract shared with the Obsidian integration branch. */
object KnowledgeIntegrationPrefs {
    private const val PREFS = "knowledge_integrations"
    private const val KEY_OBSIDIAN_TREE = "obsidian_tree_uri"
    private const val KEY_OBSIDIAN_ROOT_DOCUMENT_ID = "obsidian_root_document_id"
    private const val KEY_OBSIDIAN_DISPLAY_NAME = "obsidian_display_name"

    data class ObsidianVaultAccess(
        val permissionTreeUri: Uri,
        val rootDocumentId: String?,
        val displayName: String?,
    )

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun obsidianVault(context: Context): ObsidianVaultAccess? {
        val tree = prefs(context).getString(KEY_OBSIDIAN_TREE, null)
            ?.takeIf { it.isNotBlank() }
            ?.let(Uri::parse)
            ?: return null
        return ObsidianVaultAccess(
            permissionTreeUri = tree,
            rootDocumentId = prefs(context).getString(KEY_OBSIDIAN_ROOT_DOCUMENT_ID, null)
                ?.takeIf { it.isNotBlank() },
            displayName = prefs(context).getString(KEY_OBSIDIAN_DISPLAY_NAME, null)
                ?.takeIf { it.isNotBlank() },
        )
    }

    fun setObsidianTree(context: Context, uri: Uri?, displayName: String? = null) {
        prefs(context).edit()
            .putString(KEY_OBSIDIAN_TREE, uri?.toString())
            .putString(KEY_OBSIDIAN_ROOT_DOCUMENT_ID, null)
            .putString(KEY_OBSIDIAN_DISPLAY_NAME, displayName)
            .apply()
    }
}
