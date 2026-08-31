package com.fersaiyan.cyanbridge.integrations.knowledge

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

class KnowledgeIntegrationsActivity : AppCompatActivity() {
    private var status by mutableStateOf("Ready")
    private var busy by mutableStateOf(false)
    private var refreshKey by mutableStateOf(0)

    private var newVaultName by mutableStateOf("CyanBridge Vault")

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
        val autoSync = KnowledgeIntegrationPrefs.autoSyncEnabled(this)
        val cloudEnrichment = KnowledgeIntegrationPrefs.allowCloudEnrichment(this)
        val lastSummary = KnowledgeIntegrationPrefs.lastSummary(this)

        val existingVaultPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            uri?.let(::connectExistingVault)
        }
        val createVaultParentPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            uri?.let(::createVaultUnderParent)
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Obsidian sync") },
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
                ObsidianVaultCard(
                    vault = obsidianVault,
                    hasWritablePermission = obsidianWritable,
                    onChooseExisting = { existingVaultPicker.launch(obsidianVault?.permissionTreeUri) },
                    onCreateNew = { createVaultParentPicker.launch(null) },
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
                                Text("Periodic Obsidian sync", fontWeight = FontWeight.SemiBold)
                                Text("Refresh the connected vault every 12 hours.", style = MaterialTheme.typography.bodySmall)
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
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Allow cloud note enrichment", fontWeight = FontWeight.SemiBold)
                                Text(
                                    "When the selected provider is Pro, note text may be sent to the relay overnight to generate tags. Off keeps unattended enrichment local.",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            Switch(
                                checked = cloudEnrichment,
                                onCheckedChange = { enabled ->
                                    KnowledgeIntegrationPrefs.setAllowCloudEnrichment(
                                        this@KnowledgeIntegrationsActivity,
                                        enabled,
                                    )
                                    refreshKey++
                                },
                            )
                        }
                        Button(
                            onClick = ::syncObsidianNow,
                            enabled = !busy && obsidianVault != null && obsidianWritable,
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
                            status = "Obsidian vault disconnected. Existing Markdown files were not deleted."
                            refreshKey++
                        },
                        enabled = !busy,
                    ) { Text("Disconnect vault") }
                }
            }
        }
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
        status = "Obsidian vault connected with scoped read/write access."
        refreshKey++
        syncObsidianNow()
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
                status = "Created '${created.displayName}'. You can open this folder as a vault in Obsidian, or use it directly from CyanBridge."
                refreshKey++
                KnowledgeSyncWorker.schedule(this@KnowledgeIntegrationsActivity)
            }.onFailure {
                status = "Could not create vault: ${it.message ?: "unknown error"}"
            }
            busy = false
            if (result.isSuccess) syncObsidianNow()
        }
    }

    private fun syncObsidianNow() {
        lifecycleScope.launch {
            busy = true
            status = "Syncing Obsidian vault…"
            val result = runCatching { KnowledgeImportCoordinator.syncObsidian(this@KnowledgeIntegrationsActivity) }
            status = result.fold(
                onSuccess = { item -> item?.summary() ?: "No Obsidian vault connected." },
                onFailure = { "Sync failed: ${it.message ?: "unknown error"}" },
            )
            KnowledgeIntegrationPrefs.recordSync(this@KnowledgeIntegrationsActivity, status)
            busy = false
            refreshKey++
        }
    }

}
