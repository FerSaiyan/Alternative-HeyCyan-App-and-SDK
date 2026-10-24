package com.fersaiyan.cyanbridge.shared.ui.knowledge

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fersaiyan.cyanbridge.shared.platform.platformCurrentTimeMillis
import com.fersaiyan.cyanbridge.shared.prefs.MemoryPrefs.Knowledge
import kotlinx.coroutines.launch

data class ObsidianVaultUi(val treeUri: String, val displayName: String?, val writable: Boolean)

/** Folder access and indexing for an Obsidian / Markdown vault (Android: the Storage Access Framework). */
interface KnowledgeVaultBackend {
    fun hasWritableAccess(treeUri: String): Boolean

    /** Keeps read/write access to [treeUri]; false when it was not granted. */
    fun persistAccess(treeUri: String): Boolean
    fun displayNameOf(treeUri: String): String?

    /** Creates a vault folder named [name] under [parentTreeUri] and connects it; returns its display name. */
    suspend fun createVault(parentTreeUri: String, name: String): String

    /** Indexes the connected vault; null when none is connected. */
    suspend fun sync(): String?
    fun scheduleSync()
}

class KnowledgeIntegrationsViewModel(private val backend: KnowledgeVaultBackend) : ViewModel() {
    var status by mutableStateOf("Ready")
        private set
    var busy by mutableStateOf(false)
        private set
    var newVaultName by mutableStateOf("CyanBridge Vault")
    var vault by mutableStateOf<ObsidianVaultUi?>(null)
        private set
    var autoSync by mutableStateOf(false)
        private set
    var cloudEnrichment by mutableStateOf(false)
        private set
    var lastSummary by mutableStateOf("")
        private set

    init {
        backend.scheduleSync()
        reload()
    }

    private fun reload() {
        vault = Knowledge.obsidianTreeUri.get()?.takeIf { it.isNotBlank() }?.let { tree ->
            ObsidianVaultUi(tree, Knowledge.obsidianDisplayName.get()?.takeIf { it.isNotBlank() }, backend.hasWritableAccess(tree))
        }
        autoSync = Knowledge.autoSync.get()
        cloudEnrichment = Knowledge.allowCloudEnrichment.get()
        lastSummary = Knowledge.lastSummary.get()
    }

    fun updateAutoSync(enabled: Boolean) {
        Knowledge.autoSync.set(enabled)
        backend.scheduleSync()
        reload()
    }

    fun updateCloudEnrichment(enabled: Boolean) {
        Knowledge.allowCloudEnrichment.set(enabled)
        reload()
    }

    fun disconnect() {
        setVault(null, null, null)
        status = "Obsidian vault disconnected. Existing Markdown files were not deleted."
        reload()
    }

    fun connectExisting(treeUri: String) {
        if (!backend.persistAccess(treeUri)) {
            status = "CyanBridge needs retained read and write access to use an Obsidian vault. Please choose the folder again and allow access."
            return
        }
        setVault(treeUri, null, backend.displayNameOf(treeUri))
        status = "Obsidian vault connected with scoped read/write access."
        reload()
        syncNow()
    }

    fun createUnder(parentTreeUri: String) {
        if (!backend.persistAccess(parentTreeUri)) {
            status = "CyanBridge needs read and write access to the selected parent location before it can create a vault."
            return
        }
        viewModelScope.launch {
            busy = true
            status = "Creating Markdown vault…"
            val result = runCatching { backend.createVault(parentTreeUri, newVaultName) }
            result.onSuccess { name ->
                status = "Created '$name'. You can open this folder as a vault in Obsidian, or use it directly from CyanBridge."
                reload()
                backend.scheduleSync()
            }.onFailure {
                status = "Could not create vault: ${it.message ?: "unknown error"}"
            }
            busy = false
            if (result.isSuccess) syncNow()
        }
    }

    fun syncNow() {
        viewModelScope.launch {
            busy = true
            status = "Syncing Obsidian vault…"
            status = runCatching { backend.sync() }.fold(
                onSuccess = { it ?: "No Obsidian vault connected." },
                onFailure = { "Sync failed: ${it.message ?: "unknown error"}" },
            )
            Knowledge.lastSyncMs.set(platformCurrentTimeMillis())
            Knowledge.lastSummary.set(status.take(500))
            busy = false
            reload()
        }
    }

    private fun setVault(treeUri: String?, rootDocumentId: String?, displayName: String?) {
        Knowledge.obsidianTreeUri.set(treeUri)
        Knowledge.obsidianRootDocumentId.set(rootDocumentId)
        Knowledge.obsidianDisplayName.set(displayName)
    }
}
