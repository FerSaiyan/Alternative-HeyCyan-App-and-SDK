package com.fersaiyan.cyanbridge.ui.localmodels

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fersaiyan.cyanbridge.agent.LocalModelsPrefs
import com.fersaiyan.cyanbridge.localmodels.catalog.LocalModelCatalogEntry
import com.fersaiyan.cyanbridge.localmodels.catalog.LocalModelCatalogRepository
import com.fersaiyan.cyanbridge.localmodels.device.DeviceCapabilityService
import com.fersaiyan.cyanbridge.localmodels.device.DeviceSnapshot
import com.fersaiyan.cyanbridge.localmodels.download.LocalModelDownloadManager
import com.fersaiyan.cyanbridge.localmodels.download.LocalModelDownloadProgress
import com.fersaiyan.cyanbridge.localmodels.session.LocalChatSessionManager
import com.fersaiyan.cyanbridge.localmodels.settings.LocalComputeBackend
import com.fersaiyan.cyanbridge.localmodels.settings.LocalGenerationSettings
import com.fersaiyan.cyanbridge.localmodels.settings.LocalModelPerformanceProfile
import com.fersaiyan.cyanbridge.localmodels.settings.LocalModelSettingsRepository
import com.fersaiyan.cyanbridge.localmodels.storage.InstalledLocalModel
import com.fersaiyan.cyanbridge.localmodels.storage.LocalModelFileUtils
import com.fersaiyan.cyanbridge.localmodels.storage.LocalModelStorageRepository
import com.fersaiyan.cyanbridge.localmodels.templates.PromptTemplateRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

data class CatalogUiEntry(
    val entry: LocalModelCatalogEntry,
    val statusText: String,
    val canDownload: Boolean,
)

data class LocalModelsUiState(
    val deviceSummary: String = "",
    val engineStatus: String = "",
    val installedModels: List<InstalledLocalModel> = emptyList(),
    val selectedModelId: String? = null,
    val selectedModelStatus: String = "",
    val isEmpty: Boolean = true,
    val catalogEntries: List<CatalogUiEntry> = emptyList(),
    val catalogExpanded: Boolean = false,
    val settingsExpanded: Boolean = false,
    val hfToken: String = "",
    val warmupResult: String = "",
    val downloadProgress: String = "",
    val downloadPercent: Int = 0,
    val isDownloading: Boolean = false,

    val profileIndex: Int = 1,
    val temperature: String = "",
    val topP: String = "",
    val topK: String = "",
    val maxTokens: String = "",
    val repetitionPenalty: String = "",
    val contextSize: String = "",
    val seed: String = "",
    val systemPrompt: String = "",
    val computeBackendIndex: Int = 0,
    val cpuThreads: String = "",
    val gpuLayers: String = "",
    val experimentalJson: Boolean = false,
    val templateOverrideIndex: Int = 0,
)

class LocalModelsViewModel(application: Application) : AndroidViewModel(application) {

    private val downloadManager = LocalModelDownloadManager()
    private val downloadCancelled = AtomicBoolean(false)
    private var downloadJob: Job? = null
    private var warmupJob: Job? = null

    private val _uiState = MutableStateFlow(LocalModelsUiState())
    val uiState: StateFlow<LocalModelsUiState> = _uiState.asStateFlow()

    val profiles = LocalModelPerformanceProfile.entries.map { it.label }
    val backends = LocalComputeBackend.entries.map { it.label }
    val templates = listOf("Auto (catalog default)") +
        PromptTemplateRegistry.templates.map { "${it.label} (${it.id})" }

    fun refreshAll() {
        val context = getApplication<Application>()
        LocalModelStorageRepository.cleanupMissingModels(context)
        val snapshot = DeviceCapabilityService.snapshot(context)
        val ramGb = snapshot.totalRamBytes / (1024.0 * 1024.0 * 1024.0)
        val freeGb = snapshot.freeStorageBytes / (1024.0 * 1024.0 * 1024.0)
        val installed = LocalModelStorageRepository.listInstalled(context)
        val selectedId = LocalModelsPrefs.getSelectedModelId(context)
        val actualSelectedId = if (installed.any { it.id == selectedId }) selectedId else installed.firstOrNull()?.id

        if (actualSelectedId != selectedId) {
            LocalModelsPrefs.setSelectedModelId(context, actualSelectedId)
        }

        val selected = installed.firstOrNull { it.id == actualSelectedId }
        val status = if (selected == null) {
            "Status: not downloaded"
        } else {
            val exists = File(selected.absolutePath).exists()
            val s = if (!exists) "failed (missing file)" else "ready"
            "Status: $s | ${selected.displayName}"
        }

        val installedByCatalogId = installed.associateBy { it.catalogId }
        val catalog = LocalModelCatalogRepository.curatedModels.map { entry ->
            CatalogUiEntry(
                entry = entry,
                statusText = catalogStatusText(entry, installedByCatalogId[entry.id]),
                canDownload = !entry.gatedDownload && !entry.sourceUrl.isNullOrBlank() && !installedByCatalogId.containsKey(entry.id),
            )
        }

        val settings = if (actualSelectedId != null) {
            LocalModelSettingsRepository.getForModel(context, actualSelectedId)
        } else {
            LocalGenerationSettings.defaultsFor(null, LocalModelPerformanceProfile.BALANCED)
        }

        val templateIdx = if (settings.templateOverrideId != null) {
            PromptTemplateRegistry.templates.indexOfFirst { it.id == settings.templateOverrideId }.let { if (it >= 0) it + 1 else 0 }
        } else 0

        _uiState.value = _uiState.value.copy(
            deviceSummary = "ABI: ${snapshot.primaryAbi} | RAM: ${String.format("%.1f", ramGb)} GB | Free storage: ${String.format("%.2f", freeGb)} GB",
            engineStatus = "Backend: llama.cpp via Kotlin binding",
            installedModels = installed,
            selectedModelId = actualSelectedId,
            selectedModelStatus = status,
            isEmpty = installed.isEmpty(),
            catalogEntries = catalog,
            hfToken = LocalModelsPrefs.getHuggingFaceToken(context),
            temperature = settings.temperature.toString(),
            topP = settings.topP.toString(),
            topK = settings.topK.toString(),
            maxTokens = settings.maxTokens.toString(),
            repetitionPenalty = settings.repetitionPenalty.toString(),
            contextSize = settings.contextSize.toString(),
            seed = settings.seed.toString(),
            systemPrompt = settings.systemPromptOverride,
            computeBackendIndex = settings.computeBackend.ordinal,
            cpuThreads = settings.cpuThreads.toString(),
            gpuLayers = settings.gpuLayers.toString(),
            experimentalJson = settings.experimentalStructuredJson,
            profileIndex = settings.profile.ordinal,
            templateOverrideIndex = templateIdx,
        )
    }

    fun selectModel(modelId: String?) {
        val context = getApplication<Application>()
        LocalModelsPrefs.setSelectedModelId(context, modelId)
        refreshAll()
    }

    fun toggleCatalogExpanded() {
        _uiState.value = _uiState.value.copy(catalogExpanded = !_uiState.value.catalogExpanded)
    }

    fun toggleSettingsExpanded() {
        _uiState.value = _uiState.value.copy(settingsExpanded = !_uiState.value.settingsExpanded)
    }

    fun onProfileSelected(index: Int) {
        val context = getApplication<Application>()
        val profile = LocalModelPerformanceProfile.entries[index]
        val modelId = _uiState.value.selectedModelId ?: return
        val model = _uiState.value.installedModels.firstOrNull { it.id == modelId } ?: return
        val catalog = LocalModelCatalogRepository.findById(model.catalogId)
        val defaults = LocalGenerationSettings.defaultsFor(catalog, profile)
        _uiState.value = _uiState.value.copy(
            profileIndex = index,
            temperature = defaults.temperature.toString(),
            topP = defaults.topP.toString(),
            topK = defaults.topK.toString(),
            maxTokens = defaults.maxTokens.toString(),
            repetitionPenalty = defaults.repetitionPenalty.toString(),
            contextSize = defaults.contextSize.toString(),
            seed = defaults.seed.toString(),
        )
    }

    fun updateField(field: String, value: String) {
        _uiState.value = when (field) {
            "temperature" -> _uiState.value.copy(temperature = value)
            "topP" -> _uiState.value.copy(topP = value)
            "topK" -> _uiState.value.copy(topK = value)
            "maxTokens" -> _uiState.value.copy(maxTokens = value)
            "repetitionPenalty" -> _uiState.value.copy(repetitionPenalty = value)
            "contextSize" -> _uiState.value.copy(contextSize = value)
            "seed" -> _uiState.value.copy(seed = value)
            "systemPrompt" -> _uiState.value.copy(systemPrompt = value)
            "cpuThreads" -> _uiState.value.copy(cpuThreads = value)
            "gpuLayers" -> _uiState.value.copy(gpuLayers = value)
            "hfToken" -> _uiState.value.copy(hfToken = value)
            else -> _uiState.value
        }
    }

    fun onComputeBackendSelected(index: Int) {
        _uiState.value = _uiState.value.copy(computeBackendIndex = index)
    }

    fun onTemplateSelected(index: Int) {
        _uiState.value = _uiState.value.copy(templateOverrideIndex = index)
    }

    fun toggleExperimentalJson() {
        _uiState.value = _uiState.value.copy(experimentalJson = !_uiState.value.experimentalJson)
    }

    fun importModel(uri: Uri) {
        val context = getApplication<Application>()
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(downloadProgress = "Importing model...")
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val name = guessDisplayName(context, uri)
                    val file = LocalModelStorageRepository.copyUriToManagedModelFile(
                        context = context,
                        uri = uri,
                        preferredName = name,
                    )
                    if (!LocalModelFileUtils.isGgufFile(file)) {
                        file.delete()
                        throw IllegalStateException("Imported file is not GGUF")
                    }
                    LocalModelStorageRepository.registerImportedModel(
                        context = context,
                        displayName = file.nameWithoutExtension,
                        file = file,
                    )
                }
            }
            result.onSuccess {
                _uiState.value = _uiState.value.copy(downloadProgress = "Import complete: ${it.displayName}")
                refreshAll()
            }.onFailure { err ->
                _uiState.value = _uiState.value.copy(downloadProgress = "Import failed: ${err.message}")
            }
        }
    }

    fun downloadStarter() {
        val starter = LocalModelCatalogRepository.curatedModels.firstOrNull { it.id == "qwen2.5-0.5b-instruct-q4" }
        if (starter != null) requestDownload(starter)
    }

    fun requestDownload(entry: LocalModelCatalogEntry) {
        val context = getApplication<Application>()
        if (_uiState.value.isDownloading) return
        if (entry.gatedDownload || entry.sourceUrl.isNullOrBlank()) return

        val assessment = DeviceCapabilityService.assess(
            snapshot = DeviceCapabilityService.snapshot(context),
            entry = entry,
            requireDownloadHeadroom = true,
        )
        if (!assessment.supported) {
            _uiState.value = _uiState.value.copy(downloadProgress = assessment.blockers.joinToString(" "))
            return
        }

        downloadCancelled.set(false)
        _uiState.value = _uiState.value.copy(
            isDownloading = true,
            downloadProgress = "Starting download: ${entry.displayName}",
            downloadPercent = 0,
        )

        downloadJob?.cancel()
        downloadJob = viewModelScope.launch {
            runCatching {
                downloadManager.downloadCatalogModel(
                    context = context,
                    entry = entry,
                    cancelled = downloadCancelled,
                    onProgress = { p -> onDownloadProgress(p) },
                )
            }.onSuccess {
                _uiState.value = _uiState.value.copy(
                    isDownloading = false,
                    downloadProgress = "Download complete: ${it.displayName}",
                    downloadPercent = 100,
                )
                refreshAll()
            }.onFailure { err ->
                val cancelled = err is CancellationException || downloadCancelled.get()
                _uiState.value = _uiState.value.copy(
                    isDownloading = false,
                    downloadProgress = if (cancelled) "Download cancelled" else "Download failed: ${err.message}",
                    downloadPercent = 0,
                )
            }
        }
    }

    fun cancelDownload() {
        downloadCancelled.set(true)
        downloadJob?.cancel()
        _uiState.value = _uiState.value.copy(downloadProgress = "Cancelling download...")
    }

    fun unloadModel() {
        val context = getApplication<Application>()
        viewModelScope.launch {
            runCatching { LocalChatSessionManager.unload() }
            _uiState.value = _uiState.value.copy(warmupResult = "Local model unloaded")
            refreshAll()
        }
    }

    fun removeModel() {
        val context = getApplication<Application>()
        val modelId = _uiState.value.selectedModelId ?: return
        val model = _uiState.value.installedModels.firstOrNull { it.id == modelId } ?: return
        LocalModelStorageRepository.removeInstalled(context, model.id)
        LocalModelSettingsRepository.clearForModel(context, model.id)
        viewModelScope.launch { runCatching { LocalChatSessionManager.unload() } }
        refreshAll()
    }

    fun saveSettings() {
        val context = getApplication<Application>()
        val state = _uiState.value
        val modelId = state.selectedModelId
        if (modelId == null) {
            LocalModelsPrefs.setHuggingFaceToken(context, state.hfToken)
            _uiState.value = _uiState.value.copy(downloadProgress = "Saved token. Install a model to save generation settings.")
            return
        }

        val existing = LocalModelSettingsRepository.getForModel(context, modelId)
        val profile = LocalModelPerformanceProfile.entries.getOrElse(state.profileIndex) { LocalModelPerformanceProfile.BALANCED }
        val backend = LocalComputeBackend.entries.getOrElse(state.computeBackendIndex) { LocalComputeBackend.CPU }
        val templateId = PromptTemplateRegistry.templates.getOrNull(state.templateOverrideIndex - 1)?.id

        val settings = LocalGenerationSettings(
            profile = profile,
            temperature = state.temperature.toDoubleOrNull()?.coerceIn(0.0, 2.0) ?: existing.temperature,
            topP = state.topP.toDoubleOrNull()?.coerceIn(0.0, 1.0) ?: existing.topP,
            topK = state.topK.toIntOrNull()?.coerceIn(0, 200) ?: existing.topK,
            maxTokens = state.maxTokens.toIntOrNull()?.coerceIn(32, 2048) ?: existing.maxTokens,
            repetitionPenalty = state.repetitionPenalty.toDoubleOrNull()?.coerceIn(0.8, 2.0) ?: existing.repetitionPenalty,
            contextSize = state.contextSize.toIntOrNull()?.coerceIn(1024, 8192) ?: existing.contextSize,
            seed = state.seed.toIntOrNull() ?: existing.seed,
            systemPromptOverride = state.systemPrompt.trim(),
            templateOverrideId = templateId,
            experimentalStructuredJson = state.experimentalJson,
            computeBackend = backend,
            cpuThreads = state.cpuThreads.toIntOrNull()?.coerceIn(1, 16) ?: existing.cpuThreads,
            gpuLayers = state.gpuLayers.toIntOrNull()?.coerceIn(-1, 999) ?: existing.gpuLayers,
        )

        LocalModelSettingsRepository.saveForModel(context, modelId, settings)
        LocalModelsPrefs.setHuggingFaceToken(context, state.hfToken)
        _uiState.value = _uiState.value.copy(downloadProgress = "Local model settings saved")
    }

    fun runWarmup() {
        val context = getApplication<Application>()
        val modelId = _uiState.value.selectedModelId ?: return
        val model = _uiState.value.installedModels.firstOrNull { it.id == modelId } ?: return
        val settings = LocalModelSettingsRepository.getForModel(context, model.id)
        val entry = LocalModelCatalogRepository.findById(model.catalogId)

        _uiState.value = _uiState.value.copy(warmupResult = "Running warm-up...")
        warmupJob?.cancel()
        warmupJob = viewModelScope.launch {
            val outcome = runCatching {
                withContext(Dispatchers.IO) {
                    val loadDetails = LocalChatSessionManager.ensureModelLoaded(
                        context = context,
                        model = model,
                        catalogEntry = entry,
                        settings = settings,
                    )
                    val warmup = LocalChatSessionManager.runWarmupProbe(settings = settings, onToken = {})
                    loadDetails to warmup
                }
            }
            outcome.fold(
                onSuccess = { (loadDetails, result) ->
                    val genTps = (result.generatedTokens * 1000.0 / result.elapsedMs).coerceAtLeast(0.1)
                    val totalTps = (result.totalTokens * 1000.0 / result.elapsedMs).coerceAtLeast(0.1)
                    val backend = if (result.backend == LocalComputeBackend.GPU_EXPERIMENTAL) "GPU" else "CPU"
                    val gpuSuffix = if (result.backend == LocalComputeBackend.GPU_EXPERIMENTAL) {
                        val layers = if (loadDetails.activeGpuLayers == -1) "auto(-1)" else loadDetails.activeGpuLayers.toString()
                        ", n_gpu_layers=$layers"
                    } else ""
                    val fallbackSuffix = if (loadDetails.fallbackReason.isNullOrBlank() || result.backend == LocalComputeBackend.GPU_EXPERIMENTAL) "" else " | fallback: CPU"
                    val msg = "Warm-up complete: ${String.format("%.2f", genTps)} gen tok/s, ${String.format("%.2f", totalTps)} total tok/s, ${result.elapsedMs}ms, backend=$backend$gpuSuffix$fallbackSuffix"
                    _uiState.value = _uiState.value.copy(warmupResult = msg)
                    LocalModelsPrefs.setLastBenchmark(context, msg)
                },
                onFailure = { err ->
                    if (err is CancellationException) {
                        _uiState.value = _uiState.value.copy(warmupResult = "Warm-up cancelled")
                    } else {
                        _uiState.value = _uiState.value.copy(warmupResult = "Warm-up failed: ${err.message ?: "unknown error"}")
                    }
                },
            )
        }
    }

    private fun onDownloadProgress(progress: LocalModelDownloadProgress) {
        val done = humanSize(progress.downloadedBytes)
        val total = if (progress.totalBytes > 0) humanSize(progress.totalBytes) else "?"
        _uiState.value = _uiState.value.copy(
            downloadProgress = "Downloading ${progress.modelId}: ${progress.percent}% ($done / $total)",
            downloadPercent = progress.percent,
        )
    }

    private fun catalogStatusText(entry: LocalModelCatalogEntry, installed: InstalledLocalModel?): String {
        if (installed != null) return "Status: ready"
        if (entry.gatedDownload) return "Status: gated (manual import)"
        if (entry.sourceUrl.isNullOrBlank()) return "Status: manual import recommended"
        return "Status: not downloaded"
    }

    private fun guessDisplayName(context: android.content.Context, uri: Uri): String {
        val defaultName = "imported-model.gguf"
        if (uri.scheme == "file") return File(uri.path.orEmpty()).name.ifBlank { defaultName }
        val cursor = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val idx = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) {
                    val name = it.getString(idx)
                    if (!name.isNullOrBlank()) return name
                }
            }
        }
        return defaultName
    }

    companion object {
        fun humanSize(bytes: Long): String {
            if (bytes <= 0L) return "0 B"
            val kb = 1024.0
            val mb = kb * 1024.0
            val gb = mb * 1024.0
            val b = bytes.toDouble()
            return when {
                b >= gb -> String.format("%.2f GB", b / gb)
                b >= mb -> String.format("%.1f MB", b / mb)
                b >= kb -> String.format("%.1f KB", b / kb)
                else -> "$bytes B"
            }
        }
    }
}
