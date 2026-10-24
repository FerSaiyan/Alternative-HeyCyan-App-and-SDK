package com.fersaiyan.cyanbridge.shared.ui.knowledge

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Obsidian vault connection and sync settings; the host opens the folder pickers. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KnowledgeIntegrationsScreen(
    viewModel: KnowledgeIntegrationsViewModel,
    onChooseExisting: () -> Unit,
    onCreateNew: () -> Unit,
    onBack: () -> Unit,
) {
    val busy = viewModel.busy
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Obsidian sync") },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("Back") }
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
            ObsidianVaultCard(viewModel, onChooseExisting, onCreateNew)

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
                        Switch(checked = viewModel.autoSync, onCheckedChange = viewModel::updateAutoSync)
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
                        Switch(checked = viewModel.cloudEnrichment, onCheckedChange = viewModel::updateCloudEnrichment)
                    }
                    Button(
                        onClick = viewModel::syncNow,
                        enabled = !busy && viewModel.vault?.writable == true,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (busy) "Working…" else "Sync now") }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Status", fontWeight = FontWeight.SemiBold)
                    Text(viewModel.status)
                    val lastSummary = viewModel.lastSummary
                    if (lastSummary.isNotBlank() && lastSummary != viewModel.status) {
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
    viewModel: KnowledgeIntegrationsViewModel,
    onChooseExisting: () -> Unit,
    onCreateNew: () -> Unit,
) {
    val vault = viewModel.vault
    val busy = viewModel.busy
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Obsidian / Markdown vault", fontWeight = FontWeight.SemiBold)
                Text(
                    when {
                        vault == null -> "Not connected"
                        vault.writable -> "Read + write"
                        else -> "Permission needed"
                    },
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            if (vault?.displayName != null) {
                Text("Vault: ${vault.displayName}", style = MaterialTheme.typography.bodySmall)
            }
            Text(
                if (vault != null && !vault.writable) {
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
                value = viewModel.newVaultName,
                onValueChange = { viewModel.newVaultName = it },
                label = { Text("Vault folder name") },
                supportingText = { Text("You will choose the parent location next.") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Button(
                onClick = onCreateNew,
                enabled = !busy && viewModel.newVaultName.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Create new Markdown vault") }

            Text(
                "Storage note: files in an Obsidian vault are ordinary plaintext .md files so Obsidian and other Markdown apps can read them. CyanBridge Memory Vault encryption does not encrypt these external source files. Protect them with device/storage encryption or an encrypted sync/storage provider if needed.",
                style = MaterialTheme.typography.bodySmall,
            )
            if (vault != null) {
                TextButton(onClick = viewModel::disconnect, enabled = !busy) { Text("Disconnect vault") }
            }
        }
    }
}
