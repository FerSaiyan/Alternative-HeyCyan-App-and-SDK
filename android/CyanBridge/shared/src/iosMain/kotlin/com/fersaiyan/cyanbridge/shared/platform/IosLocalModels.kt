package com.fersaiyan.cyanbridge.shared.platform

import com.fersaiyan.cyanbridge.shared.settings.LocalizedPromptDefaults
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.Serializable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.fersaiyan.cyanbridge.localmodels.catalog.LocalModelCatalogEntry
import com.fersaiyan.cyanbridge.shared.ai.ChatMessage
import com.fersaiyan.cyanbridge.shared.localmodels.InstalledModelUiItem
import com.fersaiyan.cyanbridge.shared.localmodels.LocalModelCatalogSearchUiState
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
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSize
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSNumber
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionDownloadTask
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.setValue
import platform.Foundation.NSUserDomainMask
import platform.Foundation.downloadTaskWithRequest
import platform.UIKit.UIAlertAction
import platform.UIKit.UIAlertActionStyleCancel
import platform.UIKit.UIAlertActionStyleDestructive
import platform.UIKit.UIAlertController
import platform.UIKit.UIAlertControllerStyleAlert
import platform.UIKit.UIDevice
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UniformTypeIdentifiers.UTTypeData
import platform.darwin.NSObject
import kotlin.time.TimeSource

/**
 * On-device models for the "Local" AI provider (Android: LocalModelsConfigureActivity +
 * LocalModelStorageRepository). Kotlin keeps the files, catalog and settings; the Swift
 * [LocalModelBridge] runs them with llama.cpp (.gguf) or LiteRT-LM (.litertlm).
 */
@OptIn(ExperimentalForeignApi::class)
object IosLocalModels {
    /** Online search results by catalog id, so Download / Info can find them. */
    private val searchResults = mutableMapOf<String, LocalModelCatalogEntry>()
    private var searchJob: Job? = null
    private var downloadingId: String? = null

    private const val KEY_SELECTED = "selected_model"
    private const val KEY_USE_GPU = "use_gpu"
    private const val KEY_SYSTEM_PROMPT = "system_prompt"
    private const val KEYCHAIN_HF_TOKEN = "hugging_face_token"
    private const val KEY_GENERATION = "generation_settings"
    /** The first iOS default asked for brief answers, which cut lists short; it migrates to the default. */
    private const val LEGACY_BRIEF_SYSTEM_PROMPT =
        "You are a helpful assistant on smart glasses. Answer briefly, in the user's language."

    /** Android's default system prompt in the app language. */
    private val defaultSystemPrompt: String get() = LocalizedPromptDefaults.systemPrompt(platformLanguageTag())
    private const val TAG = "IosLocalModels"

    private val preferences = createPlatformPreferences("cyanbridge_local_models")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val runMutex = Mutex()
    private var loadedPath: String? = null
    private var loadedOnGpu = false
    private var loadedContextSize = 0
    private var downloadTask: NSURLSessionDownloadTask? = null
    private var downloadJob: Job? = null
    private var pickerDelegate: ModelPickerDelegate? = null

    var isOpen by mutableStateOf(false)
        private set
    var uiState by mutableStateOf(LocalModelsConfigureUiState())
        private set

    private val bridge: LocalModelBridge? get() = LocalModelRegistry.bridge

    // ── Storage ──

    private val modelsDirectory: String by lazy {
        val base = NSSearchPathForDirectoriesInDomains(NSApplicationSupportDirectory, NSUserDomainMask, true)
            .first() as String
        val path = "$base/local-models"
        NSFileManager.defaultManager.createDirectoryAtPath(path, true, null, null)
        // Models are large and re-downloadable: keep them out of iCloud backups.
        NSURL.fileURLWithPath(path).setResourceValue(true, NSURLIsExcludedFromBackupKey, null)
        path
    }

    private fun isModelFile(name: String) =
        name.endsWith(".gguf", ignoreCase = true) || name.endsWith(".litertlm", ignoreCase = true)

    private fun installedFiles(): List<String> =
        NSFileManager.defaultManager.contentsOfDirectoryAtPath(modelsDirectory, null)
            ?.mapNotNull { it as? String }
            ?.filter(::isModelFile)
            ?.sorted()
            .orEmpty()

    private fun pathOf(name: String) = "$modelsDirectory/$name"

    private fun sizeOf(name: String): Long =
        (NSFileManager.defaultManager.attributesOfItemAtPath(pathOf(name), null)?.get(NSFileSize) as? NSNumber)
            ?.longLongValue ?: 0L

    private val selectedName: String?
        get() = preferences.getString(KEY_SELECTED, "").takeIf { it.isNotBlank() && it in installedFiles() }

    private val useGpu: Boolean
        get() = preferences.getBoolean(KEY_USE_GPU, true) && bridge?.gpuAvailable() == true

    /** A stock prompt (any language, or the old brief one) follows the app language; edits are kept. */
    private val systemPrompt: String
        get() = preferences.getString(KEY_SYSTEM_PROMPT, "")
            .takeUnless { LocalizedPromptDefaults.isDefaultSystemPrompt(it) || it.trim() == LEGACY_BRIEF_SYSTEM_PROMPT }
            ?: defaultSystemPrompt

    private val generationSettings: LocalGenerationSettings
        get() = runCatching {
            Json.decodeFromString<StoredGenerationSettings>(preferences.getString(KEY_GENERATION, "{}")).toSettings()
        }.getOrDefault(LocalGenerationSettings())

    private val huggingFaceToken: String
        get() = IosSecurityRegistry.bridge?.keychainGet(KEYCHAIN_HF_TOKEN)?.toKotlinBytes()?.decodeToString().orEmpty()

    private fun saveHuggingFaceToken(value: String) {
        val bridge = IosSecurityRegistry.bridge ?: return
        if (value.isBlank()) bridge.keychainDelete(KEYCHAIN_HF_TOKEN)
        else bridge.keychainSet(KEYCHAIN_HF_TOKEN, value.trim().encodeToByteArray().toNSData())
    }

    private fun findEntry(id: String): LocalModelCatalogEntry? =
        IosModelCatalog.curated.firstOrNull { it.id == id } ?: searchResults[id]

    /** True when chats should run on the selected on-device model. */
    val isActive: Boolean get() = bridge != null && selectedName != null

    // ── Inference ──

    /** Describes a glasses photo with the selected model (LiteRT-LM vision models such as Gemma 4). */
    suspend fun describeImage(image: ByteArray, prompt: String, onToken: (String) -> Unit = {}): String {
        val path = "${NSTemporaryDirectory()}cyanbridge-image-question.jpg"
        check(IosMediaPlatform.writeFile(path, image)) { "Could not prepare the photo" }
        return try {
            chat(listOf(ChatMessage("user", prompt)), imagePath = path, onToken = onToken)
        } finally {
            NSFileManager.defaultManager.removeItemAtPath(path, null)
        }
    }

    suspend fun chat(
        messages: List<ChatMessage>,
        imagePath: String = "",
        onToken: (String) -> Unit = {},
    ): String = runMutex.withLock {
        val runtime = ensureLoaded()
        val messagesJson = buildJsonArray {
            messages.filter { it.content.isNotBlank() }.forEach { message ->
                addJsonObject {
                    put("role", message.role.lowercase())
                    put("content", message.content)
                }
            }
        }.toString()
        val reply = runtime.awaitGenerate(messagesJson, systemPrompt, generationSettings, imagePath, onToken).getOrThrow()
        refreshUi()
        stripReasoning(reply).ifBlank { error("The model returned an empty reply") }
    }

    /** Loads the selected model ahead of time (while the user is still asking). */
    suspend fun preload() {
        if (!isActive) return
        runCatching { runMutex.withLock { ensureLoaded() } }
            .onFailure { PlatformLogger.w(TAG, "Preload failed: ${it.message}") }
    }

    private suspend fun ensureLoaded(): LocalModelBridge {
        val runtime = bridge ?: error("On-device runtimes are not available in this build")
        val name = selectedName ?: error("No on-device model selected")
        val path = pathOf(name)
        val contextSize = generationSettings.contextSize
        if (loadedPath != path || loadedOnGpu != useGpu || loadedContextSize != contextSize) {
            refreshUi("Loading $name…")
            runtime.awaitLoad(path, useGpu, contextSize).getOrElse {
                loadedPath = null
                refreshUi()
                throw it
            }
            loadedPath = path
            loadedOnGpu = useGpu
            loadedContextSize = contextSize
            PlatformLogger.i(TAG, "Loaded $name (gpu=$useGpu, context=$contextSize)")
        }
        return runtime
    }

    /** Qwen3-style models think inside <think> tags before answering. */
    private fun stripReasoning(text: String): String =
        text.replace(Regex("<think>[\\s\\S]*?</think>"), "").substringBefore("<think>").trim()

    /**
     * Launch-time check used with CYANBRIDGE_LOCAL_MODEL_SELFTEST (1 or a model file name):
     * selects that model, or the first installed one when none is chosen, asks one question
     * and logs the outcome.
     */
    fun runSelfTest(modelName: String) {
        scope.launch {
            if (modelName in installedFiles()) preferences.putString(KEY_SELECTED, modelName)
            if (selectedName == null) installedFiles().firstOrNull()?.let { preferences.putString(KEY_SELECTED, it) }
            val name = selectedName ?: return@launch PlatformLogger.w(TAG, "Self-test: no model in $modelsDirectory")
            PlatformLogger.i(TAG, "Self-test: $name (gpu=$useGpu)")
            val started = TimeSource.Monotonic.markNow()
            runCatching { chat(listOf(ChatMessage("user", "What is the capital of France? Answer in one sentence."))) }
                .onSuccess { PlatformLogger.i(TAG, "Self-test reply in ${started.elapsedNow()}: $it") }
                .onFailure { PlatformLogger.e(TAG, "Self-test failed: ${it.message}") }
            // Optional image check: put a JPEG at Documents/selftest.jpg.
            val documents = NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true).first() as String
            val photo = IosMediaPlatform.readFile("$documents/selftest.jpg") ?: return@launch
            val imageStarted = TimeSource.Monotonic.markNow()
            val imagePrompt = (platform.Foundation.NSProcessInfo.processInfo.environment["CYANBRIDGE_LOCAL_MODEL_SELFTEST_PROMPT"] as? String)
                ?.takeIf { it.isNotBlank() } ?: "Describe this image in one sentence."
            runCatching { describeImage(photo, imagePrompt) }
                .onSuccess { PlatformLogger.i(TAG, "Self-test image reply in ${imageStarted.elapsedNow()}: $it") }
                .onFailure { PlatformLogger.e(TAG, "Self-test image failed: ${it.message}") }
        }
    }

    // ── Screen ──

    fun open() {
        uiState = buildState(remote = IosRemoteModelSettings.savedDraft())
        isOpen = true
    }

    /** Filters the curated list as the user types and searches Hugging Face after a short pause. */
    fun onCatalogSearchQueryChange(query: String) {
        searchJob?.cancel()
        val trimmed = query.trim()
        uiState = uiState.copy(
            catalogExpanded = true,
            catalogSearch = LocalModelCatalogSearchUiState(
                query = query,
                isSearching = trimmed.length >= 2,
                status = if (trimmed.isNotEmpty() && trimmed.length < 2) "Type at least 2 letters to search Hugging Face." else "",
            ),
        )
        refreshUi()
        if (trimmed.length < 2) return
        searchJob = scope.launch {
            delay(600)
            val found = runCatching { IosHuggingFaceSearch.search(trimmed, huggingFaceToken) }
            found.getOrNull()?.forEach { searchResults[it.id] = it }
            // Models this iPhone can run first, then by Hugging Face popularity (the API order).
            val results = found.getOrNull().orEmpty().sortedBy { !IosDeviceCapability.assess(it).supported }
            PlatformLogger.i(
                TAG,
                "Search \"$trimmed\": ${found.exceptionOrNull()?.message ?: results.joinToString { "${it.displayName} ${formatSize(it.sizeBytes)}" }}",
            )
            if (uiState.catalogSearch?.query?.trim() != trimmed) return@launch
            uiState = uiState.copy(
                catalogSearch = LocalModelCatalogSearchUiState(
                    query = uiState.catalogSearch?.query ?: query,
                    status = found.exceptionOrNull()?.let { "Search failed: ${it.message}" }
                        ?: if (results.isEmpty()) "No GGUF or LiteRT-LM model found for \"$trimmed\"." else
                            "${results.size} models. Each uses its recommended file (Q4_K_M when available).",
                    results = results.map(::catalogItem),
                ),
            )
        }
    }

    fun handle(action: LocalModelsAction) {
        when (action) {
            LocalModelsAction.Back, LocalModelsAction.DiscardChangesAndBack -> isOpen = false
            LocalModelsAction.Refresh -> refreshUi()
            LocalModelsAction.ImportModel -> importModel()
            is LocalModelsAction.SelectInstalledModel -> {
                preferences.putString(KEY_SELECTED, action.id)
                refreshUi()
            }
            LocalModelsAction.ShowSelectedModelInfo -> showSelectedModelInfo()
            LocalModelsAction.UnloadSelectedModel -> unload("Model unloaded. It loads again on the next question.")
            LocalModelsAction.RemoveSelectedModel -> selectedName?.let(::confirmDelete)
            is LocalModelsAction.DownloadCatalogModel -> download(action.id)
            is LocalModelsAction.ShowCatalogModelInfo -> findEntry(action.id)?.let(::showCatalogInfo)
            LocalModelsAction.CancelDownload -> downloadTask?.cancel()
            LocalModelsAction.RunWarmup -> runWarmup()
            LocalModelsAction.SaveGenerationSettings -> saveGenerationSettings()
            is LocalModelsAction.ToggleSection -> uiState = when (action.section) {
                LocalModelsSection.CATALOG -> uiState.copy(catalogExpanded = !uiState.catalogExpanded)
                LocalModelsSection.REMOTE_SERVER -> uiState.copy(remoteServerExpanded = !uiState.remoteServerExpanded)
                LocalModelsSection.GENERATION_SETTINGS ->
                    uiState.copy(generationSettingsExpanded = !uiState.generationSettingsExpanded)
                else -> uiState
            }
            is LocalModelsAction.SelectOption -> if (action.field == LocalModelOptionField.COMPUTE_BACKEND) {
                preferences.putBoolean(KEY_USE_GPU, action.index == 0 && bridge?.gpuAvailable() == true)
                refreshUi()
            }
            is LocalModelsAction.UpdateText -> when (action.field) {
                LocalModelTextField.SYSTEM_PROMPT -> uiState = uiState.copy(
                    generation = uiState.generation.copy(systemPrompt = action.value),
                    hasUnsavedChanges = true,
                )
                LocalModelTextField.TEMPERATURE -> editGeneration { it.copy(temperature = action.value) }
                LocalModelTextField.TOP_P -> editGeneration { it.copy(topP = action.value) }
                LocalModelTextField.TOP_K -> editGeneration { it.copy(topK = action.value) }
                LocalModelTextField.MAX_TOKENS -> editGeneration { it.copy(maxTokens = action.value) }
                LocalModelTextField.REPETITION_PENALTY -> editGeneration { it.copy(repetitionPenalty = action.value) }
                LocalModelTextField.CONTEXT_SIZE -> editGeneration { it.copy(contextSize = action.value) }
                LocalModelTextField.SEED -> editGeneration { it.copy(seed = action.value) }
                LocalModelTextField.HUGGING_FACE_TOKEN -> {
                    saveHuggingFaceToken(action.value)
                    uiState = uiState.copy(generation = uiState.generation.copy(huggingFaceToken = action.value))
                }
                LocalModelTextField.REMOTE_BASE_URL -> editRemote { it.copy(baseUrl = action.value) }
                LocalModelTextField.REMOTE_MODEL_NAME -> editRemote { it.copy(modelName = action.value) }
                LocalModelTextField.REMOTE_API_KEY -> editRemote { it.copy(apiKey = action.value) }
                else -> Unit
            }
            is LocalModelsAction.SetToggle -> if (action.field == LocalModelToggleField.REMOTE_SERVER_ENABLED) {
                editRemote { it.copy(enabled = action.enabled) }
            }
            LocalModelsAction.TestRemoteServer -> {
                val draft = uiState.remoteServer
                setRemoteStatus("Testing ${draft.baseUrl.trim()}…")
                scope.launch { setRemoteStatus(IosRemoteOpenAiClient.healthCheck(draft.baseUrl, draft.apiKey)) }
            }
            LocalModelsAction.SaveRemoteServer -> {
                val status = IosRemoteModelSettings.save(uiState.remoteServer)
                uiState = buildState(remote = uiState.remoteServer.copy(status = status))
                    .copy(hasUnsavedChanges = false)
            }
            else -> Unit
        }
    }

    private fun editRemote(change: (RemoteInferenceUiState) -> RemoteInferenceUiState) {
        uiState = uiState.copy(remoteServer = change(uiState.remoteServer), hasUnsavedChanges = true)
    }

    private fun setRemoteStatus(status: String) {
        uiState = uiState.copy(remoteServer = uiState.remoteServer.copy(status = status))
    }

    private fun refreshUi(warmup: String? = null) {
        scope.launch {
            val current = uiState
            uiState = buildState(remote = current.remoteServer).copy(
                catalogExpanded = current.catalogExpanded,
                remoteServerExpanded = current.remoteServerExpanded,
                download = current.download,
                hasUnsavedChanges = current.hasUnsavedChanges,
                warmupResult = warmup ?: current.warmupResult,
                catalogSearch = current.catalogSearch?.let { search ->
                    search.copy(results = search.results.mapNotNull { searchResults[it.id] }.map(::catalogItem))
                },
                catalog = filteredCatalog(current.catalogSearch?.query.orEmpty()),
                generationSettingsExpanded = current.generationSettingsExpanded,
                generation = current.generation.copy(
                    computeBackendOptions = backendOptions(),
                    computeBackendIndex = if (useGpu) 0 else backendOptions().lastIndex,
                    computeBackendNote = backendNote(),
                ),
            )
        }
    }

    private fun backendOptions(): List<String> =
        if (bridge?.gpuAvailable() == true) listOf("GPU (Metal)", "CPU") else listOf("CPU")

    private fun backendNote(): String =
        if (bridge?.gpuAvailable() == true) "GPU is faster; switch to CPU if a model fails to load."
        else "The simulator runs models on the CPU only."

    private fun buildState(remote: RemoteInferenceUiState): LocalModelsConfigureUiState {
        val files = installedFiles()
        val selected = selectedName
        val loaded = selected != null && loadedPath == pathOf(selected)
        return LocalModelsConfigureUiState(
            engineStatus = if (bridge != null) "Runtimes: llama.cpp (.gguf) + LiteRT-LM (.litertlm)"
            else "On-device runtimes are not available in this build",
            deviceSummary = "${UIDevice.currentDevice.model} · ${IosDeviceCapability.format(IosDeviceCapability.ramGb, 0)} GB RAM" +
                if (IosDeviceCapability.isSimulator) " (simulated Pro Max)" else "",
            selectedModelStatus = when {
                selected == null -> "Status: no model selected — chats use the relay or remote server."
                IosRemoteModelSettings.isActive -> "Status: $selected is selected, but the remote server is on and takes priority."
                loaded -> "Status: $selected loaded on ${if (loadedOnGpu) "GPU" else "CPU"}. Chats run on this iPhone."
                else -> "Status: $selected selected. It loads on the first question."
            },
            emptyStateMessage = "No model yet. Download a test model below or import a .gguf / .litertlm file from Files.",
            installedModels = files.map { InstalledModelUiItem(it, "$it (${formatSize(sizeOf(it))})") },
            selectedInstalledModelId = selected,
            catalog = filteredCatalog(""),
            catalogSearch = LocalModelCatalogSearchUiState(),
            catalogExpanded = files.isEmpty(),
            remoteServerExpanded = remote.enabled,
            generation = LocalModelGenerationUiState(
                computeBackendOptions = backendOptions(),
                computeBackendIndex = if (useGpu) 0 else backendOptions().lastIndex,
                computeBackendNote = backendNote(),
                systemPrompt = systemPrompt,
                huggingFaceToken = huggingFaceToken,
            ).withSettings(generationSettings),
            remoteServer = remote,
        )
    }

    private fun editGeneration(change: (LocalModelGenerationUiState) -> LocalModelGenerationUiState) {
        uiState = uiState.copy(generation = change(uiState.generation), hasUnsavedChanges = true)
    }

    /** Validates the Assistant behavior + Advanced options drafts like Android's saveGenerationSettings. */
    private fun saveGenerationSettings() {
        val draft = uiState.generation
        val defaults = LocalGenerationSettings()
        val parsed = LocalGenerationSettings(
            maxTokens = draft.maxTokens.trim().toIntOrNull()?.coerceIn(32, 8192) ?: defaults.maxTokens,
            contextSize = draft.contextSize.trim().toIntOrNull()?.coerceIn(1024, 32768) ?: defaults.contextSize,
            temperature = draft.temperature.trim().replace(',', '.').toDoubleOrNull()?.coerceIn(0.0, 2.0) ?: defaults.temperature,
            topK = draft.topK.trim().toIntOrNull()?.coerceIn(1, 200) ?: defaults.topK,
            topP = draft.topP.trim().replace(',', '.').toDoubleOrNull()?.coerceIn(0.05, 1.0) ?: defaults.topP,
            repetitionPenalty = draft.repetitionPenalty.trim().replace(',', '.').toDoubleOrNull()?.coerceIn(0.8, 2.0)
                ?: defaults.repetitionPenalty,
            seed = draft.seed.trim().toIntOrNull() ?: defaults.seed,
        )
        preferences.putString(KEY_SYSTEM_PROMPT, draft.systemPrompt.ifBlank { defaultSystemPrompt })
        preferences.putString(KEY_GENERATION, Json.encodeToString(StoredGenerationSettings.from(parsed)))
        uiState = uiState.copy(
            hasUnsavedChanges = false,
            generation = draft.withSettings(parsed),
            warmupResult = "Settings saved. Context changes apply when the model reloads.",
        )
        PlatformLogger.i(TAG, "Generation settings saved: $parsed")
    }

    private fun LocalModelGenerationUiState.withSettings(settings: LocalGenerationSettings) = copy(
        temperature = settings.temperature.toString(),
        topP = settings.topP.toString(),
        topK = settings.topK.toString(),
        maxTokens = settings.maxTokens.toString(),
        repetitionPenalty = settings.repetitionPenalty.toString(),
        contextSize = settings.contextSize.toString(),
        seed = settings.seed.toString(),
    )

    private fun filteredCatalog(query: String): List<LocalModelCatalogUiItem> =
        IosModelCatalog.curated.filter { query.isBlank() || IosModelCatalog.matches(it, query) }.map(::catalogItem)

    /** Mirrors Android's catalog rows: quantization • size • description, and the device check as status. */
    private fun catalogItem(entry: LocalModelCatalogEntry): LocalModelCatalogUiItem {
        val installed = entry.expectedFilename in installedFiles()
        val assessment = IosDeviceCapability.assess(entry)
        val runtime = if (entry.format == "litertlm") "LiteRT-LM" else "llama.cpp"
        return LocalModelCatalogUiItem(
            id = entry.id,
            title = entry.displayName,
            details = "${entry.quantization} • ${formatSize(entry.sizeBytes)} • $runtime • ${entry.shortDescription}",
            status = when {
                installed -> "Ready"
                !assessment.supported -> assessment.blockers.joinToString(" ")
                entry.gatedDownload && huggingFaceToken.isBlank() ->
                    "Gated: accept the license on Hugging Face and add a token below."
                else -> assessment.warnings.joinToString(" ").ifBlank { "Compatible with this device" }
            },
            downloadLabel = when {
                downloadingId == entry.id -> "Downloading…"
                installed -> "Installed"
                !assessment.supported -> "Download anyway"
                else -> "Download"
            },
            canDownload = !installed && downloadTask == null && entry.sourceUrl != null,
        )
    }

    private fun showCatalogInfo(entry: LocalModelCatalogEntry) {
        val assessment = IosDeviceCapability.assess(entry)
        val message = buildString {
            appendLine(entry.shortDescription)
            appendLine()
            appendLine("File: ${entry.expectedFilename}")
            appendLine("Size: ${formatSize(entry.sizeBytes)} · ${entry.quantization}")
            appendLine("Needs: ${IosDeviceCapability.format(entry.minRamGb, 1)} GB RAM · this iPhone: ${IosDeviceCapability.format(IosDeviceCapability.ramGb, 1)} GB")
            assessment.blockers.forEach { appendLine("⚠️ $it") }
            assessment.warnings.forEach { appendLine("• $it") }
            appendLine(entry.licenseTermsNote)
            append(entry.sourcePageUrl ?: entry.sourceUrl.orEmpty())
        }
        val link = entry.sourcePageUrl ?: entry.sourceUrl.orEmpty()
        IosMediaPlatform.showCopyAlert(entry.displayName, message, "Copy link", link)
    }

    private fun formatSize(bytes: Long): String =
        if (bytes >= 1_000_000_000) "${IosDeviceCapability.format(bytes / 1_000_000_000.0, 2)} GB" else "${bytes / 1_000_000} MB"

    // ── Actions ──

    private fun unload(message: String) {
        bridge?.unload()
        loadedPath = null
        refreshUi(message)
    }

    private fun runWarmup() {
        if (selectedName == null) return
        uiState = uiState.copy(warmupResult = "Running a test prompt…")
        scope.launch {
            val started = TimeSource.Monotonic.markNow()
            var pieces = 0
            val result = runCatching {
                chat(listOf(ChatMessage("user", "Say hello and tell me what you are in one short sentence."))) { pieces++ }
            }
            val seconds = started.elapsedNow().inWholeMilliseconds / 1000.0
            val message = result.fold(
                onSuccess = { "Reply in ${(seconds * 10).toInt() / 10.0}s (~${if (seconds > 0) (pieces / seconds).toInt() else 0} tokens/s):\n$it" },
                onFailure = { "Test failed: ${it.message}" },
            )
            PlatformLogger.i(TAG, message.lineSequence().first())
            uiState = uiState.copy(warmupResult = message)
            refreshUi()
        }
    }

    private fun showSelectedModelInfo() {
        val name = selectedName ?: return
        val presenter = IosMediaPlatform.topViewController() ?: return
        val runtime = if (name.endsWith(".gguf", ignoreCase = true)) "llama.cpp" else "LiteRT-LM"
        val alert = UIAlertController.alertControllerWithTitle(
            name,
            "Runtime: $runtime\nSize: ${formatSize(sizeOf(name))}\nContext: ${generationSettings.contextSize} tokens",
            UIAlertControllerStyleAlert,
        )
        alert.addAction(UIAlertAction.actionWithTitle("Delete model", UIAlertActionStyleDestructive) { _ -> confirmDelete(name) })
        alert.addAction(UIAlertAction.actionWithTitle("OK", UIAlertActionStyleCancel, handler = null))
        presenter.presentViewController(alert, animated = true, completion = null)
    }

    private fun confirmDelete(name: String) {
        scope.launch {
            if (!IosMediaPlatform.confirm("Delete $name?", "The file is removed from this iPhone.", "Delete", "Cancel")) return@launch
            if (loadedPath == pathOf(name)) unload("")
            NSFileManager.defaultManager.removeItemAtPath(pathOf(name), null)
            if (preferences.getString(KEY_SELECTED, "") == name) preferences.putString(KEY_SELECTED, "")
            refreshUi("Deleted $name.")
        }
    }

    private fun setDownload(state: LocalModelDownloadUiState) {
        scope.launch { uiState = uiState.copy(download = state) }
    }

    /** Same gate as Android's requestDownload: blockers and warnings are shown before starting. */
    private fun download(id: String) {
        val entry = findEntry(id) ?: return
        if (downloadTask != null) return
        val token = huggingFaceToken
        if (entry.gatedDownload && token.isBlank()) {
            refreshUi("${entry.displayName} is gated: accept its license on Hugging Face, then add your token in Curated models.")
            return
        }
        val assessment = IosDeviceCapability.assess(entry)
        val problems = assessment.blockers + assessment.warnings
        if (problems.isEmpty()) {
            startDownload(entry, token)
            return
        }
        scope.launch {
            val proceed = IosMediaPlatform.confirm(
                title = "Device warning",
                message = problems.joinToString("\n\n") +
                    if (!assessment.supported) "\n\niOS may close CyanBridge while loading this model." else "",
                confirmTitle = if (assessment.supported) "Continue" else "Download anyway",
                cancelTitle = "Cancel",
            )
            if (proceed) startDownload(entry, token)
        }
    }

    private fun startDownload(entry: LocalModelCatalogEntry, token: String) {
        val url = entry.sourceUrl?.let { NSURL.URLWithString(it) } ?: return
        val destination = pathOf(entry.expectedFilename)
        val request = NSMutableURLRequest(uRL = url)
        if (token.isNotBlank() && url.host?.endsWith("huggingface.co") == true) {
            request.setValue("Bearer ${token.trim()}", forHTTPHeaderField = "Authorization")
        }
        setDownload(LocalModelDownloadUiState(isInFlight = true, message = "Downloading ${entry.displayName}…", progressPercent = 0))
        val task = NSURLSession.sharedSession.downloadTaskWithRequest(request) { location, response, error ->
            // The temporary file disappears when this handler returns, so move it here.
            val status = (response as? NSHTTPURLResponse)?.statusCode?.toInt() ?: 0
            val installed: Boolean
            val message = when {
                error != null -> "Download stopped: ${error.localizedDescription}".also { installed = false }
                location == null || status !in 200..299 -> (
                    if (status == 401 || status == 403) "Download refused (HTTP $status). Accept the license and check the token."
                    else "Download failed (HTTP $status)."
                    ).also { installed = false }
                else -> {
                    NSFileManager.defaultManager.removeItemAtPath(destination, null)
                    installed = NSFileManager.defaultManager.moveItemAtURL(location, NSURL.fileURLWithPath(destination), null)
                    if (installed) "${entry.displayName} installed." else "Could not save the model file."
                }
            }
            scope.launch {
                downloadJob?.cancel()
                downloadTask = null
                downloadingId = null
                PlatformLogger.i(TAG, message)
                if (installed && selectedName == null) preferences.putString(KEY_SELECTED, entry.expectedFilename)
                uiState = uiState.copy(download = LocalModelDownloadUiState(message = message))
                refreshUi()
            }
        }
        downloadTask = task
        downloadingId = entry.id
        task.resume()
        refreshUi()
        downloadJob = scope.launch {
            while (true) {
                delay(500)
                val expected = task.countOfBytesExpectedToReceive
                val received = task.countOfBytesReceived
                val percent = if (expected > 0) (received * 100 / expected).toInt() else null
                uiState = uiState.copy(
                    download = LocalModelDownloadUiState(
                        isInFlight = true,
                        message = "Downloading ${entry.displayName}: ${formatSize(received)}" +
                            if (expected > 0) " of ${formatSize(expected)}" else "",
                        progressPercent = percent,
                    ),
                )
            }
        }
    }

    private fun importModel() {
        val presenter = IosMediaPlatform.topViewController() ?: return
        val picker = UIDocumentPickerViewController(forOpeningContentTypes = listOf(UTTypeData), asCopy = true)
        val delegate = ModelPickerDelegate { url ->
            pickerDelegate = null
            val source = url ?: return@ModelPickerDelegate
            val name = source.lastPathComponent ?: return@ModelPickerDelegate
            if (!isModelFile(name)) {
                NSFileManager.defaultManager.removeItemAtURL(source, null)
                refreshUi("$name is not a model. Pick a .gguf (llama.cpp) or .litertlm (LiteRT-LM) file.")
                return@ModelPickerDelegate
            }
            NSFileManager.defaultManager.removeItemAtPath(pathOf(name), null)
            val moved = NSFileManager.defaultManager.moveItemAtURL(source, NSURL.fileURLWithPath(pathOf(name)), null)
            if (moved) preferences.putString(KEY_SELECTED, name)
            refreshUi(if (moved) "Imported $name." else "Could not import $name.")
        }
        pickerDelegate = delegate // UIDocumentPickerViewController holds its delegate weakly.
        picker.delegate = delegate
        presenter.presentViewController(picker, animated = true, completion = null)
    }
}

private class ModelPickerDelegate(
    private val onPicked: (NSURL?) -> Unit,
) : NSObject(), UIDocumentPickerDelegateProtocol {
    override fun documentPicker(controller: UIDocumentPickerViewController, didPickDocumentsAtURLs: List<*>) {
        onPicked(didPickDocumentsAtURLs.firstOrNull() as? NSURL)
    }

    override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) = onPicked(null)
}

@Serializable
private data class StoredGenerationSettings(
    val maxTokens: Int? = null,
    val contextSize: Int? = null,
    val temperature: Double? = null,
    val topK: Int? = null,
    val topP: Double? = null,
    val repetitionPenalty: Double? = null,
    val seed: Int? = null,
) {
    fun toSettings(): LocalGenerationSettings {
        val d = LocalGenerationSettings()
        return LocalGenerationSettings(
            maxTokens ?: d.maxTokens, contextSize ?: d.contextSize, temperature ?: d.temperature,
            topK ?: d.topK, topP ?: d.topP, repetitionPenalty ?: d.repetitionPenalty, seed ?: d.seed,
        )
    }

    companion object {
        fun from(s: LocalGenerationSettings) = StoredGenerationSettings(
            s.maxTokens, s.contextSize, s.temperature, s.topK, s.topP, s.repetitionPenalty, s.seed,
        )
    }
}
