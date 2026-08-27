package com.fersaiyan.cyanbridge.integrations.knowledge

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fersaiyan.cyanbridge.ui.appearance.AppearancePreferences
import com.fersaiyan.cyanbridge.ui.appearance.rememberAppearanceSettings
import com.fersaiyan.cyanbridge.ui.notes.NotesListActivity
import com.fersaiyan.cyanbridge.ui.theme.CyanBridgeTheme

/** Chats entry point for Room notes and scoped Obsidian vault access. */
class KnowledgeIntegrationsActivity : AppCompatActivity() {
    private var refreshKey by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val appearancePreferences = AppearancePreferences(this)
        setContent {
            val appearance by rememberAppearanceSettings(appearancePreferences)
            CyanBridgeTheme(appearance) { KnowledgeIntegrationsScreen() }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun KnowledgeIntegrationsScreen() {
        @Suppress("UNUSED_VARIABLE") val refresh = refreshKey
        val vault = KnowledgeIntegrationPrefs.obsidianVault(this)
        val writable = vault?.let {
            SafKnowledgeRepository.hasPersistedTreePermission(this, it.permissionTreeUri, writable = true)
        } == true
        val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null && SafKnowledgeRepository.persistTreePermission(this, uri)) {
                KnowledgeIntegrationPrefs.setObsidianTree(this, uri, uri.lastPathSegment)
                refreshKey++
            }
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Notes & Obsidian") },
                    navigationIcon = { TextButton(onClick = ::finish) { Text("Back") } },
                )
            },
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
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

                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text("Obsidian vault", fontWeight = FontWeight.Bold)
                            Text(if (writable) "Connected" else "Not connected")
                        }
                        Text(
                            "Grant read and write access to one vault folder. Meeting notes are mirrored as plain Markdown in its CyanBridge/ folder. No broad storage permission is used.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (vault?.displayName != null) {
                            Text("Location: ${vault.displayName}", style = MaterialTheme.typography.bodySmall)
                        }
                        Button(
                            onClick = { picker.launch(vault?.permissionTreeUri) },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(if (vault == null) "Connect vault" else "Reconnect vault") }
                        if (vault != null) {
                            OutlinedButton(
                                onClick = {
                                    KnowledgeIntegrationPrefs.setObsidianTree(this@KnowledgeIntegrationsActivity, null)
                                    refreshKey++
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("Disconnect vault") }
                        }
                    }
                }
            }
        }
    }
}
