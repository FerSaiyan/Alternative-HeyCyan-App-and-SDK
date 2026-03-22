package com.fersaiyan.cyanbridge.ui.localmodels

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fersaiyan.cyanbridge.ui.theme.CardBackground
import com.fersaiyan.cyanbridge.ui.theme.CyanAccent
import com.fersaiyan.cyanbridge.ui.theme.TextSecondary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalModelsScreen(viewModel: LocalModelsViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsState()

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importModel(uri)
    }

    var showRemoveConfirm by remember { mutableStateOf(false) }
    var showModelInfo by remember { mutableStateOf(false) }
    var showCatalogInfo by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(Unit) { viewModel.refreshAll() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Local Models") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
                actions = {
                    IconButton(onClick = { viewModel.refreshAll() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                    }
                },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        if (showRemoveConfirm) {
            AlertDialog(
                onDismissRequest = { showRemoveConfirm = false },
                title = { Text("Remove model?") },
                text = { Text("Delete from local storage?") },
                confirmButton = {
                    TextButton(onClick = {
                        viewModel.removeModel()
                        showRemoveConfirm = false
                    }) { Text("Remove") }
                },
                dismissButton = {
                    TextButton(onClick = { showRemoveConfirm = false }) { Text("Cancel") }
                },
            )
        }

        if (showModelInfo) {
            val model = state.installedModels.firstOrNull { it.id == state.selectedModelId }
            AlertDialog(
                onDismissRequest = { showModelInfo = false },
                title = { Text(model?.displayName ?: "Model") },
                text = {
                    if (model != null) {
                        val exists = java.io.File(model.absolutePath).exists()
                        Text(
                            "Quantization: ${model.quantization ?: "unknown"}\n" +
                                "Size: ${LocalModelsViewModel.humanSize(model.sizeBytes)}\n" +
                                "Template: ${model.promptTemplateId ?: "auto"}\n" +
                                "Status: ${if (exists) "ready" else "failed (missing file)"}\n" +
                                "SHA-256: ${model.sha256 ?: "n/a"}",
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showModelInfo = false }) { Text("Close") }
                },
            )
        }

        if (showCatalogInfo != null) {
            val entry = state.catalogEntries.getOrNull(showCatalogInfo!!)?.entry
            if (entry != null) {
                AlertDialog(
                    onDismissRequest = { showCatalogInfo = null },
                    title = { Text(entry.displayName) },
                    text = {
                        Text(
                            "Family: ${entry.family}\n" +
                                "Quantization: ${entry.quantization}\n" +
                                "Size: ${LocalModelsViewModel.humanSize(entry.sizeBytes)}\n" +
                                "Template: ${entry.promptTemplateId}\n" +
                                "RAM tier: ${entry.minRamGb} GB+\n" +
                                "Storage tier: ${entry.minStorageGb} GB+\n" +
                                "License: ${entry.licenseTermsNote}",
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = { showCatalogInfo = null }) { Text("Close") }
                    },
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            DeviceInfoCard(state)

            InstalledModelsSection(
                state = state,
                viewModel = viewModel,
                onImport = { importLauncher.launch(arrayOf("application/octet-stream", "*/*")) },
                onShowInfo = { showModelInfo = true },
                onRemove = { showRemoveConfirm = true },
            )

            CatalogSection(
                state = state,
                viewModel = viewModel,
                onShowInfo = { showCatalogInfo = it },
            )

            GenerationSettingsSection(
                state = state,
                viewModel = viewModel,
            )

            HfTokenSection(state, viewModel)

            WarmupSection(state, viewModel)

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                Button(
                    onClick = { viewModel.saveSettings() },
                    colors = ButtonDefaults.buttonColors(containerColor = CyanAccent),
                ) {
                    Icon(Icons.Filled.Check, contentDescription = null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Save Settings")
                }
            }

            if (state.downloadProgress.isNotBlank()) {
                Text(
                    text = state.downloadProgress,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            if (state.isDownloading) {
                LinearProgressIndicator(
                    progress = { state.downloadPercent / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    color = CyanAccent,
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = { viewModel.cancelDownload() }) {
                        Text("Cancel", color = MaterialTheme.colorScheme.error)
                    }
                }
            }

            Spacer(modifier = Modifier.height(80.dp))
        }
    }
}

@Composable
private fun DeviceInfoCard(state: LocalModelsUiState) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        colors = CardDefaults.cardColors(containerColor = CardBackground),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = state.engineStatus,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = state.deviceSummary,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InstalledModelsSection(
    state: LocalModelsUiState,
    viewModel: LocalModelsViewModel,
    onImport: () -> Unit,
    onShowInfo: () -> Unit,
    onRemove: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = CardBackground),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Installed Models",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )

            if (state.installedModels.isEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "No models installed",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { viewModel.downloadStarter() },
                        colors = ButtonDefaults.buttonColors(containerColor = CyanAccent),
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Download Starter")
                    }
                    OutlinedButton(onClick = onImport) {
                        Text("Import GGUF")
                    }
                }
            } else {
                Spacer(modifier = Modifier.height(8.dp))
                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = !expanded },
                ) {
                    OutlinedTextField(
                        value = state.installedModels.firstOrNull { it.id == state.selectedModelId }
                            ?.let { "${it.displayName} (${LocalModelsViewModel.humanSize(it.sizeBytes)})" }
                            ?: "Select model",
                        onValueChange = {},
                        readOnly = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = CyanAccent,
                            cursorColor = CyanAccent,
                        ),
                    )
                    ExposedDropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false },
                    ) {
                        state.installedModels.forEach { model ->
                            DropdownMenuItem(
                                text = { Text("${model.displayName} (${LocalModelsViewModel.humanSize(model.sizeBytes)})") },
                                onClick = {
                                    viewModel.selectModel(model.id)
                                    expanded = false
                                },
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = state.selectedModelStatus,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                )

                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onShowInfo) {
                        Icon(Icons.Filled.List, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Info")
                    }
                    OutlinedButton(onClick = { viewModel.unloadModel() }) {
                        Text("Unload")
                    }
                    OutlinedButton(
                        onClick = onRemove,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) {
                        Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Remove")
                    }
                }
                OutlinedButton(onClick = onImport) {
                    Text("Import GGUF")
                }
            }
        }
    }
}

@Composable
private fun CatalogSection(
    state: LocalModelsUiState,
    viewModel: LocalModelsViewModel,
    onShowInfo: (Int) -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = CardBackground),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { viewModel.toggleCatalogExpanded() },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Star, contentDescription = null, tint = CyanAccent, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Curated Catalog",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = if (state.catalogExpanded) Icons.Filled.ArrowDropDown else Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    tint = TextSecondary,
                    modifier = Modifier.size(20.dp),
                )
            }

            AnimatedVisibility(visible = state.catalogExpanded) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 12.dp)) {
                    state.catalogEntries.forEachIndexed { index, catalogUi ->
                        CatalogEntryCard(
                            catalogUi = catalogUi,
                            isDownloading = state.isDownloading,
                            onDownload = { viewModel.requestDownload(catalogUi.entry) },
                            onInfo = { onShowInfo(index) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CatalogEntryCard(
    catalogUi: CatalogUiEntry,
    isDownloading: Boolean,
    onDownload: () -> Unit,
    onInfo: () -> Unit,
) {
    val entry = catalogUi.entry
    Column {
        Text(
            text = entry.displayName,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = "${entry.quantization} · ${LocalModelsViewModel.humanSize(entry.sizeBytes)} · tags: ${entry.tags.joinToString(", ")}",
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
        )
        Text(
            text = catalogUi.statusText,
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val btnText = when {
                entry.gatedDownload -> "Gated"
                !catalogUi.canDownload -> if (catalogUi.statusText.contains("ready")) "Installed" else "Manual Import"
                else -> "Download"
            }
            Button(
                onClick = onDownload,
                enabled = catalogUi.canDownload && !isDownloading,
                colors = ButtonDefaults.buttonColors(containerColor = CyanAccent),
            ) {
                Text(btnText)
            }
            OutlinedButton(onClick = onInfo) {
                Text("Info")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GenerationSettingsSection(
    state: LocalModelsUiState,
    viewModel: LocalModelsViewModel,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = CardBackground),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { viewModel.toggleSettingsExpanded() },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Settings, contentDescription = null, tint = CyanAccent, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Generation Settings",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = if (state.settingsExpanded) Icons.Filled.ArrowDropDown else Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    tint = TextSecondary,
                    modifier = Modifier.size(20.dp),
                )
            }

            AnimatedVisibility(visible = state.settingsExpanded) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 12.dp),
                ) {
                    DropdownSelector(
                        label = "Profile",
                        options = viewModel.profiles,
                        selectedIndex = state.profileIndex,
                        onSelected = { viewModel.onProfileSelected(it) },
                    )

                    SettingsField("Temperature", state.temperature, KeyboardType.Decimal) { viewModel.updateField("temperature", it) }
                    SettingsField("Top P", state.topP, KeyboardType.Decimal) { viewModel.updateField("topP", it) }
                    SettingsField("Top K", state.topK, KeyboardType.Number) { viewModel.updateField("topK", it) }
                    SettingsField("Max Tokens", state.maxTokens, KeyboardType.Number) { viewModel.updateField("maxTokens", it) }
                    SettingsField("Repetition Penalty", state.repetitionPenalty, KeyboardType.Decimal) { viewModel.updateField("repetitionPenalty", it) }
                    SettingsField("Context Size", state.contextSize, KeyboardType.Number) { viewModel.updateField("contextSize", it) }
                    SettingsField("Seed", state.seed, KeyboardType.Number) { viewModel.updateField("seed", it) }

                    DropdownSelector(
                        label = "Compute Backend",
                        options = viewModel.backends,
                        selectedIndex = state.computeBackendIndex,
                        onSelected = { viewModel.onComputeBackendSelected(it) },
                    )

                    if (state.computeBackendIndex == 1) {
                        Text(
                            text = "GPU is experimental. Use -1 for auto layer offload. If GPU init fails, the app retries lower layer counts and then falls back to CPU.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                        )
                    } else {
                        Text(
                            text = "CPU mode is the most compatible option. Increase CPU threads for speed if your device remains responsive.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                        )
                    }

                    SettingsField("CPU Threads", state.cpuThreads, KeyboardType.Number) { viewModel.updateField("cpuThreads", it) }
                    SettingsField("GPU Layers", state.gpuLayers, KeyboardType.Number) { viewModel.updateField("gpuLayers", it) }

                    OutlinedTextField(
                        value = state.systemPrompt,
                        onValueChange = { viewModel.updateField("systemPrompt", it) },
                        label = { Text("System Prompt") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = CyanAccent, cursorColor = CyanAccent),
                    )

                    DropdownSelector(
                        label = "Template Override",
                        options = viewModel.templates,
                        selectedIndex = state.templateOverrideIndex,
                        onSelected = { viewModel.onTemplateSelected(it) },
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Experimental JSON",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Switch(
                            checked = state.experimentalJson,
                            onCheckedChange = { viewModel.toggleExperimentalJson() },
                            colors = SwitchDefaults.colors(checkedThumbColor = CyanAccent, checkedTrackColor = CyanAccent.copy(alpha = 0.3f)),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsField(label: String, value: String, keyboardType: KeyboardType, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = CyanAccent, cursorColor = CyanAccent),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DropdownSelector(
    label: String,
    options: List<String>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val display = options.getOrElse(selectedIndex) { options.firstOrNull().orEmpty() }

    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
        OutlinedTextField(
            value = display,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = CyanAccent, cursorColor = CyanAccent),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEachIndexed { index, option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        onSelected(index)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun HfTokenSection(state: LocalModelsUiState, viewModel: LocalModelsViewModel) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = CardBackground),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Hugging Face Token",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = state.hfToken,
                onValueChange = { viewModel.updateField("hfToken", it) },
                label = { Text("HF Token") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = CyanAccent, cursorColor = CyanAccent),
            )
        }
    }
}

@Composable
private fun WarmupSection(state: LocalModelsUiState, viewModel: LocalModelsViewModel) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = CardBackground),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Warm-up Probe",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Button(
                    onClick = { viewModel.runWarmup() },
                    enabled = state.selectedModelId != null,
                    colors = ButtonDefaults.buttonColors(containerColor = CyanAccent),
                ) {
                    Text("Run")
                }
            }
            if (state.warmupResult.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = state.warmupResult,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                )
            }
        }
    }
}
