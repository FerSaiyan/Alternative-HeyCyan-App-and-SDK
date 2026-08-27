package com.fersaiyan.cyanbridge.integrations.knowledge

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.fersaiyan.cyanbridge.ui.appearance.AppearancePreferences
import com.fersaiyan.cyanbridge.ui.appearance.rememberAppearanceSettings
import com.fersaiyan.cyanbridge.ui.notes.NotesListActivity
import com.fersaiyan.cyanbridge.ui.theme.CyanBridgeTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class KnowledgeIntegrationsActivity : AppCompatActivity() {
    private var status by mutableStateOf("Ready")
    private var busy by mutableStateOf(false)
    private var refreshKey by mutableStateOf(0)

    private var newVaultName by mutableStateOf("CyanBridge Vault")
    private var managedNotes by mutableStateOf<List<SafKnowledgeRepository.SafEntry>>(emptyList())
    private var editingNoteUri by mutableStateOf<Uri?>(null)
    private var editingNoteName by mutableStateOf<String?>(null)
    private var noteCreatedAt by mutableStateOf<String?>(null)
    private var noteTitle by mutableStateOf("")
    private var noteTags by mutableStateOf("")
    private var noteBody by mutableStateOf(TextFieldValue(""))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        KnowledgeSyncWorker.schedule(this)
        val appearancePreferences = AppearancePreferences(this)
        setContent {
            val appearance by rememberAppearanceSettings(appearancePreferences)
            CyanBridgeTheme(appearance) {
                KnowledgeIntegrationsScreen()
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun KnowledgeIntegrationsScreen() {
        @Suppress("UNUSED_VARIABLE") val refresh = refreshKey
        val obsidianVault = KnowledgeIntegrationPrefs.obsidianVault(this)
        val obsidianWritable = obsidianVault?.let {
            SafKnowledgeRepository.hasPersistedTreePermission(this, it.permissionTreeUri, writable = true)
        } == true
        val inboxTree = KnowledgeIntegrationPrefs.importInboxTree(this)
        val inboxReadable = inboxTree?.let {
            SafKnowledgeRepository.hasPersistedTreePermission(this, it, writable = false)
        } == true
        val autoSync = KnowledgeIntegrationPrefs.autoSyncEnabled(this)
        val lastSummary = KnowledgeIntegrationPrefs.lastSummary(this)

        LaunchedEffect(refreshKey, obsidianVault?.permissionTreeUri, obsidianVault?.rootDocumentId, obsidianWritable) {
            managedNotes = if (obsidianVault != null && obsidianWritable) {
                withContext(Dispatchers.IO) {
                    SafKnowledgeRepository.listManagedObsidianNotes(
                        context = this@KnowledgeIntegrationsActivity,
                        treeUri = obsidianVault.permissionTreeUri,
                        rootDocumentId = obsidianVault.rootDocumentId,
                    )
                }
            } else {
                emptyList()
            }
        }

        val chatGptImport = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { importFile(it, KnowledgeSource.CHATGPT) }
        }
        val claudeImport = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { importFile(it, KnowledgeSource.CLAUDE) }
        }
        val existingVaultPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            uri?.let(::connectExistingVault)
        }
        val createVaultParentPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            uri?.let(::createVaultUnderParent)
        }
        val inboxPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            uri?.let { selected ->
                if (SafKnowledgeRepository.persistTreePermission(this, selected, writable = false)) {
                    KnowledgeIntegrationPrefs.setImportInboxTree(this, selected)
                    status = "Import folder connected with read-only scoped access."
                    refreshKey++
                    KnowledgeSyncWorker.schedule(this)
                    syncAllNow()
                } else {
                    status = "Could not retain read access to that import folder. Choose it again and grant access."
                }
            }
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Notes & AI imports") },
                    navigationIcon = {
                        TextButton(onClick = { finish() }) { Text("Back") }
                    },
                )
            },
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                PrivacyCard()

                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text("CyanBridge notes", fontWeight = FontWeight.Bold)
                        Text(
                            "Meeting summaries are saved locally as structured Markdown notes.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Button(
                            onClick = {
                                startActivity(Intent(this@KnowledgeIntegrationsActivity, NotesListActivity::class.java))
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Open notes") }
                    }
                }

                ImportCard(
                    title = "ChatGPT",
                    body = "Import an official ChatGPT data export (.zip or conversations.json). CyanBridge reads it locally and never logs in to your ChatGPT account.",
                    button = "Import ChatGPT export",
                    enabled = !busy,
                ) { chatGptImport.launch(arrayOf("application/zip", "application/json", "text/json", "application/octet-stream")) }

                ImportCard(
                    title = "Claude",
                    body = "Import a Claude data export locally. User turns and assistant turns stay distinct so assistant output is never promoted to a confirmed personal fact.",
                    button = "Import Claude export",
                    enabled = !busy,
                ) { claudeImport.launch(arrayOf("application/zip", "application/json", "text/json", "application/octet-stream")) }

                ObsidianVaultCard(
                    vault = obsidianVault,
                    hasWritablePermission = obsidianWritable,
                    onChooseExisting = { existingVaultPicker.launch(obsidianVault?.permissionTreeUri) },
                    onCreateNew = { createVaultParentPicker.launch(null) },
                )

                if (obsidianVault != null && obsidianWritable) {
                    ObsidianNoteEditor(
                        onSave = { saveObsidianNote(obsidianVault) },
                        onNew = ::resetNoteEditor,
                    )
                }

                ConnectionCard(
                    title = "Automatic import inbox",
                    connected = inboxTree != null && inboxReadable,
                    body = if (inboxTree != null && !inboxReadable) {
                        "The stored folder permission is no longer available. Reconnect the folder to resume automatic imports."
                    } else {
                        "Optional bridge folder for .zip, .json, .md, or .txt files. A desktop/browser companion, Syncthing, FolderSync, or another user-controlled tool can drop new snapshots here; CyanBridge periodically re-indexes it without provider credentials."
                    },
                    connectLabel = if (inboxTree == null) "Choose import folder" else "Reconnect import folder",
                    onConnect = { inboxPicker.launch(inboxTree) },
                )

                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Periodic local sync", fontWeight = FontWeight.SemiBold)
                                Text("Every 12 hours when enabled; no provider login is required.", style = MaterialTheme.typography.bodySmall)
                            }
                            Switch(
                                checked = autoSync,
                                onCheckedChange = { enabled ->
                                    KnowledgeIntegrationPrefs.setAutoSyncEnabled(this@KnowledgeIntegrationsActivity, enabled)
                                    KnowledgeSyncWorker.schedule(this@KnowledgeIntegrationsActivity)
                                    refreshKey++
                                },
                            )
                        }
                        Button(
                            onClick = ::syncAllNow,
                            enabled = !busy && ((obsidianVault != null && obsidianWritable) || (inboxTree != null && inboxReadable)),
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(if (busy) "Working…" else "Sync now") }
                    }
                }

                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Status", fontWeight = FontWeight.SemiBold)
                        Text(status)
                        if (lastSummary.isNotBlank() && lastSummary != status) {
                            Text("Last sync: $lastSummary", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                Spacer(Modifier.height(20.dp))
            }
        }
    }

    @Composable
    private fun PrivacyCard() {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Inbound-only private knowledge", fontWeight = FontWeight.Bold)
                Text(
                    "These integrations pull data into CyanBridge; they do not upload CyanBridge chats, memories, imported notes, or vault content to ChatGPT or Claude. Imported knowledge is automatically added to AI prompt context only when the on-device Local Models provider is selected.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }

    @Composable
    private fun ObsidianVaultCard(
        vault: KnowledgeIntegrationPrefs.ObsidianVaultAccess?,
        hasWritablePermission: Boolean,
        onChooseExisting: () -> Unit,
        onCreateNew: () -> Unit,
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Obsidian / Markdown vault", fontWeight = FontWeight.SemiBold)
                    Text(
                        when {
                            vault == null -> "Not connected"
                            hasWritablePermission -> "Read + write"
                            else -> "Permission needed"
                        },
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                if (vault?.displayName != null) {
                    Text("Vault: ${vault.displayName}", style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    if (vault != null && !hasWritablePermission) {
                        "CyanBridge no longer has retained read/write access to this location. Reconnect it before notes can be indexed or edited."
                    } else {
                        "Choose an existing Obsidian vault, or create a normal Markdown vault if you do not have one yet. Android will ask you to grant CyanBridge access to that folder; no broad storage permission is requested."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedButton(
                    onClick = onChooseExisting,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (vault == null) "Connect existing vault" else "Reconnect / change vault") }

                Text("Create a new vault", fontWeight = FontWeight.Medium)
                OutlinedTextField(
                    value = newVaultName,
                    onValueChange = { newVaultName = it },
                    label = { Text("Vault folder name") },
                    supportingText = { Text("You will choose the parent location next.") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Button(
                    onClick = onCreateNew,
                    enabled = !busy && newVaultName.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Create new Markdown vault") }

                Text(
                    "Storage note: files in an Obsidian vault are ordinary plaintext .md files so Obsidian and other Markdown apps can read them. CyanBridge Memory Vault encryption does not encrypt these external source files. Protect them with device/storage encryption or an encrypted sync/storage provider if needed.",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (vault != null) {
                    TextButton(
                        onClick = {
                            KnowledgeIntegrationPrefs.setObsidianVault(
                                this@KnowledgeIntegrationsActivity,
                                permissionTreeUri = null,
                                rootDocumentId = null,
                                displayName = null,
                            )
                            resetNoteEditor()
                            managedNotes = emptyList()
                            status = "Obsidian vault disconnected. Existing Markdown files were not deleted."
                            refreshKey++
                        },
                        enabled = !busy,
                    ) { Text("Disconnect vault") }
                }
            }
        }
    }

    @Composable
    private fun ImportCard(
        title: String,
        body: String,
        button: String,
        enabled: Boolean,
        onClick: () -> Unit,
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(body, style = MaterialTheme.typography.bodyMedium)
                Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text(button) }
            }
        }
    }

    @Composable
    private fun ConnectionCard(
        title: String,
        connected: Boolean,
        body: String,
        connectLabel: String,
        onConnect: () -> Unit,
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(title, fontWeight = FontWeight.SemiBold)
                    Text(if (connected) "Connected" else "Not connected", style = MaterialTheme.typography.labelMedium)
                }
                Text(body, style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(onClick = onConnect, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(connectLabel) }
            }
        }
    }

    @Composable
    private fun ObsidianNoteEditor(
        onSave: () -> Unit,
        onNew: () -> Unit,
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            if (editingNoteUri == null) "New Markdown note" else "Editing Markdown note",
                            fontWeight = FontWeight.SemiBold,
                        )
                        editingNoteName?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                    if (editingNoteUri != null || noteBody.text.isNotBlank() || noteTitle.isNotBlank()) {
                        TextButton(onClick = onNew, enabled = !busy) { Text("New") }
                    }
                }
                Text(
                    "CyanBridge manages notes in the vault's CyanBridge/ folder. Other vault notes remain searchable but are not overwritten by this editor.",
                    style = MaterialTheme.typography.bodySmall,
                )

                if (managedNotes.isNotEmpty()) {
                    Text("Recent CyanBridge notes", fontWeight = FontWeight.Medium)
                    managedNotes.take(8).forEach { entry ->
                        TextButton(
                            onClick = { loadManagedNote(entry) },
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Edit ${entry.name}", modifier = Modifier.fillMaxWidth())
                        }
                    }
                }

                OutlinedTextField(
                    value = noteTitle,
                    onValueChange = { noteTitle = it },
                    label = { Text("Title") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = noteTags,
                    onValueChange = { noteTags = it },
                    label = { Text("Tags") },
                    supportingText = { Text("Comma- or space-separated; # is optional. Saved as Obsidian YAML tags.") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )

                Text("Markdown toolbar", fontWeight = FontWeight.Medium)
                MarkdownToolbar()

                OutlinedTextField(
                    value = noteBody,
                    onValueChange = { noteBody = it },
                    label = { Text("Markdown note") },
                    supportingText = { Text("Supports headings, lists, tasks, links, wiki links, tags, quotes, code, bold and italic Markdown.") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 10,
                    maxLines = 24,
                )
                Button(
                    onClick = onSave,
                    enabled = !busy && noteBody.text.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (editingNoteUri == null) "Create note in Obsidian" else "Update note in Obsidian") }
            }
        }
    }

    @Composable
    private fun MarkdownToolbar() {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ToolButton("H1") { noteBody = MarkdownEditorActions.prefixCurrentLine(noteBody, "# ") }
            ToolButton("• List") { noteBody = MarkdownEditorActions.prefixCurrentLine(noteBody, "- ") }
            ToolButton("☐ Task") { noteBody = MarkdownEditorActions.prefixCurrentLine(noteBody, "- [ ] ") }
            ToolButton("1. List") { noteBody = MarkdownEditorActions.prefixCurrentLine(noteBody, "1. ") }
            ToolButton("Bold") { noteBody = MarkdownEditorActions.wrap(noteBody, "**", "**", "bold") }
            ToolButton("Italic") { noteBody = MarkdownEditorActions.wrap(noteBody, "_", "_", "italic") }
            ToolButton("Code") { noteBody = MarkdownEditorActions.wrap(noteBody, "`", "`", "code") }
            ToolButton("Quote") { noteBody = MarkdownEditorActions.prefixCurrentLine(noteBody, "> ") }
            ToolButton("Link") { noteBody = MarkdownEditorActions.wrap(noteBody, "[", "](https://)", "link text") }
            ToolButton("#tag") { noteBody = MarkdownEditorActions.insert(noteBody, "#tag") }
            ToolButton("[[Wiki]]") { noteBody = MarkdownEditorActions.wrap(noteBody, "[[", "]]", "note") }
        }
    }

    @Composable
    private fun ToolButton(label: String, onClick: () -> Unit) {
        OutlinedButton(onClick = onClick, enabled = !busy) { Text(label) }
    }

    private fun connectExistingVault(selected: Uri) {
        if (!SafKnowledgeRepository.persistTreePermission(this, selected, writable = true)) {
            status = "CyanBridge needs retained read and write access to use an Obsidian vault. Please choose the folder again and allow access."
            return
        }
        KnowledgeIntegrationPrefs.setObsidianVault(
            context = this,
            permissionTreeUri = selected,
            rootDocumentId = null,
            displayName = selected.lastPathSegment?.substringAfterLast(':')?.takeIf { it.isNotBlank() },
        )
        resetNoteEditor()
        status = "Obsidian vault connected with scoped read/write access."
        refreshKey++
        syncAllNow()
    }

    private fun createVaultUnderParent(parent: Uri) {
        if (!SafKnowledgeRepository.persistTreePermission(this, parent, writable = true)) {
            status = "CyanBridge needs read and write access to the selected parent location before it can create a vault."
            return
        }
        lifecycleScope.launch {
            busy = true
            status = "Creating Markdown vault…"
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    SafKnowledgeRepository.createObsidianVault(
                        context = this@KnowledgeIntegrationsActivity,
                        parentTreeUri = parent,
                        requestedName = newVaultName,
                    )
                }
            }
            result.onSuccess { created ->
                KnowledgeIntegrationPrefs.setObsidianVault(
                    context = this@KnowledgeIntegrationsActivity,
                    permissionTreeUri = created.permissionTreeUri,
                    rootDocumentId = created.rootDocumentId,
                    displayName = created.displayName,
                )
                resetNoteEditor()
                status = "Created '${created.displayName}'. You can open this folder as a vault in Obsidian, or use it directly from CyanBridge."
                refreshKey++
                KnowledgeSyncWorker.schedule(this@KnowledgeIntegrationsActivity)
            }.onFailure {
                status = "Could not create vault: ${it.message ?: "unknown error"}"
            }
            busy = false
            if (result.isSuccess) syncAllNow()
        }
    }

    private fun importFile(uri: Uri, source: KnowledgeSource) {
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        lifecycleScope.launch {
            busy = true
            status = "Importing ${source.label}…"
            val result = runCatching {
                KnowledgeImportCoordinator.importSelectedFile(this@KnowledgeIntegrationsActivity, uri, source)
            }
            status = result.fold(
                onSuccess = { items -> items.joinToString(" · ") { it.summary() }.ifBlank { "No conversations found." } },
                onFailure = { "Import failed: ${it.message ?: "unknown error"}" },
            )
            busy = false
            refreshKey++
        }
    }

    private fun syncAllNow() {
        lifecycleScope.launch {
            busy = true
            status = "Syncing local knowledge…"
            val result = runCatching { KnowledgeImportCoordinator.syncAll(this@KnowledgeIntegrationsActivity) }
            status = result.fold(
                onSuccess = { items -> items.joinToString(" · ") { it.summary() }.ifBlank { "Nothing connected yet." } },
                onFailure = { "Sync failed: ${it.message ?: "unknown error"}" },
            )
            busy = false
            refreshKey++
        }
    }

    private fun loadManagedNote(entry: SafKnowledgeRepository.SafEntry) {
        lifecycleScope.launch {
            busy = true
            status = "Opening ${entry.name}…"
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    SafKnowledgeRepository.readManagedObsidianNote(this@KnowledgeIntegrationsActivity, entry)
                }
            }.map { markdown -> ObsidianMarkdownCodec.parse(entry.name, markdown) }
            result.onSuccess { draft ->
                editingNoteUri = entry.uri
                editingNoteName = entry.name
                noteCreatedAt = draft.createdAt
                noteTitle = draft.title
                noteTags = draft.tags
                noteBody = TextFieldValue(draft.body)
                status = "Editing ${entry.name}."
            }.onFailure {
                status = "Could not open note: ${it.message ?: "unknown error"}"
            }
            busy = false
        }
    }

    private fun resetNoteEditor() {
        editingNoteUri = null
        editingNoteName = null
        noteCreatedAt = null
        noteTitle = ""
        noteTags = ""
        noteBody = TextFieldValue("")
    }

    private fun saveObsidianNote(vault: KnowledgeIntegrationPrefs.ObsidianVaultAccess) {
        lifecycleScope.launch {
            busy = true
            status = if (editingNoteUri == null) "Creating note in Obsidian…" else "Updating note in Obsidian…"
            val now = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
            val title = noteTitle.trim().ifBlank { "CyanBridge note ${now.substringBefore(' ')}" }
            val draft = ObsidianManagedDraft(
                title = title,
                tags = noteTags,
                body = noteBody.text,
                createdAt = noteCreatedAt,
            )
            val markdown = ObsidianMarkdownCodec.render(draft, now)
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val saved = SafKnowledgeRepository.saveObsidianNote(
                        context = this@KnowledgeIntegrationsActivity,
                        treeUri = vault.permissionTreeUri,
                        rootDocumentId = vault.rootDocumentId,
                        title = title,
                        markdown = markdown,
                        existingUri = editingNoteUri,
                    )
                    KnowledgeImportCoordinator.syncObsidian(this@KnowledgeIntegrationsActivity)
                    saved
                }
            }
            result.onSuccess { savedUri ->
                editingNoteUri = savedUri
                editingNoteName = if (title.endsWith(".md", true)) title else "$title.md"
                noteCreatedAt = noteCreatedAt ?: now
                noteTitle = title
                noteTags = ObsidianMarkdownCodec.normalizeTags(noteTags).joinToString(", ")
                status = "Saved as plaintext Markdown in the Obsidian vault and re-indexed locally."
                refreshKey++
            }.onFailure {
                status = "Could not save note: ${it.message ?: "unknown error"}"
            }
            busy = false
        }
    }
}
