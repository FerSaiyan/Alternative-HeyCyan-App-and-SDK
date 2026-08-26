package com.fersaiyan.cyanbridge.integrations.knowledge

import android.content.Context
import android.net.Uri

object KnowledgeIntegrationPrefs {
    private const val PREFS = "knowledge_integrations"
    private const val KEY_OBSIDIAN_TREE = "obsidian_tree_uri"
    private const val KEY_IMPORT_INBOX_TREE = "import_inbox_tree_uri"
    private const val KEY_AUTO_SYNC = "auto_sync"
    private const val KEY_LAST_SYNC = "last_sync_ms"
    private const val KEY_LAST_SUMMARY = "last_summary"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun obsidianTree(context: Context): Uri? =
        prefs(context).getString(KEY_OBSIDIAN_TREE, null)?.takeIf { it.isNotBlank() }?.let(Uri::parse)

    fun setObsidianTree(context: Context, uri: Uri?) {
        prefs(context).edit().putString(KEY_OBSIDIAN_TREE, uri?.toString()).apply()
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

    fun lastSyncMs(context: Context): Long = prefs(context).getLong(KEY_LAST_SYNC, 0L)

    fun lastSummary(context: Context): String = prefs(context).getString(KEY_LAST_SUMMARY, "") ?: ""

    fun recordSync(context: Context, summary: String, nowMs: Long = System.currentTimeMillis()) {
        prefs(context).edit()
            .putLong(KEY_LAST_SYNC, nowMs)
            .putString(KEY_LAST_SUMMARY, summary.take(500))
            .apply()
    }
}
