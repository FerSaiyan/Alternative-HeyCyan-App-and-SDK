package com.fersaiyan.cyanbridge.shared.localmodels

/** Platform-neutral presentation contract for local-model configuration. */
data class LocalModelsConfigureUiState(
    val engineStatus: String = "Runtimes available: llama.cpp + LiteRT",
    val deviceSummary: String = "",
    val selectedModelStatus: String = "Status: not downloaded",
    val emptyStateMessage: String = "",
    val installedModels: List<InstalledModelUiItem> = emptyList(),
    val selectedInstalledModelId: String? = null,
    val catalog: List<LocalModelCatalogUiItem> = emptyList(),
    val catalogExpanded: Boolean = false,
    val remoteServerExpanded: Boolean = false,
    val studioBridgeExpanded: Boolean = false,
    val generationSettingsExpanded: Boolean = false,
    val download: LocalModelDownloadUiState = LocalModelDownloadUiState(),
    val hasUnsavedChanges: Boolean = false,
    val warmupResult: String = "",
    val generation: LocalModelGenerationUiState = LocalModelGenerationUiState(),
    val remoteServer: RemoteInferenceUiState = RemoteInferenceUiState(),
    val studioBridge: StudioBridgeUiState = StudioBridgeUiState(),
)

data class InstalledModelUiItem(
    val id: String,
    val label: String,
)

data class LocalModelCatalogUiItem(
    val id: String,
    val title: String,
    val details: String,
    val status: String,
    val downloadLabel: String,
    val canDownload: Boolean,
)

data class LocalModelDownloadUiState(
    val isInFlight: Boolean = false,
    val message: String = "",
    val progressPercent: Int? = null,
)

data class LocalModelGenerationUiState(
    // Primary performance controls.
    val computeBackendOptions: List<String> = emptyList(),
    val computeBackendIndex: Int = 0,
    val computeBackendNote: String = "",
    val mtpOptions: List<String> = emptyList(),
    val mtpIndex: Int = 0,
    val mtpSupported: Boolean? = null,
    val mtpStatus: String = "",

    // Assistant behavior.
    val systemPrompt: String = "",

    // Advanced controls.
    val runtimeOptions: List<String> = emptyList(),
    val runtimeIndex: Int = 0,
    val runtimeNote: String = "",
    val cpuThreads: String = "",
    val gpuLayers: String = "",
    val gpuLayersEnabled: Boolean = false,
    val temperature: String = "",
    val topP: String = "",
    val topK: String = "",
    val maxTokens: String = "",
    val repetitionPenalty: String = "",
    val contextSize: String = "",
    val seed: String = "",
    val templateOptions: List<String> = emptyList(),
    val templateIndex: Int = 0,
    val experimentalStructuredJson: Boolean = false,
    val huggingFaceToken: String = "",
)

data class RemoteInferenceUiState(
    val enabled: Boolean = false,
    val baseUrl: String = "",
    val modelName: String = "",
    val apiKey: String = "",
    val status: String = "",
)

data class StudioBridgeUiState(
    val enabled: Boolean = false,
    val apiKey: String = "",
    val status: String = "",
)

enum class LocalModelsSection {
    CATALOG,
    REMOTE_SERVER,
    STUDIO_BRIDGE,
    GENERATION_SETTINGS,
}

enum class LocalModelTextField {
    CPU_THREADS,
    GPU_LAYERS,
    TEMPERATURE,
    TOP_P,
    TOP_K,
    MAX_TOKENS,
    REPETITION_PENALTY,
    CONTEXT_SIZE,
    SEED,
    SYSTEM_PROMPT,
    HUGGING_FACE_TOKEN,
    REMOTE_BASE_URL,
    REMOTE_MODEL_NAME,
    REMOTE_API_KEY,
    STUDIO_BRIDGE_API_KEY,
}

enum class LocalModelOptionField {
    RUNTIME,
    COMPUTE_BACKEND,
    MTP_MODE,
    TEMPLATE,
}

enum class LocalModelToggleField {
    EXPERIMENTAL_STRUCTURED_JSON,
    REMOTE_SERVER_ENABLED,
    STUDIO_BRIDGE_ENABLED,
}

sealed interface LocalModelsAction {
    data object Back : LocalModelsAction
    data object DiscardChangesAndBack : LocalModelsAction
    data object Refresh : LocalModelsAction
    data object ImportModel : LocalModelsAction
    data class SelectInstalledModel(val id: String) : LocalModelsAction
    data object ShowSelectedModelInfo : LocalModelsAction
    data object UnloadSelectedModel : LocalModelsAction
    data object RemoveSelectedModel : LocalModelsAction
    data class DownloadCatalogModel(val id: String) : LocalModelsAction
    data class ShowCatalogModelInfo(val id: String) : LocalModelsAction
    data object CancelDownload : LocalModelsAction
    data object RunWarmup : LocalModelsAction
    data object SaveGenerationSettings : LocalModelsAction
    data class ToggleSection(val section: LocalModelsSection) : LocalModelsAction
    data class UpdateText(val field: LocalModelTextField, val value: String) : LocalModelsAction
    data class SelectOption(val field: LocalModelOptionField, val index: Int) : LocalModelsAction
    data class SetToggle(val field: LocalModelToggleField, val enabled: Boolean) : LocalModelsAction
    data object TestRemoteServer : LocalModelsAction
    data object SaveRemoteServer : LocalModelsAction
    data object ShowStudioBridgeApiKeyHelp : LocalModelsAction
    data object SaveStudioBridge : LocalModelsAction
}
