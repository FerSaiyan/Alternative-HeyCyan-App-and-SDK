package com.fersaiyan.cyanbridge.agent

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.fersaiyan.cyanbridge.localmodels.catalog.LocalModelCatalogEntry
import com.fersaiyan.cyanbridge.localmodels.catalog.LocalModelCatalogRepository
import com.fersaiyan.cyanbridge.localmodels.device.DeviceCapabilityService
import com.fersaiyan.cyanbridge.localmodels.device.DeviceSnapshot
import com.fersaiyan.cyanbridge.localmodels.download.ModelDownloadForegroundService
import com.fersaiyan.cyanbridge.localmodels.engine.LiteRtLocalInferenceEngine
import com.fersaiyan.cyanbridge.localmodels.engine.NpuCatalogVariants
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
import com.fersaiyan.cyanbridge.localmodels.storage.LocalModelProjectorStore
import com.fersaiyan.cyanbridge.localmodels.storage.LocalModelStorageRepository
import com.fersaiyan.cyanbridge.localmodels.templates.PromptTemplateRegistry
import com.fersaiyan.cyanbridge.plugins.PluginVoicePermissions
import com.fersaiyan.cyanbridge.shared.format.formatFixed
import com.fersaiyan.cyanbridge.shared.localmodels.DownloadCheck
import com.fersaiyan.cyanbridge.shared.localmodels.InstalledModelUiItem
import com.fersaiyan.cyanbridge.shared.localmodels.LocalModelCatalogUiItem
import com.fersaiyan.cyanbridge.shared.localmodels.LocalModelDownloadUiState
import com.fersaiyan.cyanbridge.shared.localmodels.LocalModelGenerationUiState
import com.fersaiyan.cyanbridge.shared.localmodels.LocalModelsOverview
import com.fersaiyan.cyanbridge.shared.localmodels.LocalModelsPlatform
import com.fersaiyan.cyanbridge.shared.localmodels.LocalModelsSection
import com.fersaiyan.cyanbridge.shared.localmodels.ModelInfo
import com.fersaiyan.cyanbridge.shared.localmodels.Refusal
import com.fersaiyan.cyanbridge.shared.localmodels.RemoteInferenceUiState
import com.fersaiyan.cyanbridge.shared.localmodels.StudioBridgeUiState
import com.fersaiyan.cyanbridge.shared.prefs.AgentPrefs
import com.fersaiyan.cyanbridge.shared.prefs.AppPrefs
import com.fersaiyan.cyanbridge.shared.prefs.get
import com.fersaiyan.cyanbridge.shared.prefs.set
import com.fersaiyan.cyanbridge.ui.MyApplication
import java.io.File
import java.util.Locale

/** Local model storage, device checks, LiteRT/llama.cpp settings and remote servers on Android. */
class AndroidLocalModelsPlatform(context: Context) : LocalModelsPlatform {
    private val context = context.applicationContext
    private var installedModels: List<InstalledLocalModel> = emptyList()
    private var deviceSnapshot: DeviceSnapshot? = null

    private val snapshot: DeviceSnapshot
        get() = deviceSnapshot ?: DeviceCapabilityService.snapshot(context).also { deviceSnapshot = it }

    private fun selectedModel(): InstalledLocalModel? = LocalModelStorageRepository.resolveSelectedModel(context)

    override fun reload() {
        LocalModelStorageRepository.cleanupMissingModels(context)
        installedModels = LocalModelStorageRepository.listInstalled(context)
        deviceSnapshot = DeviceCapabilityService.snapshot(context)
    }

    override fun overview(): LocalModelsOverview {
        val selected = selectedModel()
        val ramGb = DeviceCapabilityService.totalRamGb(snapshot.totalRamBytes)
        val freeGb = snapshot.freeStorageBytes / GIB
        return LocalModelsOverview(
            engineStatus = selected?.let { "Ready for ${it.displayName}" } ?: "Runtimes available: llama.cpp + LiteRT",
            deviceSummary = "${snapshot.primaryAbi} • ${formatFixed(ramGb, 1)} GB RAM • ${formatFixed(freeGb, 2)} GB free",
            selectedModelStatus = selected?.let {
                "${if (File(it.absolutePath).exists()) "Ready" else "Missing file"} • ${humanSize(it.sizeBytes)}"
            } ?: "No model selected",
            emptyStateMessage = if (installedModels.isEmpty()) {
                "No local model installed. Gemma 4 E2B is the recommended multimodal starter."
            } else "",
            installedModels = installedModels.map { InstalledModelUiItem(it.id, "${it.displayName} (${humanSize(it.sizeBytes)})") },
            selectedModelId = selected?.id,
            selectedModelName = selected?.displayName,
        )
    }

    override fun catalog(huggingFaceToken: String): List<LocalModelCatalogUiItem> {
        val installedByCatalogId = installedModels.associateBy { it.catalogId }
        return LocalModelCatalogRepository.curatedModels.map { entry ->
            val installed = installedByCatalogId[entry.id]
            // Per-SoC NPU entries show this phone's file (size, source) or why there is none.
            val resolved = NpuCatalogVariants.forDevice(context, entry).entry
            val shown = resolved ?: entry
            LocalModelCatalogUiItem(
                id = entry.id,
                title = entry.displayName,
                details = "${entry.quantization} • ${humanSize(shown.sizeBytes)} • ${entry.shortDescription}",
                status = statusText(entry, installed),
                downloadLabel = when {
                    ModelDownloadForegroundService.isDownloading && ModelDownloadForegroundService.downloadingModelId == entry.id -> "Downloading…"
                    installed != null -> "Installed"
                    entry.comingSoon -> "Coming soon"
                    resolved == null -> "Unavailable"
                    shown.sourceUrl.isNullOrBlank() -> "Manual import"
                    !assess(shown).supported -> "Unavailable"
                    entry.gatedDownload -> "Download (token)"
                    else -> "Download"
                },
                canDownload = canDownload(entry, installed, huggingFaceToken),
            )
        }
    }

    override fun selectModel(id: String) = LocalModelStorageRepository.setSelectedModelId(context, id)

    override fun warmupResult(): String = AgentPrefs.LocalModels.lastBenchmark[context]

    private fun sectionPref(section: LocalModelsSection) =
        AppPrefs.LocalModelsSections.expanded[section.name.lowercase(Locale.US)]

    override fun isSectionExpanded(section: LocalModelsSection): Boolean = sectionPref(section)[context]

    override fun setSectionExpanded(section: LocalModelsSection, expanded: Boolean) {
        sectionPref(section)[context] = expanded
    }

    override fun loadGeneration(): LocalModelGenerationUiState {
        val token = LocalModelSettingsRepository.getHuggingFaceToken(context)
        val model = selectedModel() ?: return draftOf(LocalGenerationSettings.defaultsFor(null), LocalMtpMode.AUTO, token, templateIndex = 0)
        val settings = LocalModelSettingsRepository.getForModel(context, model.id)
        val templateIndex = PromptTemplateRegistry.templates.indexOfFirst { it.id == settings.templateOverrideId }
            .let { if (it >= 0) it + 1 else 0 }
        return draftOf(settings, LocalMtpSettingsRepository.getMode(context, model.id), token, templateIndex)
    }

    private fun draftOf(settings: LocalGenerationSettings, mtpMode: LocalMtpMode, token: String, templateIndex: Int) =
        LocalModelGenerationUiState(
            runtimeIndex = settings.modelRuntime.ordinal,
            computeBackendIndex = settings.computeBackend.ordinal,
            mtpIndex = mtpMode.ordinal,
            cpuThreads = settings.cpuThreads.toString(),
            gpuLayers = settings.gpuLayers.toString(),
            temperature = settings.temperature.toString(),
            topP = settings.topP.toString(),
            topK = settings.topK.toString(),
            maxTokens = settings.maxTokens.toString(),
            repetitionPenalty = settings.repetitionPenalty.toString(),
            contextSize = settings.contextSize.toString(),
            seed = settings.seed.toString(),
            templateIndex = templateIndex,
            experimentalStructuredJson = settings.experimentalStructuredJson,
            systemPrompt = settings.systemPromptOverride,
            huggingFaceToken = token,
        )

    private val LocalModelGenerationUiState.runtime get() = LocalModelRuntime.entries.getOrElse(runtimeIndex) { LocalModelRuntime.LLAMA_CPP }
    private val LocalModelGenerationUiState.backend get() = LocalComputeBackend.entries.getOrElse(computeBackendIndex) { LocalComputeBackend.GPU }
    private val LocalModelGenerationUiState.mtpMode get() = LocalMtpMode.entries.getOrElse(mtpIndex) { LocalMtpMode.AUTO }

    override fun describeGeneration(draft: LocalModelGenerationUiState): LocalModelGenerationUiState {
        val selected = selectedModel()
        val mtpSupport = mtpSupportFor(selected, draft.runtime)
        val templateOptions = listOf("Auto (catalog default)") + PromptTemplateRegistry.templates.map { "${it.label} (${it.id})" }
        return draft.copy(
            computeBackendOptions = LocalComputeBackend.entries.map { it.label },
            computeBackendIndex = draft.backend.ordinal.coerceAtLeast(0),
            computeBackendNote = backendNote(draft.backend, draft.runtime),
            mtpOptions = LocalMtpMode.entries.map { it.label },
            mtpIndex = draft.mtpMode.ordinal,
            mtpSupported = mtpSupport,
            mtpStatus = mtpStatusFor(selected, draft, mtpSupport),
            runtimeOptions = LocalModelRuntime.entries.map { it.label },
            runtimeIndex = draft.runtime.ordinal,
            runtimeNote = runtimeNote(draft.runtime),
            gpuLayersEnabled = draft.backend != LocalComputeBackend.CPU,
            templateOptions = templateOptions,
            templateIndex = draft.templateIndex.coerceIn(0, templateOptions.lastIndex.coerceAtLeast(0)),
        )
    }

    override fun saveGeneration(draft: LocalModelGenerationUiState): Boolean {
        val model = selectedModel()
        LocalModelSettingsRepository.setHuggingFaceToken(context, draft.huggingFaceToken)
        if (model == null) return false

        val existing = LocalModelSettingsRepository.getForModel(context, model.id)
        val templateId = if (draft.templateIndex <= 0) null else PromptTemplateRegistry.templates.getOrNull(draft.templateIndex - 1)?.id
        val settings = existing.copy(
            temperature = draft.temperature.toDoubleOrNull()?.coerceIn(0.0, 2.0) ?: existing.temperature,
            topP = draft.topP.toDoubleOrNull()?.coerceIn(0.0, 1.0) ?: existing.topP,
            topK = draft.topK.toIntOrNull()?.coerceIn(0, 200) ?: existing.topK,
            maxTokens = draft.maxTokens.toIntOrNull()
                ?.coerceIn(LocalGenerationSettings.MIN_MAX_TOKENS, LocalGenerationSettings.MAX_MAX_TOKENS)
                ?: existing.maxTokens,
            repetitionPenalty = draft.repetitionPenalty.toDoubleOrNull()?.coerceIn(0.8, 2.0) ?: existing.repetitionPenalty,
            contextSize = draft.contextSize.toIntOrNull()
                ?.coerceIn(LocalGenerationSettings.MIN_CONTEXT_SIZE, LocalGenerationSettings.MAX_CONTEXT_SIZE)
                ?: existing.contextSize,
            seed = draft.seed.toIntOrNull() ?: existing.seed,
            systemPromptOverride = draft.systemPrompt.trim(),
            templateOverrideId = templateId,
            experimentalStructuredJson = draft.experimentalStructuredJson,
            computeBackend = draft.backend,
            cpuThreads = draft.cpuThreads.toIntOrNull()?.coerceIn(1, 16) ?: existing.cpuThreads,
            gpuLayers = draft.gpuLayers.toIntOrNull()?.coerceIn(-1, 999) ?: existing.gpuLayers,
            modelRuntime = draft.runtime,
        )
        LocalModelSettingsRepository.saveForModel(context, model.id, settings)
        LocalMtpSettingsRepository.setMode(context, model.id, draft.mtpMode)
        return true
    }

    override fun loadRemote() = RemoteInferenceUiState(
        enabled = RemoteOpenAiPrefs.isEnabled(context),
        baseUrl = RemoteOpenAiPrefs.getBaseUrl(context),
        modelName = RemoteOpenAiPrefs.getModel(context),
        apiKey = RemoteOpenAiPrefs.getApiKey(context),
        status = if (RemoteOpenAiPrefs.isActive(context)) {
            "Active: ${RemoteOpenAiPrefs.getModel(context)} @ ${RemoteOpenAiPrefs.getBaseUrl(context)}"
        } else "",
    )

    override fun loadStudio() = StudioBridgeUiState(
        enabled = RemoteOpenAiPrefs.isBridgeEnabled(context),
        apiKey = RemoteOpenAiPrefs.getApiKey(context),
        status = when {
            RemoteOpenAiPrefs.isBridgeConfigured(context) -> "Bridge configured for voice approvals."
            RemoteOpenAiPrefs.isBridgeEnabled(context) -> "Bridge enabled but server URL, model, or API key is missing."
            else -> ""
        },
    )

    override fun saveRemote(draft: RemoteInferenceUiState): Refusal? {
        val url = draft.baseUrl.trim()
        val model = draft.modelName.trim()
        val key = draft.apiKey.trim()
        if (draft.enabled && (url.isBlank() || model.isBlank())) return Refusal("Base URL and model name are required.")
        if (key.isNotBlank() && !RemoteOpenAiPrefs.isCredentialTransportAllowed(url)) {
            return Refusal("API keys require HTTPS, a private LAN address, or a Tailscale address.", long = true)
        }
        RemoteOpenAiPrefs.setBaseUrl(context, url)
        RemoteOpenAiPrefs.setModel(context, model)
        RemoteOpenAiPrefs.setApiKey(context, key)
        RemoteOpenAiPrefs.setEnabled(context, draft.enabled)
        return null
    }

    override suspend fun checkRemoteHealth(): String = RemoteOpenAiClient.healthCheck(context)

    override fun studioBlocker(apiKey: String): Refusal? {
        val baseUrl = RemoteOpenAiPrefs.getBaseUrl(context)
        return when {
            baseUrl.isBlank() || RemoteOpenAiPrefs.getModel(context).isBlank() ->
                Refusal("Configure the Remote model server first.", long = true)
            apiKey.isBlank() -> Refusal("Studio API key is required.")
            !RemoteOpenAiPrefs.isCredentialTransportAllowed(baseUrl) ->
                Refusal("Studio credentials require HTTPS, LAN, or Tailscale transport.", long = true)
            !android.speech.SpeechRecognizer.isRecognitionAvailable(context) ->
                Refusal("No speech recognizer is available on this device.", long = true)
            else -> null
        }
    }

    override fun studioPermissionsGranted() = PluginVoicePermissions.hasRequiredPermissions(context)

    override fun applyStudio(enabled: Boolean, apiKey: String): Boolean {
        if (enabled) RemoteOpenAiPrefs.setApiKey(context, apiKey)
        RemoteOpenAiPrefs.setBridgeEnabled(context, enabled)
        val app = context as? MyApplication
        if (!enabled) app?.stopStudioBridge()
        return enabled && app?.startStudioBridge() == true
    }

    override fun downloadInProgress(): LocalModelDownloadUiState? {
        if (!ModelDownloadForegroundService.isDownloading) return null
        return LocalModelDownloadUiState(
            isInFlight = true,
            message = ModelDownloadForegroundService.lastStatusMessage ?: "Downloading…",
            progressPercent = ModelDownloadForegroundService.lastPercent,
        )
    }

    override fun checkDownload(catalogId: String, huggingFaceToken: String): DownloadCheck? {
        val entry = LocalModelCatalogRepository.findById(catalogId) ?: return null
        if (ModelDownloadForegroundService.isDownloading) return DownloadCheck.Blocked(Refusal("A model download is already in progress"))
        val assessment = assess(entry)
        if (!assessment.supported) return DownloadCheck.Blocked(Refusal(assessment.blockers.joinToString(" "), long = true))
        val token = huggingFaceToken.trim().ifBlank { null }
        if (entry.gatedDownload && token == null) {
            return DownloadCheck.Blocked(Refusal("Add a Hugging Face token after accepting the model terms.", long = true))
        }
        return DownloadCheck.Allowed(entry.displayName, token, assessment.warnings)
    }

    override fun startDownload(catalogId: String, token: String?) =
        ModelDownloadForegroundService.startDownload(context, catalogId, token)

    override fun cancelDownload() = ModelDownloadForegroundService.cancelDownload(context)

    override fun importModel(source: Any): String {
        val uri = source as Uri
        val file = LocalModelStorageRepository.copyUriToManagedModelFile(context = context, uri = uri, preferredName = guessDisplayName(uri))
        if (!LocalModelFileUtils.isSupportedModelFile(file)) {
            file.delete()
            error("Imported file must be GGUF or LiteRT (.litertlm/.task)")
        }
        if (file.name.contains("mmproj", ignoreCase = true)) {
            val model = selectedModel()
            if (model == null || !model.fileName.endsWith(".gguf", ignoreCase = true)) {
                file.delete()
                error("Select the matching GGUF model before importing its mmproj projector")
            }
            LocalModelProjectorStore.attach(context, model, file)
            return "Projector attached to ${model.displayName}"
        }
        return "Import complete: ${LocalModelStorageRepository.registerImportedModel(context = context, displayName = file.nameWithoutExtension, file = file).displayName}"
    }

    private fun guessDisplayName(uri: Uri): String {
        if (uri.scheme == "file") return File(uri.path.orEmpty()).name.ifBlank { "imported-model.gguf" }
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) return cursor.getString(index).orEmpty().ifBlank { "imported-model.gguf" }
            }
        }
        return "imported-model.gguf"
    }

    override suspend fun unloadModel() = LocalChatSessionManager.unload()

    override fun removeModel(id: String) {
        LocalModelStorageRepository.removeInstalled(context, id)
        LocalModelSettingsRepository.clearForModel(context, id)
        LocalMtpSettingsRepository.clearForModel(context, id)
    }

    override fun selectedModelInfo(runtimeIndex: Int): ModelInfo? {
        val model = selectedModel() ?: return null
        val entry = LocalModelCatalogRepository.findById(model.catalogId)
        val runtime = LocalModelRuntime.entries.getOrElse(runtimeIndex) { LocalModelRuntime.LLAMA_CPP }
        return ModelInfo(
            title = model.displayName,
            message = "Family: ${entry?.family ?: "custom"}\n" +
                "Runtime: ${runtime.label}\n" +
                "Quantization: ${model.quantization ?: "unknown"}\n" +
                "Size: ${humanSize(model.sizeBytes)}\n" +
                "Projector: ${LocalModelProjectorStore.get(context, model) ?: "none (import the matching mmproj GGUF for media)"}\n" +
                "Location: ${model.absolutePath}",
        )
    }

    override fun catalogInfo(catalogId: String): ModelInfo? {
        val entry = LocalModelCatalogRepository.findById(catalogId) ?: return null
        return ModelInfo(
            title = entry.displayName,
            message = "${entry.shortDescription}\n\nRuntime: ${entry.engine}\nFormat: ${entry.format}\n" +
                "Quantization: ${entry.quantization}\nSize: ${humanSize(entry.sizeBytes)}\n" +
                "RAM minimum: ${entry.minRamGb} GB\nLicense: ${entry.licenseTermsNote}",
            sourceUrl = entry.sourcePageUrl ?: entry.sourceUrl,
        )
    }

    private fun mtpSupportFor(model: InstalledLocalModel?, runtime: LocalModelRuntime): Boolean? {
        if (model == null || runtime != LocalModelRuntime.LITERT) return null
        val file = File(model.absolutePath)
        if (!file.exists()) return false
        return LiteRtLocalInferenceEngine.supportsSpeculativeDecoding(file.absolutePath)
    }

    private fun mtpStatusFor(model: InstalledLocalModel?, draft: LocalModelGenerationUiState, supported: Boolean?): String {
        if (model == null) return "Select a LiteRT-LM model to inspect MTP support."
        if (draft.runtime != LocalModelRuntime.LITERT) return "MTP is available only for compatible LiteRT-LM packages."
        if (supported != true) return "Not supported by this model package."
        val file = File(model.absolutePath)
        val record = LocalMtpSettingsRepository.getBenchmark(
            context = context,
            modelId = model.id,
            backend = draft.backend,
            modelSignature = LocalMtpSettingsRepository.modelSignature(file.absolutePath, file.length(), file.lastModified()),
        )
        return when {
            draft.mtpMode == LocalMtpMode.ON -> "Supported • forced on."
            draft.mtpMode == LocalMtpMode.OFF -> "Supported • forced off."
            record == null -> "Supported • Automatic uses MTP until this device/backend is benchmarked."
            record.mtpOnFailure != null || record.mtpOnOutputTokensPerSecond == null ->
                "Supported • Automatic keeps MTP off because the MTP-on test did not finish."
            record.recommendMtp ->
                "Supported • Automatic recommends MTP (${formatFixed(record.mtpOnOutputTokensPerSecond, 2)} vs ${formatFixed(record.mtpOffOutputTokensPerSecond, 2)} tok/s)."
            else ->
                "Supported • Automatic keeps MTP off (${formatFixed(record.mtpOnOutputTokensPerSecond, 2)} vs ${formatFixed(record.mtpOffOutputTokensPerSecond, 2)} tok/s)."
        }
    }

    private fun assess(entry: LocalModelCatalogEntry) = DeviceCapabilityService.assess(
        snapshot = snapshot,
        entry = entry,
        requireDownloadHeadroom = !entry.sourceUrl.isNullOrBlank(),
    )

    private fun canDownload(catalogEntry: LocalModelCatalogEntry, installed: InstalledLocalModel?, huggingFaceToken: String): Boolean {
        val entry = NpuCatalogVariants.forDevice(context, catalogEntry).entry ?: return false
        if (installed != null || !entry.enabled || entry.comingSoon || entry.sourceUrl.isNullOrBlank()) return false
        if (ModelDownloadForegroundService.isDownloading) return false
        if (entry.gatedDownload && huggingFaceToken.isBlank()) return false
        return assess(entry).supported
    }

    private fun statusText(catalogEntry: LocalModelCatalogEntry, installed: InstalledLocalModel?): String {
        if (installed != null) return "Ready"
        if (catalogEntry.comingSoon) return "Coming soon"
        val resolution = NpuCatalogVariants.forDevice(context, catalogEntry)
        resolution.reason?.let { return it }
        val assessment = assess(resolution.entry ?: catalogEntry)
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

    companion object {
        private const val KIB = 1024.0
        private const val MIB = KIB * 1024.0
        private const val GIB = MIB * 1024.0

        fun humanSize(bytes: Long): String {
            val b = bytes.toDouble()
            return when {
                b >= GIB -> "${formatFixed(b / GIB, 2)} GB"
                b >= MIB -> "${formatFixed(b / MIB, 1)} MB"
                b >= KIB -> "${formatFixed(b / KIB, 1)} KB"
                else -> "$bytes B"
            }
        }
    }
}
