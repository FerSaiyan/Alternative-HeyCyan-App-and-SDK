package com.fersaiyan.cyanbridge.shared.ui.localmodels

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.fersaiyan.cyanbridge.shared.localmodels.LocalModelDownloadUiState
import com.fersaiyan.cyanbridge.shared.localmodels.LocalModelOptionField
import com.fersaiyan.cyanbridge.shared.localmodels.LocalModelTextField
import com.fersaiyan.cyanbridge.shared.localmodels.LocalModelToggleField
import com.fersaiyan.cyanbridge.shared.localmodels.LocalModelsAction
import com.fersaiyan.cyanbridge.shared.localmodels.LocalModelsConfigureUiState
import com.fersaiyan.cyanbridge.shared.localmodels.LocalModelsSection

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalModelsConfigureScreen(
    state: LocalModelsConfigureUiState,
    onAction: (LocalModelsAction) -> Unit,
) {
    var showUnsavedChangesDialog by rememberSaveable { mutableStateOf(false) }
    val requestBack = {
        if (state.hasUnsavedChanges) showUnsavedChangesDialog = true
        else onAction(LocalModelsAction.Back)
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text("Local models") },
                navigationIcon = {
                    IconButton(onClick = requestBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        bottomBar = {
            if (state.download.isInFlight || state.download.message.isNotBlank()) {
                Surface(tonalElevation = 6.dp, shadowElevation = 8.dp, modifier = Modifier.fillMaxWidth()) {
                    Box(Modifier.fillMaxWidth().padding(16.dp)) {
                        DownloadProgressCard(state.download, onAction)
                    }
                }
            }
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
                .testTag("local_models_configure"),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                ScreenCard("Current model") {
                    Text(state.engineStatus, style = MaterialTheme.typography.bodyMedium)
                    if (state.deviceSummary.isNotBlank()) SupportingText(state.deviceSummary)
                    SupportingText(state.selectedModelStatus)
                    if (state.installedModels.isNotEmpty()) {
                        ChoiceField(
                            label = "Selected model",
                            value = state.installedModels.firstOrNull { it.id == state.selectedInstalledModelId }?.label.orEmpty(),
                            options = state.installedModels.map { it.label },
                            onSelected = { index ->
                                state.installedModels.getOrNull(index)?.let {
                                    onAction(LocalModelsAction.SelectInstalledModel(it.id))
                                }
                            },
                        )
                    } else {
                        Text(
                            state.emptyStateMessage.ifBlank { "No local model installed." },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    ActionRow(
                        primaryLabel = "Import model",
                        onPrimary = { onAction(LocalModelsAction.ImportModel) },
                        secondaryLabel = "Refresh",
                        onSecondary = { onAction(LocalModelsAction.Refresh) },
                    )
                    if (state.selectedInstalledModelId != null) {
                        ActionRow(
                            primaryLabel = "Model info",
                            onPrimary = { onAction(LocalModelsAction.ShowSelectedModelInfo) },
                            secondaryLabel = "Unload",
                            onSecondary = { onAction(LocalModelsAction.UnloadSelectedModel) },
                        )
                    }
                }
            }

            item {
                ScreenCard("Performance") {
                    val generation = state.generation
                    ChoiceField(
                        label = "Compute backend",
                        value = generation.computeBackendOptions.getOrNull(generation.computeBackendIndex).orEmpty(),
                        options = generation.computeBackendOptions,
                        onSelected = {
                            onAction(LocalModelsAction.SelectOption(LocalModelOptionField.COMPUTE_BACKEND, it))
                        },
                    )
                    SupportingText(generation.computeBackendNote)
                    ChoiceField(
                        label = "MTP acceleration",
                        value = generation.mtpOptions.getOrNull(generation.mtpIndex).orEmpty(),
                        options = generation.mtpOptions,
                        enabled = state.selectedInstalledModelId != null,
                        onSelected = {
                            onAction(LocalModelsAction.SelectOption(LocalModelOptionField.MTP_MODE, it))
                        },
                    )
                    if (generation.mtpStatus.isNotBlank()) SupportingText(generation.mtpStatus)
                    FilledTonalButton(
                        onClick = { onAction(LocalModelsAction.RunWarmup) },
                        enabled = state.selectedInstalledModelId != null,
                        modifier = Modifier.fillMaxWidth().testTag("test_model_now"),
                    ) { Text("Test model now") }
                    if (state.warmupResult.isNotBlank()) SupportingText(state.warmupResult)
                }
            }

            item {
                ScreenCard("Assistant behavior") {
                    Text(
                        "This prompt is sent to the selected local model. Keep the short-first instruction for faster spoken responses, or customize it for your use case.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    ModelTextField(
                        label = "System prompt",
                        value = state.generation.systemPrompt,
                        minLines = 4,
                        onValueChange = {
                            onAction(LocalModelsAction.UpdateText(LocalModelTextField.SYSTEM_PROMPT, it))
                        },
                    )
                    FilledTonalButton(
                        onClick = { onAction(LocalModelsAction.SaveGenerationSettings) },
                        enabled = state.selectedInstalledModelId != null,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Save local model settings") }
                }
            }

            item {
                ExpandableCard(
                    title = "Curated models",
                    expanded = state.catalogExpanded,
                    onToggle = { onAction(LocalModelsAction.ToggleSection(LocalModelsSection.CATALOG)) },
                ) {
                    Text(
                        "Download a tested starter package or import your own GGUF / LiteRT-LM model.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    state.catalog.forEachIndexed { index, model ->
                        if (index > 0) HorizontalDivider()
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(model.title, style = MaterialTheme.typography.titleSmall)
                            SupportingText(model.details)
                            SupportingText(model.status)
                            ActionRow(
                                primaryLabel = model.downloadLabel,
                                onPrimary = { onAction(LocalModelsAction.DownloadCatalogModel(model.id)) },
                                secondaryLabel = "Info",
                                onSecondary = { onAction(LocalModelsAction.ShowCatalogModelInfo(model.id)) },
                                enabled = model.canDownload,
                                secondaryEnabled = true,
                            )
                        }
                    }
                    ModelTextField(
                        label = "Hugging Face token (only for gated downloads)",
                        value = state.generation.huggingFaceToken,
                        password = true,
                        onValueChange = {
                            onAction(LocalModelsAction.UpdateText(LocalModelTextField.HUGGING_FACE_TOKEN, it))
                        },
                    )
                }
            }

            item {
                ExpandableCard(
                    title = "Remote model server",
                    subtitle = "Run a model on another computer or device",
                    expanded = state.remoteServerExpanded,
                    onToggle = { onAction(LocalModelsAction.ToggleSection(LocalModelsSection.REMOTE_SERVER)) },
                ) {
                    val remote = state.remoteServer
                    ToggleRow(
                        label = "Use remote OpenAI-compatible server",
                        checked = remote.enabled,
                        onCheckedChange = {
                            onAction(LocalModelsAction.SetToggle(LocalModelToggleField.REMOTE_SERVER_ENABLED, it))
                        },
                    )
                    ModelTextField(
                        label = "Base URL",
                        value = remote.baseUrl,
                        onValueChange = {
                            onAction(LocalModelsAction.UpdateText(LocalModelTextField.REMOTE_BASE_URL, it))
                        },
                    )
                    ModelTextField(
                        label = "Model name",
                        value = remote.modelName,
                        onValueChange = {
                            onAction(LocalModelsAction.UpdateText(LocalModelTextField.REMOTE_MODEL_NAME, it))
                        },
                    )
                    ModelTextField(
                        label = "API key (optional)",
                        value = remote.apiKey,
                        password = true,
                        onValueChange = {
                            onAction(LocalModelsAction.UpdateText(LocalModelTextField.REMOTE_API_KEY, it))
                        },
                    )
                    ActionRow(
                        primaryLabel = "Test connection",
                        onPrimary = { onAction(LocalModelsAction.TestRemoteServer) },
                        secondaryLabel = "Save",
                        onSecondary = { onAction(LocalModelsAction.SaveRemoteServer) },
                    )
                    if (remote.status.isNotBlank()) SupportingText(remote.status)
                }
            }

            item {
                ExpandableCard(
                    title = "CyanBridge Model Studio",
                    subtitle = "Connect to a model running on your other device",
                    expanded = state.studioBridgeExpanded,
                    onToggle = { onAction(LocalModelsAction.ToggleSection(LocalModelsSection.STUDIO_BRIDGE)) },
                ) {
                    val studio = state.studioBridge
                    ToggleRow(
                        label = "Enable Studio Bridge",
                        checked = studio.enabled,
                        onCheckedChange = {
                            onAction(LocalModelsAction.SetToggle(LocalModelToggleField.STUDIO_BRIDGE_ENABLED, it))
                        },
                    )
                    ModelTextField(
                        label = "Studio API key",
                        value = studio.apiKey,
                        password = true,
                        onValueChange = {
                            onAction(LocalModelsAction.UpdateText(LocalModelTextField.STUDIO_BRIDGE_API_KEY, it))
                        },
                    )
                    ActionRow(
                        primaryLabel = "Save & connect",
                        onPrimary = { onAction(LocalModelsAction.SaveStudioBridge) },
                        secondaryLabel = "API key help",
                        onSecondary = { onAction(LocalModelsAction.ShowStudioBridgeApiKeyHelp) },
                    )
                    if (studio.status.isNotBlank()) SupportingText(studio.status)
                }
            }

            item {
                ExpandableCard(
                    title = "Advanced options",
                    subtitle = "Runtime, context and sampling controls",
                    expanded = state.generationSettingsExpanded,
                    onToggle = { onAction(LocalModelsAction.ToggleSection(LocalModelsSection.GENERATION_SETTINGS)) },
                ) {
                    val generation = state.generation
                    ChoiceField(
                        label = "Runtime",
                        value = generation.runtimeOptions.getOrNull(generation.runtimeIndex).orEmpty(),
                        options = generation.runtimeOptions,
                        onSelected = {
                            onAction(LocalModelsAction.SelectOption(LocalModelOptionField.RUNTIME, it))
                        },
                    )
                    SupportingText(generation.runtimeNote)
                    ModelTextField(
                        label = "CPU threads",
                        value = generation.cpuThreads,
                        onValueChange = {
                            onAction(LocalModelsAction.UpdateText(LocalModelTextField.CPU_THREADS, it))
                        },
                    )
                    ModelTextField(
                        label = "GPU layers (-1 = auto)",
                        value = generation.gpuLayers,
                        enabled = generation.gpuLayersEnabled,
                        onValueChange = {
                            onAction(LocalModelsAction.UpdateText(LocalModelTextField.GPU_LAYERS, it))
                        },
                    )
                    ModelTextField(label = "Temperature", value = generation.temperature, onValueChange = {
                        onAction(LocalModelsAction.UpdateText(LocalModelTextField.TEMPERATURE, it))
                    })
                    ModelTextField(label = "Top P", value = generation.topP, onValueChange = {
                        onAction(LocalModelsAction.UpdateText(LocalModelTextField.TOP_P, it))
                    })
                    ModelTextField(label = "Top K", value = generation.topK, onValueChange = {
                        onAction(LocalModelsAction.UpdateText(LocalModelTextField.TOP_K, it))
                    })
                    ModelTextField(label = "Max output tokens", value = generation.maxTokens, onValueChange = {
                        onAction(LocalModelsAction.UpdateText(LocalModelTextField.MAX_TOKENS, it))
                    })
                    ModelTextField(label = "Repetition penalty", value = generation.repetitionPenalty, onValueChange = {
                        onAction(LocalModelsAction.UpdateText(LocalModelTextField.REPETITION_PENALTY, it))
                    })
                    ModelTextField(label = "Context size", value = generation.contextSize, onValueChange = {
                        onAction(LocalModelsAction.UpdateText(LocalModelTextField.CONTEXT_SIZE, it))
                    })
                    ModelTextField(label = "Seed (-1 = random)", value = generation.seed, onValueChange = {
                        onAction(LocalModelsAction.UpdateText(LocalModelTextField.SEED, it))
                    })
                    ChoiceField(
                        label = "Prompt template",
                        value = generation.templateOptions.getOrNull(generation.templateIndex).orEmpty(),
                        options = generation.templateOptions,
                        onSelected = {
                            onAction(LocalModelsAction.SelectOption(LocalModelOptionField.TEMPLATE, it))
                        },
                    )
                    ToggleRow(
                        label = "Experimental structured JSON",
                        checked = generation.experimentalStructuredJson,
                        onCheckedChange = {
                            onAction(LocalModelsAction.SetToggle(LocalModelToggleField.EXPERIMENTAL_STRUCTURED_JSON, it))
                        },
                    )
                    OutlinedButton(
                        onClick = { onAction(LocalModelsAction.RemoveSelectedModel) },
                        enabled = state.selectedInstalledModelId != null,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Remove selected model") }
                }
            }
            item { Spacer(Modifier.height(8.dp)) }
        }
    }

    if (showUnsavedChangesDialog) {
        AlertDialog(
            onDismissRequest = { showUnsavedChangesDialog = false },
            title = { Text("Unsaved changes") },
            text = { Text("Leave without saving your local-model changes?") },
            dismissButton = {
                TextButton(onClick = { showUnsavedChangesDialog = false }) { Text("Keep editing") }
            },
            confirmButton = {
                TextButton(onClick = { onAction(LocalModelsAction.DiscardChangesAndBack) }) { Text("Discard") }
            },
        )
    }
}

@Composable
private fun ScreenCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun ExpandableCard(
    title: String,
    subtitle: String? = null,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    subtitle?.let { SupportingText(it) }
                }
                Icon(
                    if (expanded) Icons.Outlined.ExpandMore else Icons.Outlined.ChevronRight,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                )
            }
            if (expanded) {
                HorizontalDivider()
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    content = content,
                )
            }
        }
    }
}

@Composable
private fun ChoiceField(
    label: String,
    value: String,
    options: List<String>,
    enabled: Boolean = true,
    onSelected: (Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Surface(
            modifier = Modifier.fillMaxWidth().clickable(enabled = enabled) { expanded = !expanded },
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(value.ifBlank { "Select" }, modifier = Modifier.weight(1f))
                Icon(Icons.Outlined.ExpandMore, contentDescription = null)
            }
        }
        if (expanded && enabled) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column {
                    options.forEachIndexed { index, option ->
                        Text(
                            option,
                            modifier = Modifier.fillMaxWidth().clickable {
                                expanded = false
                                onSelected(index)
                            }.padding(14.dp),
                        )
                        if (index < options.lastIndex) HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelTextField(
    label: String,
    value: String,
    enabled: Boolean = true,
    password: Boolean = false,
    minLines: Int = 1,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        enabled = enabled,
        minLines = minLines,
        visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun ActionRow(
    primaryLabel: String,
    onPrimary: () -> Unit,
    secondaryLabel: String,
    onSecondary: () -> Unit,
    enabled: Boolean = true,
    secondaryEnabled: Boolean = enabled,
) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilledTonalButton(onClick = onPrimary, enabled = enabled, modifier = Modifier.weight(1f)) { Text(primaryLabel) }
        OutlinedButton(onClick = onSecondary, enabled = secondaryEnabled, modifier = Modifier.weight(1f)) { Text(secondaryLabel) }
    }
}

@Composable
private fun SupportingText(text: String) {
    if (text.isBlank()) return
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun DownloadProgressCard(state: LocalModelDownloadUiState, onAction: (LocalModelsAction) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        Text(state.message.ifBlank { "Model download" }, style = MaterialTheme.typography.bodyMedium)
        if (state.isInFlight) {
            val progress = state.progressPercent
            if (progress == null) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            else LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
            TextButton(onClick = { onAction(LocalModelsAction.CancelDownload) }) { Text("Cancel download") }
        }
    }
}
