package com.fersaiyan.cyanbridge.shared.localmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fersaiyan.cyanbridge.shared.viewmodel.CloseScreen
import com.fersaiyan.cyanbridge.shared.viewmodel.DialogButton
import com.fersaiyan.cyanbridge.shared.viewmodel.ScreenDialog
import com.fersaiyan.cyanbridge.shared.viewmodel.ScreenEffect
import com.fersaiyan.cyanbridge.shared.viewmodel.ScreenEffects
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The top of the local models screen: runtimes, device and the installed models. */
data class LocalModelsOverview(
    val engineStatus: String,
    val deviceSummary: String,
    val selectedModelStatus: String,
    val emptyStateMessage: String,
    val installedModels: List<InstalledModelUiItem>,
    val selectedModelId: String?,
    val selectedModelName: String?,
)

/** Why a change was refused; [long] messages stay on screen longer. */
data class Refusal(val message: String, val long: Boolean = false)

/** Whether a catalog model may be downloaded now, and what to tell the user first. */
sealed interface DownloadCheck {
    data class Blocked(val refusal: Refusal) : DownloadCheck
    data class Allowed(val displayName: String, val token: String?, val warnings: List<String>) : DownloadCheck
}

data class ModelInfo(val title: String, val message: String, val sourceUrl: String? = null)

/**
 * Storage, device checks, runtimes and settings behind the local models screen. The generation
 * draft keeps option fields as indices into the lists [describeGeneration] fills in.
 */
interface LocalModelsPlatform {
    /** Drops entries whose files vanished, then rereads the installed models and the device. */
    fun reload()
    fun overview(): LocalModelsOverview
    fun catalog(huggingFaceToken: String): List<LocalModelCatalogUiItem>
    fun selectModel(id: String)
    fun warmupResult(): String
    fun isSectionExpanded(section: LocalModelsSection): Boolean
    fun setSectionExpanded(section: LocalModelsSection, expanded: Boolean)

    /** The selected model's saved settings (defaults without a model), as an editable draft. */
    fun loadGeneration(): LocalModelGenerationUiState

    /** Fills in option lists, notes and MTP support for [draft]. */
    fun describeGeneration(draft: LocalModelGenerationUiState): LocalModelGenerationUiState

    /** Saves the download token, then [draft] for the selected model; false when there is none. */
    fun saveGeneration(draft: LocalModelGenerationUiState): Boolean
    fun loadRemote(): RemoteInferenceUiState
    fun loadStudio(): StudioBridgeUiState

    /** Saves the remote server settings, or returns why they were refused. */
    fun saveRemote(draft: RemoteInferenceUiState): Refusal?

    /** The remote server's health status. */
    suspend fun checkRemoteHealth(): String

    /** What blocks turning the Studio bridge on with [apiKey], if anything. */
    fun studioBlocker(apiKey: String): Refusal?
    fun studioPermissionsGranted(): Boolean

    /** Saves the Studio bridge choice and starts or stops it; true when an enabled bridge started. */
    fun applyStudio(enabled: Boolean, apiKey: String): Boolean

    fun downloadInProgress(): LocalModelDownloadUiState?

    /** Null for an unknown model. */
    fun checkDownload(catalogId: String, huggingFaceToken: String): DownloadCheck?
    fun startDownload(catalogId: String, token: String?)
    fun cancelDownload()

    /** Copies the picked file into model storage; returns the result message or throws. Blocks. */
    fun importModel(source: Any): String
    suspend fun unloadModel()
    fun removeModel(id: String)
    fun selectedModelInfo(runtimeIndex: Int): ModelInfo?
    fun catalogInfo(catalogId: String): ModelInfo?
}

/** Opens the platform's file picker for a model file. */
data object PickModelFile : ScreenEffect

/** Opens the performance test for [modelId]. */
data class OpenModelTest(val modelId: String) : ScreenEffect
data class OpenModelSource(val url: String) : ScreenEffect

/** Asks for the microphone and speech permissions the Studio bridge needs, then retries the save. */
data object RequestStudioPermissions : ScreenEffect

/** The settings were saved; the opener may refresh. */
data object ReportSettingsSaved : ScreenEffect

/** Local models: installed and catalog models, downloads, generation settings and remote servers. */
class LocalModelsConfigureViewModel(
    private val platform: LocalModelsPlatform,
    private val io: CoroutineDispatcher,
) : ViewModel() {
    private val _state = MutableStateFlow(LocalModelsConfigureUiState())
    val state: StateFlow<LocalModelsConfigureUiState> = _state.asStateFlow()
    private val effects = ScreenEffects()
    val effectFlow get() = effects.flow

    private var generation = LocalModelGenerationUiState()
    private var remote = RemoteInferenceUiState()
    private var studio = StudioBridgeUiState()
    private var download = LocalModelDownloadUiState()
    private var hasUnsavedChanges = false
    private var started = false

    fun start() {
        if (started) return
        started = true
        refreshAll(loadDrafts = true)
    }

    /** Back on screen (for instance after the performance test): refresh unless edits are pending. */
    fun onResume() = refreshAll(loadDrafts = !hasUnsavedChanges)

    fun handle(action: LocalModelsAction) {
        when (action) {
            LocalModelsAction.Back -> requestClose()
            LocalModelsAction.DiscardChangesAndBack -> effects.send(CloseScreen)
            LocalModelsAction.Refresh -> refreshAll(loadDrafts = !hasUnsavedChanges)
            LocalModelsAction.ImportModel -> effects.send(PickModelFile)
            is LocalModelsAction.SelectInstalledModel -> {
                platform.selectModel(action.id)
                generation = platform.loadGeneration()
                hasUnsavedChanges = false
                publish()
            }
            LocalModelsAction.ShowSelectedModelInfo -> platform.selectedModelInfo(generation.runtimeIndex)?.let { info ->
                effects.send(ScreenDialog(info.title, info.message, positive = DialogButton("Close")))
            }
            LocalModelsAction.UnloadSelectedModel -> viewModelScope.launch {
                platform.unloadModel()
                effects.toast("Local model unloaded")
                publish()
            }
            LocalModelsAction.RemoveSelectedModel -> confirmRemoveSelectedModel()
            is LocalModelsAction.DownloadCatalogModel -> requestDownload(action.id)
            is LocalModelsAction.ShowCatalogModelInfo -> platform.catalogInfo(action.id)?.let { info ->
                effects.send(
                    ScreenDialog(
                        title = info.title,
                        message = info.message,
                        negative = DialogButton("Close"),
                        positive = DialogButton("Open source") { info.sourceUrl?.let { effects.send(OpenModelSource(it)) } },
                    ),
                )
            }
            LocalModelsAction.CancelDownload -> {
                platform.cancelDownload()
                download = LocalModelDownloadUiState(message = "Download cancelled")
                publish()
            }
            LocalModelsAction.RunWarmup -> launchModelTest()
            LocalModelsAction.SaveGenerationSettings -> saveGenerationSettings(showToast = true)
            is LocalModelsAction.ToggleSection -> {
                platform.setSectionExpanded(action.section, !platform.isSectionExpanded(action.section))
                publish()
            }
            is LocalModelsAction.UpdateText, is LocalModelsAction.SelectOption, is LocalModelsAction.SetToggle ->
                LocalModelDrafts(generation, remote, studio).edit(action)?.let { drafts ->
                    generation = drafts.generation
                    remote = drafts.remote
                    studio = drafts.studio
                    hasUnsavedChanges = true
                    publish()
                }
            LocalModelsAction.TestRemoteServer -> testRemoteServer()
            LocalModelsAction.SaveRemoteServer -> saveRemoteServer(showToast = true)
            LocalModelsAction.ShowStudioBridgeApiKeyHelp -> effects.send(
                ScreenDialog(
                    title = "CyanBridge Model Studio API key",
                    message = "Open CyanBridge Model Studio on your other device, go to Settings → API Keys, " +
                        "create a key, and paste it here. The Remote model server URL/model above are used by Studio Bridge.",
                    positive = DialogButton("Got it"),
                ),
            )
            LocalModelsAction.SaveStudioBridge -> saveStudioBridge()
        }
    }

    /** The picked file from [PickModelFile]. */
    fun importModel(source: Any) {
        viewModelScope.launch {
            download = LocalModelDownloadUiState(isInFlight = true, message = "Importing model…")
            publish()
            withContext(io) { runCatching { platform.importModel(source) } }.fold(
                onSuccess = {
                    download = LocalModelDownloadUiState(message = it)
                    refreshAll(loadDrafts = true)
                },
                onFailure = {
                    download = LocalModelDownloadUiState(message = "Import failed: ${it.message}")
                    publish()
                },
            )
        }
    }

    fun onDownloadProgress(state: LocalModelDownloadUiState) {
        download = state
        publish()
    }

    fun onDownloadFinished(success: Boolean, error: String?) {
        download = LocalModelDownloadUiState(message = if (success) "Download complete" else "Download failed: ${error ?: "unknown error"}")
        if (success) refreshAll(loadDrafts = true) else publish()
    }

    private fun refreshAll(loadDrafts: Boolean) {
        platform.reload()
        if (loadDrafts) {
            generation = platform.loadGeneration()
            remote = platform.loadRemote()
            studio = platform.loadStudio()
            hasUnsavedChanges = false
        }
        val inProgress = platform.downloadInProgress()
        if (inProgress != null) {
            download = inProgress
        } else if (download.isInFlight) {
            download = LocalModelDownloadUiState()
        }
        publish()
    }

    private fun publish() {
        val overview = platform.overview()
        _state.value = LocalModelsConfigureUiState(
            engineStatus = overview.engineStatus,
            deviceSummary = overview.deviceSummary,
            selectedModelStatus = overview.selectedModelStatus,
            emptyStateMessage = overview.emptyStateMessage,
            installedModels = overview.installedModels,
            selectedInstalledModelId = overview.selectedModelId,
            catalog = platform.catalog(generation.huggingFaceToken),
            catalogExpanded = platform.isSectionExpanded(LocalModelsSection.CATALOG),
            remoteServerExpanded = platform.isSectionExpanded(LocalModelsSection.REMOTE_SERVER),
            studioBridgeExpanded = platform.isSectionExpanded(LocalModelsSection.STUDIO_BRIDGE),
            generationSettingsExpanded = platform.isSectionExpanded(LocalModelsSection.GENERATION_SETTINGS),
            download = download,
            hasUnsavedChanges = hasUnsavedChanges,
            warmupResult = platform.warmupResult(),
            generation = platform.describeGeneration(generation),
            remoteServer = remote,
            studioBridge = studio,
        )
    }

    private fun saveGenerationSettings(showToast: Boolean) {
        val saved = platform.saveGeneration(generation)
        if (!saved) {
            if (showToast) effects.toast("Saved download token. Install a model to save model settings.")
            hasUnsavedChanges = false
            publish()
            return
        }
        generation = platform.loadGeneration()
        hasUnsavedChanges = false
        effects.send(ReportSettingsSaved)
        if (showToast) effects.toast("Local model settings saved")
        publish()
    }

    private fun launchModelTest() {
        val modelId = platform.overview().selectedModelId ?: run {
            effects.toast("Install or select a model first")
            return
        }
        saveGenerationSettings(showToast = false)
        effects.send(OpenModelTest(modelId))
    }

    private fun requestDownload(catalogId: String) {
        when (val check = platform.checkDownload(catalogId, generation.huggingFaceToken) ?: return) {
            is DownloadCheck.Blocked -> effects.toast(check.refusal.message, check.refusal.long)
            is DownloadCheck.Allowed -> {
                val start = {
                    download = LocalModelDownloadUiState(isInFlight = true, message = "Starting ${check.displayName}…")
                    publish()
                    platform.startDownload(catalogId, check.token)
                }
                if (check.warnings.isEmpty()) {
                    start()
                } else {
                    effects.send(
                        ScreenDialog(
                            title = "Device warning",
                            message = check.warnings.joinToString("\n"),
                            negative = DialogButton("Cancel"),
                            positive = DialogButton("Continue") { start() },
                        ),
                    )
                }
            }
        }
    }

    private fun confirmRemoveSelectedModel() {
        val overview = platform.overview()
        val id = overview.selectedModelId ?: return
        effects.send(
            ScreenDialog(
                title = "Remove model?",
                message = "Delete ${overview.selectedModelName} from local storage?",
                negative = DialogButton("Cancel"),
                positive = DialogButton("Remove") {
                    viewModelScope.launch { runCatching { platform.unloadModel() } }
                    platform.removeModel(id)
                    refreshAll(loadDrafts = true)
                },
            ),
        )
    }

    private fun saveRemoteServer(showToast: Boolean): Boolean {
        platform.saveRemote(remote)?.let { refusal ->
            effects.toast(refusal.message, refusal.long)
            return false
        }
        val url = remote.baseUrl.trim()
        val model = remote.modelName.trim()
        remote = remote.copy(status = if (remote.enabled) "Active: $model @ $url" else "Saved (disabled)")
        studio = studio.copy(apiKey = remote.apiKey.trim())
        hasUnsavedChanges = false
        if (showToast) effects.toast("Remote server settings saved")
        publish()
        return true
    }

    private fun testRemoteServer() {
        if (!saveRemoteServer(showToast = false)) return
        remote = remote.copy(status = "Testing connection…")
        publish()
        viewModelScope.launch {
            val result = withContext(io) { runCatching { platform.checkRemoteHealth() } }
            remote = remote.copy(
                status = result.fold(onSuccess = { "Connection: $it" }, onFailure = { "Connection failed: ${it.message}" }),
            )
            publish()
        }
    }

    /** Also the retry after [RequestStudioPermissions]. */
    fun saveStudioBridge() {
        val enabled = studio.enabled
        val key = studio.apiKey.trim()
        if (enabled) {
            platform.studioBlocker(key)?.let { refusal ->
                effects.toast(refusal.message, refusal.long)
                return
            }
            if (!platform.studioPermissionsGranted()) {
                effects.send(RequestStudioPermissions)
                return
            }
            remote = remote.copy(apiKey = key)
        }
        val started = platform.applyStudio(enabled, key)
        studio = when {
            !enabled -> studio.copy(status = "")
            started -> studio.copy(status = "Bridge connecting…")
            else -> studio.copy(status = "Bridge could not start. Check server/model settings.")
        }
        hasUnsavedChanges = false
        publish()
    }

    private fun requestClose() {
        if (!hasUnsavedChanges) {
            effects.send(CloseScreen)
            return
        }
        effects.send(
            ScreenDialog(
                title = "Unsaved changes",
                message = "Leave without saving your Local Models changes?",
                negative = DialogButton("Keep editing"),
                positive = DialogButton("Discard") { effects.send(CloseScreen) },
            ),
        )
    }
}

/** The editable parts of the local models screen: generation settings, remote server and Studio bridge. */
data class LocalModelDrafts(
    val generation: LocalModelGenerationUiState,
    val remote: RemoteInferenceUiState,
    val studio: StudioBridgeUiState,
) {
    /** The drafts after a text, option or toggle edit; null for other actions. */
    fun edit(action: LocalModelsAction): LocalModelDrafts? = when (action) {
        is LocalModelsAction.UpdateText -> withText(action.field, action.value)
        is LocalModelsAction.SelectOption -> copy(
            generation = when (action.field) {
                LocalModelOptionField.RUNTIME -> generation.copy(runtimeIndex = action.index)
                LocalModelOptionField.COMPUTE_BACKEND -> generation.copy(computeBackendIndex = action.index)
                LocalModelOptionField.MTP_MODE -> generation.copy(mtpIndex = action.index)
                LocalModelOptionField.TEMPLATE -> generation.copy(templateIndex = action.index)
            },
        )
        is LocalModelsAction.SetToggle -> when (action.field) {
            LocalModelToggleField.EXPERIMENTAL_STRUCTURED_JSON -> copy(generation = generation.copy(experimentalStructuredJson = action.enabled))
            LocalModelToggleField.REMOTE_SERVER_ENABLED -> copy(remote = remote.copy(enabled = action.enabled))
            LocalModelToggleField.STUDIO_BRIDGE_ENABLED -> copy(studio = studio.copy(enabled = action.enabled))
        }
        else -> null
    }

    private fun withText(field: LocalModelTextField, value: String): LocalModelDrafts = when (field) {
        LocalModelTextField.CPU_THREADS -> copy(generation = generation.copy(cpuThreads = value))
        LocalModelTextField.GPU_LAYERS -> copy(generation = generation.copy(gpuLayers = value))
        LocalModelTextField.TEMPERATURE -> copy(generation = generation.copy(temperature = value))
        LocalModelTextField.TOP_P -> copy(generation = generation.copy(topP = value))
        LocalModelTextField.TOP_K -> copy(generation = generation.copy(topK = value))
        LocalModelTextField.MAX_TOKENS -> copy(generation = generation.copy(maxTokens = value))
        LocalModelTextField.REPETITION_PENALTY -> copy(generation = generation.copy(repetitionPenalty = value))
        LocalModelTextField.CONTEXT_SIZE -> copy(generation = generation.copy(contextSize = value))
        LocalModelTextField.SEED -> copy(generation = generation.copy(seed = value))
        LocalModelTextField.SYSTEM_PROMPT -> copy(generation = generation.copy(systemPrompt = value))
        LocalModelTextField.HUGGING_FACE_TOKEN -> copy(generation = generation.copy(huggingFaceToken = value))
        LocalModelTextField.REMOTE_BASE_URL -> copy(remote = remote.copy(baseUrl = value))
        LocalModelTextField.REMOTE_MODEL_NAME -> copy(remote = remote.copy(modelName = value))
        LocalModelTextField.REMOTE_API_KEY -> copy(remote = remote.copy(apiKey = value))
        LocalModelTextField.STUDIO_BRIDGE_API_KEY -> copy(studio = studio.copy(apiKey = value))
    }
}

/** Applies a draft edit to the screen state and marks it unsaved; null for other actions. */
fun LocalModelsConfigureUiState.withDraftEdit(action: LocalModelsAction): LocalModelsConfigureUiState? =
    LocalModelDrafts(generation, remoteServer, studioBridge).edit(action)?.let {
        copy(generation = it.generation, remoteServer = it.remote, studioBridge = it.studio, hasUnsavedChanges = true)
    }
