package com.fersaiyan.cyanbridge.integrations.knowledge

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.fersaiyan.cyanbridge.ui.appearance.AppearancePreferences
import com.fersaiyan.cyanbridge.ui.appearance.rememberAppearanceSettings
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
    private var noteTitle by mutableStateOf("")
    private var noteBody by mutableStateOf("")
    private var refreshKey by mutableStateOf(0)

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
        // refreshKey makes persisted URI/status reads update after picker callbacks.
        @Suppress("UNUSED_VARIABLE") val refresh = refreshKey
        val obsidianTree = KnowledgeIntegrationPrefs.obsidianTree(this)
        val inboxTree = KnowledgeIntegrationPrefs.importInboxTree(this)
        val autoSync = KnowledgeIntegrationPrefs.autoSyncEnabled(this)
        val lastSummary = KnowledgeIntegrationPrefs.lastSummary(this)

        val chatGptImport = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { importFile(it, KnowledgeSource.CHATGPT) }
        }
        val claudeImport = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { importFile(it, KnowledgeSource.CLAUDE) }
        }
        val obsidianPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            uri?.let { selected ->
                SafKnowledgeRepository.persistTreePermission(this, selected, writable = true)
                KnowledgeIntegrationPrefs.setObsidianTree(this, selected)
                refreshKey++
                syncAllNow()
            }
        }
        val inboxPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            uri?.let { selected ->
                SafKnowledgeRepository.persistTreePermission(this, selected, writable = false)
                KnowledgeIntegrationPrefs.setImportInboxTree(this, selected)
                refreshKey++
                KnowledgeSyncWorker.schedule(this)
                syncAllNow()
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

                ConnectionCard(
                    title = "Obsidian vault",
                    connected = obsidianTree != null,
                    body = if (obsidianTree == null) {
                        "Grant CyanBridge access to one vault folder. Markdown notes are indexed for local RAG, and CyanBridge can write notes back into a CyanBridge/ folder inside the vault."
                    } else {
                        "Vault connected. Changes can be re-indexed periodically without broad storage permission."
                    },
                    connectLabel = if (obsidianTree == null) "Choose vault" else "Change vault",
                    onConnect = { obsidianPicker.launch(obsidianTree) },
                )

                if (obsidianTree != null) {
                    ObsidianNoteEditor(onSave = { saveObsidianNote(obsidianTree) })
                }

                ConnectionCard(
                    title = "Automatic import inbox",
                    connected = inboxTree != null,
                    body = "Optional bridge folder for .zip, .json, .md, or .txt files. A desktop/browser companion, Syncthing, FolderSync, or another user-controlled tool can drop new snapshots here; CyanBridge periodically re-indexes the folder without provider credentials.",
                    connectLabel = if (inboxTree == null) "Choose import folder" else "Change import folder",
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
                                Text("Every 12 hours when enabled; no network is required.", style = MaterialTheme.typography.bodySmall)
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
                            enabled = !busy && (obsidianTree != null || inboxTree != null),
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
                    "These integrations pull data into CyanBridge; they do not upload CyanBridge chats, notes, memories, or vault content to ChatGPT or Claude. Imported knowledge is automatically added to AI prompt context only when the on-device Local Models provider is selected.",
                    style = MaterialTheme.typography.bodyMedium,
                )
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
    private fun ObsidianNoteEditor(onSave: () -> Unit) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("New Obsidian note", fontWeight = FontWeight.SemiBold)
                Text("Write or paste a transcription/idea here. It is saved as Markdown and becomes available to local RAG after sync.")
                OutlinedTextField(
                    value = noteTitle,
                    onValueChange = { noteTitle = it },
                    label = { Text("Title") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = noteBody,
                    onValueChange = { noteBody = it },
                    label = { Text("Markdown note") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 6,
                    maxLines = 16,
                )
                Button(
                    onClick = onSave,
                    enabled = !busy && noteBody.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Save to Obsidian") }
            }
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

    private fun saveObsidianNote(treeUri: Uri) {
        lifecycleScope.launch {
            busy = true
            status = "Saving note to Obsidian…"
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val date = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
                    val title = noteTitle.trim().ifBlank { "CyanBridge note ${date.substringBefore(' ')}" }
                    val markdown = buildString {
                        appendLine("---")
                        appendLine("source: cyanbridge")
                        appendLine("created: \"$date\"")
                        appendLine("---")
                        appendLine()
                        appendLine("# $title")
                        appendLine()
                        appendLine(noteBody.trim())
                    }
                    SafKnowledgeRepository.saveObsidianNote(this@KnowledgeIntegrationsActivity, treeUri, title, markdown)
                    KnowledgeImportCoordinator.syncObsidian(this@KnowledgeIntegrationsActivity)
                }
            }
            status = result.fold(
                onSuccess = { "Saved to Obsidian and re-indexed locally." },
                onFailure = { "Could not save note: ${it.message ?: "unknown error"}" },
            )
            if (result.isSuccess) {
                noteTitle = ""
                noteBody = ""
            }
            busy = false
            refreshKey++
        }
    }
}
