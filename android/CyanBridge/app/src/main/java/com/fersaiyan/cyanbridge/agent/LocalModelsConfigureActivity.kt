package com.fersaiyan.cyanbridge.agent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.fersaiyan.cyanbridge.localmodels.catalog.LocalModelCatalogEntry
import com.fersaiyan.cyanbridge.localmodels.catalog.LocalModelCatalogRepository
import com.fersaiyan.cyanbridge.localmodels.device.DeviceCapabilityService
import com.fersaiyan.cyanbridge.localmodels.device.DeviceSnapshot
import com.fersaiyan.cyanbridge.localmodels.download.ModelDownloadForegroundService
import com.fersaiyan.cyanbridge.localmodels.engine.LiteRtLocalInferenceEngine
import com.fersaiyan.cyanbridge.localmodels.remote.RemoteOpenAiClient
import com.fersaiyan.cyanbridge.localmodels.remote.RemoteOpenAiPrefs
import com.fersaiyan.cyanbridge.localmodels.session.LocalChatSessionManager
import com.fersaiyan.cyanbridge.localmodels.settings.LocalComputeBackend
import com.fersaiyan.cyanbridge.localmodels.settings.LocalGenerationSettings
import com.fersaiyan.cyanbridge.localmodels.settings.LocalModelRuntime
import com.fersaiyan.cyanbridge.localmodels.settings.LocalModelSettingsRepository
import com.fersaiyan.cyanbridge.localmodels.settings.LocalMtpMode
import com.fersaiyan.cyanbridge.localmodels.settings.LocalMtpSettingsRepository
import com.fersaiyan.cyanbridge.localmodels.storage.InstalledLocalModel
import com.fersaiyan.cyanbridge.localmodels.storage.LocalModelFileUtils
import com.fersaiyan.cyanbridge.localmodels.storage.LocalModelStorageRepository
import com.fersaiyan.cyanbridge.plugins.PluginVoicePermissions
import com.fersaiyan.cyanbridge.shared.localmodels.InstalledModelUiItem
import com.fersaiyan.cyanbridge.shared.localmodels.LocalModelCatalogUiItem
import com.fersaiyan.cyanbridge.shared.localmodels.LocalModelDownloadUiState
import com.fersaiyan.cyanbridge.shared.localmodels.LocalModelGenerationUiState
import com.fersaiyan.cyanbridge.shared.localmodels.LocalModelOptionField
import com.fersaiyan.cyanbridge.shared.localmodels.LocalModelTextField
import com.fersaiyan.cyanbridge.shared.localmodels.LocalModelToggleField
import com.fersaiyan.cyanbridge.shared.localmodels.LocalModelsAction
import com.fersaiyan.cyanbridge.shared.localmodels.LocalModelsConfigureUiState
import com.fersaiyan.cyanbridge.shared.localmodels.LocalModelsSection
import com.fersaiyan.cyanbridge.shared.localmodels.RemoteInferenceUiState
import com.fersaiyan.cyanbridge.shared.localmodels.StudioBridgeUiState
import com.fersaiyan.cyanbridge.shared.ui.localmodels.LocalModelsConfigureScreen
import com.fersaiyan.cyanbridge.ui.MyApplication
import com.fersaiyan.cyanbridge.ui.appearance.AppearancePreferences
import com.fersaiyan.cyanbridge.ui.appearance.rememberAppearanceSettings
import com.fersaiyan.cyanbridge.ui.theme.CyanBridgeTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

class LocalModelsConfigureActivity : AppCompatActivity() {
    private var uiState by mutableStateOf(LocalModelsConfigureUiState())
    private var installedModels: List<InstalledLocalModel> = emptyList()
    private var deviceSnapshot: DeviceSnapshot? = null
    private var downloadReceiver: BroadcastReceiver? = null
    private var downloadState = LocalModelDownloadUiState()
    private var hasUnsavedChanges = false

    private var generationDraft = GenerationDraft()
    private var remoteDraft = RemoteDraft()
    private var studioDraft = StudioDraft()

    private val sectionPrefs by lazy {
        getSharedPreferences("local_models_sections", MODE_PRIVATE)
    }

    private val importModelLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) importModel(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val appearancePreferences = AppearancePreferences(this)
        setContent {
            val appearance by rememberAppearanceSettings(appearancePreferences)
            CyanBridgeTheme(appearance) {
                LocalModelsConfigureScreen(
                    state = uiState,
                    onAction = ::handleAction,
                )
            }
        }

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() = requestClose()
            },
        )

        registerDownloadReceiver()
        refreshAllUi(loadDrafts = true)
    }

    override fun onResume() {
        super.onResume()
        // Refresh test recommendations/download results after returning from the benchmark screen.
        if (::uiState.isInitializedCompat()) return
        refreshAllUi(loadDrafts = !hasUnsavedChanges)
    }

    override fun onDestroy() {
        super.onDestroy()
        downloadReceiver?.let { runCatching { unregisterReceiver(it) } }
        downloadReceiver = null
    }

    private fun handleAction(action: LocalModelsAction) {
        when (action) {
            LocalModelsAction.Back -> requestClose()
            LocalModelsAction.DiscardChangesAndBack -> finish()
            LocalModelsAction.Refresh -> refreshAllUi(loadDrafts = !hasUnsavedChanges)
            LocalModelsAction.ImportModel -> importModelLauncher.launch(arrayOf("application/octet-stream", "*/*"))
            is LocalModelsAction.SelectInstalledModel -> selectModel(action.id)
            LocalModelsAction.ShowSelectedModelInfo -> showSelectedModelInfo()
            LocalModelsAction.UnloadSelectedModel -> unloadSelectedModel()
            LocalModelsAction.RemoveSelectedModel -> confirmRemoveSelectedModel()
            is LocalModelsAction.DownloadCatalogModel -> {
                LocalModelCatalogRepository.findById(action.id)?.let(::requestDownload)
            }
            is LocalModelsAction.ShowCatalogModelInfo -> {
                LocalModelCatalogRepository.findById(action.id)?.let(::showCatalogInfo)
            }
            LocalModelsAction.CancelDownload -> {
                ModelDownloadForegroundService.cancelDownload(this)
                downloadState = LocalModelDownloadUiState(message = "Download cancelled")
                refreshComposeState()
            }
            LocalModelsAction.RunWarmup -> launchModelTest()
            LocalModelsAction.SaveGenerationSettings -> saveGenerationSettings(showToast = true)
            is LocalModelsAction.ToggleSection -> toggleSection(action.section)
            is LocalModelsAction.UpdateText -> updateText(action.field, action.value)
            is LocalModelsAction.SelectOption -> updateOption(action.field, action.index)
            is LocalModelsAction.SetToggle -> updateToggle(action.field, action.enabled)
            LocalModelsAction.TestRemoteServer -> testRemoteServerConnection()
            LocalModelsAction.SaveRemoteServer -> saveRemoteServerConfig(showToast = true)
            LocalModelsAction.ShowStudioBridgeApiKeyHelp -> showApiKeyHelpDialog()
            LocalModelsAction.SaveStudioBridge -> saveStudioBridgeConfig()
        }
    }

    private fun refreshAllUi(loadDrafts: Boolean) {
        LocalModelStorageRepository.cleanupMissingModels(this)
        installedModels = LocalModelStorageRepository.listInstalled(this)
        val selected = LocalModelStorageRepository.resolveSelectedModel(this)
        deviceSnapshot = DeviceCapabilityService.snapshot(this)
        if (loadDrafts) {
            loadGenerationDraft(selected)
            loadRemoteDraft()
            loadStudioDraft()
            hasUnsavedChanges = false
        }
        syncDownloadStateFromService()
        refreshComposeState()
    }

    private fun refreshComposeState() {
        val selected = selectedModel()
        val snapshot = deviceSnapshot ?: DeviceCapabilityService.snapshot(this).also { deviceSnapshot = it }
        val ramGb = snapshot.totalRamBytes / GIB
        val freeGb = snapshot.freeStorageBytes / GIB
        val mtpSupport = mtpSupportFor(selected)
        val mtpStatus = mtpStatusFor(selected, mtpSupport)
        val templateOptions = buildList {
            add("Auto (catalog default)")
            addAll(
                com.fersaiyan.cyanbridge.localmodels.templates.PromptTemplateRegistry.templates.map {
                    "${it.label} (${it.id})"
                },
            )
        }
        val installedByCatalogId = installedModels.associateBy { it.catalogId }

        uiState = LocalModelsConfigureUiState(
            engineStatus = selected?.let { "Ready for ${it.displayName}" } ?: "Runtimes available: llama.cpp + LiteRT",
            deviceSummary = "${snapshot.primaryAbi} • ${format1(ramGb)} GB RAM • ${format2(freeGb)} GB free",
            selectedModelStatus = selected?.let {
                val exists = File(it.absolutePath).exists()
                "${if (exists) "Ready" else "Missing file"} • ${humanSize(it.sizeBytes)}"
            } ?: "No model selected",
            emptyStateMessage = if (installedModels.isEmpty()) {
                "No local model installed. Gemma 4 E2B is the recommended multimodal starter."
            } else "",
            installedModels = installedModels.map {
                InstalledModelUiItem(it.id, "${it.displayName} (${humanSize(it.sizeBytes)})")
            },
            selectedInstalledModelId = selected?.id,
            catalog = LocalModelCatalogRepository.curatedModels.map { entry ->
                val installed = installedByCatalogId[entry.id]
                LocalModelCatalogUiItem(
                    id = entry.id,
                    title = entry.displayName,
                    details = "${entry.quantization} • ${humanSize(entry.sizeBytes)} • ${entry.shortDescription}",
                    status = statusText(entry, installed),
                    downloadLabel = when {
                        ModelDownloadForegroundService.isDownloading && ModelDownloadForegroundService.downloadingModelId == entry.id -> "Downloading…"
                        installed != null -> "Installed"
                        entry.comingSoon -> "Coming soon"
                        entry.sourceUrl.isNullOrBlank() -> "Manual import"
                        !assessCatalogEntry(entry).supported -> "Unavailable"
                        entry.gatedDownload -> "Download (token)"
                        else -> "Download"
                    },
                    canDownload = canDownload(entry, installed),
                )
            },
            catalogExpanded = sectionExpanded(LocalModelsSection.CATALOG),
            remoteServerExpanded = sectionExpanded(LocalModelsSection.REMOTE_SERVER),
            studioBridgeExpanded = sectionExpanded(LocalModelsSection.STUDIO_BRIDGE),
            generationSettingsExpanded = sectionExpanded(LocalModelsSection.GENERATION_SETTINGS),
            download = downloadState,
            hasUnsavedChanges = hasUnsavedChanges,
            warmupResult = LocalModelsPrefs.getLastBenchmark(this),
            generation = LocalModelGenerationUiState(
                computeBackendOptions = LocalComputeBackend.entries.map { it.label },
                computeBackendIndex = generationDraft.computeBackend.ordinal.coerceAtLeast(0),
                computeBackendNote = backendNote(generationDraft.computeBackend, generationDraft.runtime),
                mtpOptions = LocalMtpMode.entries.map { it.label },
                mtpIndex = generationDraft.mtpMode.ordinal,
                mtpSupported = mtpSupport,
                mtpStatus = mtpStatus,
                systemPrompt = generationDraft.systemPrompt,
                runtimeOptions = LocalModelRuntime.entries.map { it.label },
                runtimeIndex = generationDraft.runtime.ordinal,
                runtimeNote = runtimeNote(generationDraft.runtime),
                cpuThreads = generationDraft.cpuThreads,
                gpuLayers = generationDraft.gpuLayers,
                gpuLayersEnabled = generationDraft.computeBackend != LocalComputeBackend.CPU,
                temperature = generationDraft.temperature,
                topP = generationDraft.topP,
                topK = generationDraft.topK,
                maxTokens = generationDraft.maxTokens,
                repetitionPenalty = generationDraft.repetitionPenalty,
                contextSize = generationDraft.contextSize,
                seed = generationDraft.seed,
                templateOptions = templateOptions,
                templateIndex = generationDraft.templateIndex.coerceIn(0, templateOptions.lastIndex.coerceAtLeast(0)),
                experimentalStructuredJson = generationDraft.structuredJson,
                huggingFaceToken = generationDraft.huggingFaceToken,
            ),
            remoteServer = RemoteInferenceUiState(
                enabled = remoteDraft.enabled,
                baseUrl = remoteDraft.baseUrl,
                modelName = remoteDraft.model,
                apiKey = remoteDraft.apiKey,
                status = remoteDraft.status,
            ),
            studioBridge = StudioBridgeUiState(
                enabled = studioDraft.enabled,
                apiKey = studioDraft.apiKey,
                status = studioDraft.status,
            ),
        )
    }

    private fun loadGenerationDraft(model: InstalledLocalModel?) {
        if (model == null) {
            val defaults = LocalGenerationSettings.defaultsFor(null)
            generationDraft = GenerationDraft.from(defaults, LocalMtpMode.AUTO, LocalModelsPrefs.getHuggingFaceToken(this))
            return
        }
        val settings = LocalModelSettingsRepository.getForModel(this, model.id)
        val templates = com.fersaiyan.cyanbridge.localmodels.templates.PromptTemplateRegistry.templates
        val templateIndex = templates.indexOfFirst { it.id == settings.templateOverrideId }
            .let { if (it >= 0) it + 1 else 0 }
        generationDraft = GenerationDraft.from(
            settings = settings,
            mtpMode = LocalMtpSettingsRepository.getMode(this, model.id),
            huggingFaceToken = LocalModelsPrefs.getHuggingFaceToken(this),
        ).copy(templateIndex = templateIndex)
    }

    private fun loadRemoteDraft() {
        remoteDraft = RemoteDraft(
            enabled = RemoteOpenAiPrefs.isEnabled(this),
            baseUrl = RemoteOpenAiPrefs.getBaseUrl(this),
            model = RemoteOpenAiPrefs.getModel(this),
            apiKey = RemoteOpenAiPrefs.getApiKey(this),
            status = if (RemoteOpenAiPrefs.isActive(this)) {
                "Active: ${RemoteOpenAiPrefs.getModel(this)} @ ${RemoteOpenAiPrefs.getBaseUrl(this)}"
            } else "",
        )
    }

    private fun loadStudioDraft() {
        studioDraft = StudioDraft(
            enabled = RemoteOpenAiPrefs.isBridgeEnabled(this),
            apiKey = RemoteOpenAiPrefs.getApiKey(this),
            status = when {
                RemoteOpenAiPrefs.isBridgeConfigured(this) -> "Bridge configured for voice approvals."
                RemoteOpenAiPrefs.isBridgeEnabled(this) -> "Bridge enabled but server URL, model, or API key is missing."
                else -> ""
            },
        )
    }

    private fun selectModel(id: String) {
        LocalModelsPrefs.setSelectedModelId(this, id)
        loadGenerationDraft(selectedModel())
        hasUnsavedChanges = false
        refreshComposeState()
    }

    private fun updateText(field: LocalModelTextField, value: String) {
        generationDraft = when (field) {
            LocalModelTextField.CPU_THREADS -> generationDraft.copy(cpuThreads = value)
            LocalModelTextField.GPU_LAYERS -> generationDraft.copy(gpuLayers = value)
            LocalModelTextField.TEMPERATURE -> generationDraft.copy(temperature = value)
            LocalModelTextField.TOP_P -> generationDraft.copy(topP = value)
            LocalModelTextField.TOP_K -> generationDraft.copy(topK = value)
            LocalModelTextField.MAX_TOKENS -> generationDraft.copy(maxTokens = value)
            LocalModelTextField.REPETITION_PENALTY -> generationDraft.copy(repetitionPenalty = value)
            LocalModelTextField.CONTEXT_SIZE -> generationDraft.copy(contextSize = value)
            LocalModelTextField.SEED -> generationDraft.copy(seed = value)
            LocalModelTextField.SYSTEM_PROMPT -> generationDraft.copy(systemPrompt = value)
            LocalModelTextField.HUGGING_FACE_TOKEN -> generationDraft.copy(huggingFaceToken = value)
            LocalModelTextField.REMOTE_BASE_URL -> {
                remoteDraft = remoteDraft.copy(baseUrl = value)
                generationDraft
            }
            LocalModelTextField.REMOTE_MODEL_NAME -> {
                remoteDraft = remoteDraft.copy(model = value)
                generationDraft
            }
            LocalModelTextField.REMOTE_API_KEY -> {
                remoteDraft = remoteDraft.copy(apiKey = value)
                generationDraft
            }
            LocalModelTextField.STUDIO_BRIDGE_API_KEY -> {
                studioDraft = studioDraft.copy(apiKey = value)
                generationDraft
            }
        }
        hasUnsavedChanges = true
        refreshComposeState()
    }

    private fun updateOption(field: LocalModelOptionField, index: Int) {
        when (field) {
            LocalModelOptionField.RUNTIME -> {
                generationDraft = generationDraft.copy(
                    runtime = LocalModelRuntime.entries.getOrElse(index) { LocalModelRuntime.LLAMA_CPP },
                )
            }
            LocalModelOptionField.COMPUTE_BACKEND -> {
                generationDraft = generationDraft.copy(
                    computeBackend = LocalComputeBackend.entries.getOrElse(index) { LocalComputeBackend.GPU },
                )
            }
            LocalModelOptionField.MTP_MODE -> {
                generationDraft = generationDraft.copy(
                    mtpMode = LocalMtpMode.entries.getOrElse(index) { LocalMtpMode.AUTO },
                )
            }
            LocalModelOptionField.TEMPLATE -> generationDraft = generationDraft.copy(templateIndex = index)
        }
        hasUnsavedChanges = true
        refreshComposeState()
    }

    private fun updateToggle(field: LocalModelToggleField, enabled: Boolean) {
        when (field) {
            LocalModelToggleField.EXPERIMENTAL_STRUCTURED_JSON -> generationDraft = generationDraft.copy(structuredJson = enabled)
            LocalModelToggleField.REMOTE_SERVER_ENABLED -> remoteDraft = remoteDraft.copy(enabled = enabled)
            LocalModelToggleField.STUDIO_BRIDGE_ENABLED -> studioDraft = studioDraft.copy(enabled = enabled)
        }
        hasUnsavedChanges = true
        refreshComposeState()
    }

    private fun saveGenerationSettings(showToast: Boolean): Boolean {
        val model = selectedModel()
        LocalModelsPrefs.setHuggingFaceToken(this, generationDraft.huggingFaceToken)
        if (model == null) {
            if (showToast) Toast.makeText(this, "Saved download token. Install a model to save model settings.", Toast.LENGTH_SHORT).show()
            hasUnsavedChanges = false
            refreshComposeState()
            return false
        }

        val existing = LocalModelSettingsRepository.getForModel(this, model.id)
        val templates = com.fersaiyan.cyanbridge.localmodels.templates.PromptTemplateRegistry.templates
        val templateId = if (generationDraft.templateIndex <= 0) null
        else templates.getOrNull(generationDraft.templateIndex - 1)?.id
        val settings = existing.copy(
            temperature = generationDraft.temperature.toDoubleOrNull()?.coerceIn(0.0, 2.0) ?: existing.temperature,
            topP = generationDraft.topP.toDoubleOrNull()?.coerceIn(0.0, 1.0) ?: existing.topP,
            topK = generationDraft.topK.toIntOrNull()?.coerceIn(0, 200) ?: existing.topK,
            maxTokens = generationDraft.maxTokens.toIntOrNull()
                ?.coerceIn(LocalGenerationSettings.MIN_MAX_TOKENS, LocalGenerationSettings.MAX_MAX_TOKENS)
                ?: existing.maxTokens,
            repetitionPenalty = generationDraft.repetitionPenalty.toDoubleOrNull()?.coerceIn(0.8, 2.0)
                ?: existing.repetitionPenalty,
            contextSize = generationDraft.contextSize.toIntOrNull()
                ?.coerceIn(LocalGenerationSettings.MIN_CONTEXT_SIZE, LocalGenerationSettings.MAX_CONTEXT_SIZE)
                ?: existing.contextSize,
            seed = generationDraft.seed.toIntOrNull() ?: existing.seed,
            systemPromptOverride = generationDraft.systemPrompt.trim(),
            templateOverrideId = templateId,
            experimentalStructuredJson = generationDraft.structuredJson,
            computeBackend = generationDraft.computeBackend,
            cpuThreads = generationDraft.cpuThreads.toIntOrNull()?.coerceIn(1, 16) ?: existing.cpuThreads,
            gpuLayers = generationDraft.gpuLayers.toIntOrNull()?.coerceIn(-1, 999) ?: existing.gpuLayers,
            modelRuntime = generationDraft.runtime,
        )
        LocalModelSettingsRepository.saveForModel(this, model.id, settings)
        LocalMtpSettingsRepository.setMode(this, model.id, generationDraft.mtpMode)
        loadGenerationDraft(model)
        hasUnsavedChanges = false
        setResult(RESULT_OK)
        if (showToast) Toast.makeText(this, "Local model settings saved", Toast.LENGTH_SHORT).show()
        refreshComposeState()
        return true
    }

    private fun launchModelTest() {
        val model = selectedModel() ?: run {
            Toast.makeText(this, "Install or select a model first", Toast.LENGTH_SHORT).show()
            return
        }
        saveGenerationSettings(showToast = false)
        startActivity(
            Intent(this, LocalModelTestActivity::class.java)
                .putExtra(LocalModelTestActivity.EXTRA_MODEL_ID, model.id),
        )
    }

    private fun mtpSupportFor(model: InstalledLocalModel?): Boolean? {
        if (model == null || generationDraft.runtime != LocalModelRuntime.LITERT) return null
        val file = File(model.absolutePath)
        if (!file.exists()) return false
        return LiteRtLocalInferenceEngine.supportsSpeculativeDecoding(file.absolutePath)
    }

    private fun mtpStatusFor(model: InstalledLocalModel?, supported: Boolean?): String {
        if (model == null) return "Select a LiteRT-LM model to inspect MTP support."
        if (generationDraft.runtime != LocalModelRuntime.LITERT) {
            return "MTP is available only for compatible LiteRT-LM packages."
        }
        if (supported != true) return "Not supported by this model package."
        val file = File(model.absolutePath)
        val record = LocalMtpSettingsRepository.getBenchmark(
            context = this,
            modelId = model.id,
            backend = generationDraft.computeBackend,
            modelSignature = LocalMtpSettingsRepository.modelSignature(file.absolutePath, file.length(), file.lastModified()),
        )
        return when {
            generationDraft.mtpMode == LocalMtpMode.ON -> "Supported • forced on."
            generationDraft.mtpMode == LocalMtpMode.OFF -> "Supported • forced off."
            record == null -> "Supported • Automatic uses MTP until this device/backend is benchmarked."
            record.recommendMtp -> "Supported • Automatic recommends MTP (${format2(record.mtpOnOutputTokensPerSecond)} vs ${format2(record.mtpOffOutputTokensPerSecond)} tok/s)."
            else -> "Supported • Automatic keeps MTP off (${format2(record.mtpOnOutputTokensPerSecond)} vs ${format2(record.mtpOffOutputTokensPerSecond)} tok/s)."
        }
    }

    private fun requestDownload(entry: LocalModelCatalogEntry) {
        if (ModelDownloadForegroundService.isDownloading) {
            Toast.makeText(this, "A model download is already in progress", Toast.LENGTH_SHORT).show()
            return
        }
        val assessment = assessCatalogEntry(entry)
        if (!assessment.supported) {
            Toast.makeText(this, assessment.blockers.joinToString(" "), Toast.LENGTH_LONG).show()
            return
        }
        val token = generationDraft.huggingFaceToken.trim().ifBlank { null }
        if (entry.gatedDownload && token == null) {
            Toast.makeText(this, "Add a Hugging Face token after accepting the model terms.", Toast.LENGTH_LONG).show()
            return
        }
        val start = {
            downloadState = LocalModelDownloadUiState(isInFlight = true, message = "Starting ${entry.displayName}…")
            refreshComposeState()
            ModelDownloadForegroundService.startDownload(this, entry.id, token)
        }
        if (assessment.warnings.isNotEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("Device warning")
                .setMessage(assessment.warnings.joinToString("\n"))
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Continue") { _, _ -> start() }
                .show()
        } else start()
    }

    private fun syncDownloadStateFromService() {
        if (ModelDownloadForegroundService.isDownloading) {
            downloadState = LocalModelDownloadUiState(
                isInFlight = true,
                message = ModelDownloadForegroundService.lastStatusMessage ?: "Downloading…",
                progressPercent = ModelDownloadForegroundService.lastPercent,
            )
        } else if (downloadState.isInFlight) {
            downloadState = LocalModelDownloadUiState()
        }
    }

    private fun registerDownloadReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    ModelDownloadForegroundService.BROADCAST_PROGRESS -> {
                        val pct = intent.getIntExtra(ModelDownloadForegroundService.EXTRA_PERCENT, 0)
                        val downloaded = intent.getLongExtra(ModelDownloadForegroundService.EXTRA_DOWNLOADED_BYTES, 0L)
                        val total = intent.getLongExtra(ModelDownloadForegroundService.EXTRA_TOTAL_BYTES, 0L)
                        downloadState = LocalModelDownloadUiState(
                            isInFlight = true,
                            message = "Downloading: $pct% (${humanSize(downloaded)} / ${if (total > 0) humanSize(total) else "?"})",
                            progressPercent = pct.takeIf { it > 0 },
                        )
                        refreshComposeState()
                    }
                    ModelDownloadForegroundService.BROADCAST_DOWNLOAD_FINISHED -> {
                        val success = intent.getBooleanExtra(ModelDownloadForegroundService.EXTRA_SUCCESS, false)
                        val error = intent.getStringExtra(ModelDownloadForegroundService.EXTRA_ERROR)
                        downloadState = LocalModelDownloadUiState(
                            message = if (success) "Download complete" else "Download failed: ${error ?: "unknown error"}",
                        )
                        if (success) refreshAllUi(loadDrafts = true) else refreshComposeState()
                    }
                }
            }
        }
        downloadReceiver = receiver
        val filter = IntentFilter().apply {
            addAction(ModelDownloadForegroundService.BROADCAST_PROGRESS)
            addAction(ModelDownloadForegroundService.BROADCAST_DOWNLOAD_FINISHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }
    }

    private fun importModel(uri: Uri) {
        lifecycleScope.launch {
            downloadState = LocalModelDownloadUiState(isInFlight = true, message = "Importing model…")
            refreshComposeState()
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val file = LocalModelStorageRepository.copyUriToManagedModelFile(
                        context = this@LocalModelsConfigureActivity,
                        uri = uri,
                        preferredName = guessDisplayName(uri),
                    )
                    if (!LocalModelFileUtils.isSupportedModelFile(file)) {
                        file.delete()
                        error("Imported file must be GGUF or LiteRT (.litertlm/.task)")
                    }
                    LocalModelStorageRepository.registerImportedModel(
                        context = this@LocalModelsConfigureActivity,
                        displayName = file.nameWithoutExtension,
                        file = file,
                    )
                }
            }
            result.fold(
                onSuccess = {
                    downloadState = LocalModelDownloadUiState(message = "Import complete: ${it.displayName}")
                    refreshAllUi(loadDrafts = true)
                },
                onFailure = {
                    downloadState = LocalModelDownloadUiState(message = "Import failed: ${it.message}")
                    refreshComposeState()
                },
            )
        }
    }

    private fun guessDisplayName(uri: Uri): String {
        if (uri.scheme == "file") return File(uri.path.orEmpty()).name.ifBlank { "imported-model.gguf" }
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) return cursor.getString(index).orEmpty().ifBlank { "imported-model.gguf" }
            }
        }
        return "imported-model.gguf"
    }

    private fun unloadSelectedModel() {
        lifecycleScope.launch {
            LocalChatSessionManager.unload()
            Toast.makeText(this@LocalModelsConfigureActivity, "Local model unloaded", Toast.LENGTH_SHORT).show()
            refreshComposeState()
        }
    }

    private fun confirmRemoveSelectedModel() {
        val model = selectedModel() ?: return
        AlertDialog.Builder(this)
            .setTitle("Remove model?")
            .setMessage("Delete ${model.displayName} from local storage?")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Remove") { _, _ ->
                lifecycleScope.launch { runCatching { LocalChatSessionManager.unload() } }
                LocalModelStorageRepository.removeInstalled(this, model.id)
                LocalModelSettingsRepository.clearForModel(this, model.id)
                LocalMtpSettingsRepository.clearForModel(this, model.id)
                refreshAllUi(loadDrafts = true)
            }
            .show()
    }

    private fun showSelectedModelInfo() {
        val model = selectedModel() ?: return
        val entry = LocalModelCatalogRepository.findById(model.catalogId)
        AlertDialog.Builder(this)
            .setTitle(model.displayName)
            .setMessage(
                "Family: ${entry?.family ?: "custom"}\n" +
                    "Runtime: ${generationDraft.runtime.label}\n" +
                    "Quantization: ${model.quantization ?: "unknown"}\n" +
                    "Size: ${humanSize(model.sizeBytes)}\n" +
                    "Location: ${model.absolutePath}",
            )
            .setPositiveButton("Close", null)
            .show()
    }

    private fun showCatalogInfo(entry: LocalModelCatalogEntry) {
        AlertDialog.Builder(this)
            .setTitle(entry.displayName)
            .setMessage(
                "${entry.shortDescription}\n\nRuntime: ${entry.engine}\nFormat: ${entry.format}\n" +
                    "Quantization: ${entry.quantization}\nSize: ${humanSize(entry.sizeBytes)}\n" +
                    "RAM minimum: ${entry.minRamGb} GB\nLicense: ${entry.licenseTermsNote}",
            )
            .setNegativeButton("Close", null)
            .setPositiveButton("Open source") { _, _ ->
                val url = entry.sourcePageUrl ?: entry.sourceUrl ?: return@setPositiveButton
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            }
            .show()
    }

    private fun saveRemoteServerConfig(showToast: Boolean): Boolean {
        val url = remoteDraft.baseUrl.trim()
        val model = remoteDraft.model.trim()
        val key = remoteDraft.apiKey.trim()
        if (remoteDraft.enabled && (url.isBlank() || model.isBlank())) {
            Toast.makeText(this, "Base URL and model name are required.", Toast.LENGTH_SHORT).show()
            return false
        }
        if (key.isNotBlank() && !RemoteOpenAiPrefs.isCredentialTransportAllowed(url)) {
            Toast.makeText(this, "API keys require HTTPS, a private LAN address, or a Tailscale address.", Toast.LENGTH_LONG).show()
            return false
        }
        RemoteOpenAiPrefs.setBaseUrl(this, url)
        RemoteOpenAiPrefs.setModel(this, model)
        RemoteOpenAiPrefs.setApiKey(this, key)
        RemoteOpenAiPrefs.setEnabled(this, remoteDraft.enabled)
        remoteDraft = remoteDraft.copy(
            status = if (remoteDraft.enabled) "Active: $model @ $url" else "Saved (disabled)",
        )
        studioDraft = studioDraft.copy(apiKey = key)
        hasUnsavedChanges = false
        if (showToast) Toast.makeText(this, "Remote server settings saved", Toast.LENGTH_SHORT).show()
        refreshComposeState()
        return true
    }

    private fun testRemoteServerConnection() {
        if (!saveRemoteServerConfig(showToast = false)) return
        remoteDraft = remoteDraft.copy(status = "Testing connection…")
        refreshComposeState()
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { RemoteOpenAiClient.healthCheck(this@LocalModelsConfigureActivity) }
            }
            remoteDraft = remoteDraft.copy(
                status = result.fold(
                    onSuccess = { "Connection: $it" },
                    onFailure = { "Connection failed: ${it.message}" },
                ),
            )
            refreshComposeState()
        }
    }

    private fun saveStudioBridgeConfig() {
        val enabled = studioDraft.enabled
        val key = studioDraft.apiKey.trim()
        if (enabled) {
            val baseUrl = RemoteOpenAiPrefs.getBaseUrl(this)
            if (baseUrl.isBlank() || RemoteOpenAiPrefs.getModel(this).isBlank()) {
                Toast.makeText(this, "Configure the Remote model server first.", Toast.LENGTH_LONG).show()
                return
            }
            if (key.isBlank()) {
                Toast.makeText(this, "Studio API key is required.", Toast.LENGTH_SHORT).show()
                return
            }
            if (!RemoteOpenAiPrefs.isCredentialTransportAllowed(baseUrl)) {
                Toast.makeText(this, "Studio credentials require HTTPS, LAN, or Tailscale transport.", Toast.LENGTH_LONG).show()
                return
            }
            if (!android.speech.SpeechRecognizer.isRecognitionAvailable(this)) {
                Toast.makeText(this, "No speech recognizer is available on this device.", Toast.LENGTH_LONG).show()
                return
            }
            if (!PluginVoicePermissions.hasRequiredPermissions(this)) {
                PluginVoicePermissions.ensure(this) { saveStudioBridgeConfig() }
                return
            }
            RemoteOpenAiPrefs.setApiKey(this, key)
            remoteDraft = remoteDraft.copy(apiKey = key)
        }
        RemoteOpenAiPrefs.setBridgeEnabled(this, enabled)
        val app = application as? MyApplication
        studioDraft = if (enabled) {
            val started = app?.startStudioBridge() == true
            studioDraft.copy(status = if (started) "Bridge connecting…" else "Bridge could not start. Check server/model settings.")
        } else {
            app?.stopStudioBridge()
            studioDraft.copy(status = "")
        }
        hasUnsavedChanges = false
        refreshComposeState()
    }

    private fun showApiKeyHelpDialog() {
        AlertDialog.Builder(this)
            .setTitle("CyanBridge Model Studio API key")
            .setMessage(
                "Open CyanBridge Model Studio on your other device, go to Settings → API Keys, " +
                    "create a key, and paste it here. The Remote model server URL/model above are used by Studio Bridge.",
            )
            .setPositiveButton("Got it", null)
            .show()
    }

    private fun toggleSection(section: LocalModelsSection) {
        val key = sectionKey(section)
        sectionPrefs.edit().putBoolean(key, !sectionExpanded(section)).apply()
        refreshComposeState()
    }

    private fun sectionExpanded(section: LocalModelsSection): Boolean =
        sectionPrefs.getBoolean(sectionKey(section), false)

    private fun sectionKey(section: LocalModelsSection) = "section_${section.name.lowercase(Locale.US)}"

    private fun requestClose() {
        if (!hasUnsavedChanges) {
            finish()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Unsaved changes")
            .setMessage("Leave without saving your Local Models changes?")
            .setNegativeButton("Keep editing", null)
            .setPositiveButton("Discard") { _, _ -> finish() }
            .show()
    }

    private fun selectedModel(): InstalledLocalModel? =
        LocalModelStorageRepository.resolveSelectedModel(this)

    private fun assessCatalogEntry(entry: LocalModelCatalogEntry) = DeviceCapabilityService.assess(
        snapshot = deviceSnapshot ?: DeviceCapabilityService.snapshot(this).also { deviceSnapshot = it },
        entry = entry,
        requireDownloadHeadroom = !entry.sourceUrl.isNullOrBlank(),
    )

    private fun canDownload(entry: LocalModelCatalogEntry, installed: InstalledLocalModel?): Boolean {
        if (installed != null || !entry.enabled || entry.comingSoon || entry.sourceUrl.isNullOrBlank()) return false
        if (ModelDownloadForegroundService.isDownloading) return false
        if (entry.gatedDownload && generationDraft.huggingFaceToken.isBlank()) return false
        return assessCatalogEntry(entry).supported
    }

    private fun statusText(entry: LocalModelCatalogEntry, installed: InstalledLocalModel?): String {
        if (installed != null) return "Ready"
        if (entry.comingSoon) return "Coming soon"
        val assessment = assessCatalogEntry(entry)
        if (!assessment.supported) return assessment.blockers.joinToString(" ")
        return assessment.warnings.joinToString(" ").ifBlank { "Compatible with this device" }
    }

    private fun backendNote(backend: LocalComputeBackend, runtime: LocalModelRuntime): String = when (backend) {
        LocalComputeBackend.GPU -> if (runtime == LocalModelRuntime.LITERT) {
            "Recommended default. LiteRT uses the device GPU and falls back to CPU if initialization fails."
        } else {
            "Recommended default. llama.cpp offloads compatible layers to the GPU."
        }
        LocalComputeBackend.CPU -> "Most compatible, but usually slower on modern phones."
        LocalComputeBackend.NPU_EXPERIMENTAL -> if (runtime == LocalModelRuntime.LITERT) {
            "Uses LiteRT's NPU backend when the device and model package support it; otherwise CyanBridge falls back to GPU/CPU."
        } else {
            "NPU requires a compatible LiteRT model. llama.cpp does not currently expose CyanBridge's NPU path."
        }
    }

    private fun runtimeNote(runtime: LocalModelRuntime): String = when (runtime) {
        LocalModelRuntime.LLAMA_CPP -> "For GGUF models."
        LocalModelRuntime.LITERT -> "For .litertlm/.task packages, including Gemma 4 multimodal models and MTP-capable packages."
        LocalModelRuntime.REMOTE_OPENAI -> "Uses the Remote model server configured above."
    }

    private fun humanSize(bytes: Long): String {
        val b = bytes.toDouble()
        return when {
            b >= GIB -> String.format(Locale.US, "%.2f GB", b / GIB)
            b >= MIB -> String.format(Locale.US, "%.1f MB", b / MIB)
            b >= KIB -> String.format(Locale.US, "%.1f KB", b / KIB)
            else -> "$bytes B"
        }
    }

    private fun format1(value: Double) = String.format(Locale.US, "%.1f", value)
    private fun format2(value: Double) = String.format(Locale.US, "%.2f", value)

    private data class GenerationDraft(
        val runtime: LocalModelRuntime = LocalModelRuntime.LLAMA_CPP,
        val computeBackend: LocalComputeBackend = LocalComputeBackend.GPU,
        val mtpMode: LocalMtpMode = LocalMtpMode.AUTO,
        val cpuThreads: String = LocalGenerationSettings.defaultCpuThreads().toString(),
        val gpuLayers: String = "-1",
        val temperature: String = LocalGenerationSettings.DEFAULT_TEMPERATURE.toString(),
        val topP: String = LocalGenerationSettings.DEFAULT_TOP_P.toString(),
        val topK: String = LocalGenerationSettings.DEFAULT_TOP_K.toString(),
        val maxTokens: String = LocalGenerationSettings.DEFAULT_MAX_OUTPUT_TOKENS.toString(),
        val repetitionPenalty: String = LocalGenerationSettings.DEFAULT_REPETITION_PENALTY.toString(),
        val contextSize: String = "4096",
        val seed: String = "-1",
        val templateIndex: Int = 0,
        val structuredJson: Boolean = false,
        val systemPrompt: String = LocalGenerationSettings.DEFAULT_SYSTEM_PROMPT,
        val huggingFaceToken: String = "",
    ) {
        companion object {
            fun from(
                settings: LocalGenerationSettings,
                mtpMode: LocalMtpMode,
                huggingFaceToken: String,
            ) = GenerationDraft(
                runtime = settings.modelRuntime,
                computeBackend = settings.computeBackend,
                mtpMode = mtpMode,
                cpuThreads = settings.cpuThreads.toString(),
                gpuLayers = settings.gpuLayers.toString(),
                temperature = settings.temperature.toString(),
                topP = settings.topP.toString(),
                topK = settings.topK.toString(),
                maxTokens = settings.maxTokens.toString(),
                repetitionPenalty = settings.repetitionPenalty.toString(),
                contextSize = settings.contextSize.toString(),
                seed = settings.seed.toString(),
                structuredJson = settings.experimentalStructuredJson,
                systemPrompt = settings.systemPromptOverride,
                huggingFaceToken = huggingFaceToken,
            )
        }
    }

    private data class RemoteDraft(
        val enabled: Boolean = false,
        val baseUrl: String = "",
        val model: String = "",
        val apiKey: String = "",
        val status: String = "",
    )

    private data class StudioDraft(
        val enabled: Boolean = false,
        val apiKey: String = "",
        val status: String = "",
    )

    private fun <T> T.isInitializedCompat(): Boolean = false

    private companion object {
        const val KIB = 1024.0
        const val MIB = KIB * 1024.0
        const val GIB = MIB * 1024.0
    }
}
