package com.fersaiyan.cyanbridge.localmodels.engine

import android.content.Context
import android.util.Log
import com.fersaiyan.cyanbridge.BuildConfig
import com.fersaiyan.cyanbridge.ai.decision.DecisionLogits
import com.fersaiyan.cyanbridge.ai.decision.NextTokenDecisionRuntime
import com.fersaiyan.cyanbridge.localmodels.settings.LocalComputeBackend
import com.fersaiyan.cyanbridge.localmodels.settings.LocalMtpResolver
import com.fersaiyan.cyanbridge.localmodels.settings.LocalMtpSettingsRepository
import com.fersaiyan.cyanbridge.localmodels.storage.LocalModelStorageRepository
import com.fersaiyan.cyanbridge.ui.MyApplication
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ToolProvider
import com.google.ai.edge.litertlm.ThinkingConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

class LiteRtLocalInferenceEngine(private val context: Context = MyApplication.CONTEXT) : LocalInferenceEngine, NextTokenDecisionRuntime {
    private val supportedAudioExtensions = setOf("wav", "mp3", "flac")

    private val mutex = Mutex()
    private val operationMutex = Mutex()
    private var engine: Engine? = null
    private var activeConversation: Conversation? = null
    private var modelPath: String? = null
    private var activeLoadConfig: EngineLoadConfig? = null
    private var activeLoadResult: EngineLoadResult? = null

    override suspend fun loadModel(modelPath: String, config: EngineLoadConfig): EngineLoadResult = operationMutex.withLock {
        loadModelSerialized(modelPath, config)
    }

    private suspend fun loadModelSerialized(modelPath: String, config: EngineLoadConfig): EngineLoadResult {
        val effectiveConfig = resolveAutomaticMtp(modelPath, config)
        Log.i(
            TAG,
            "loadModel path=$modelPath backend=${effectiveConfig.computeBackend} cpuThreads=${effectiveConfig.cpuThreads} " +
                "context=${effectiveConfig.contextSize} gpuLayers=${effectiveConfig.gpuLayers} mtp=${effectiveConfig.speculativeDecoding}",
        )
        mutex.withLock {
            if (
                this.modelPath == modelPath &&
                engine != null &&
                activeLoadConfig == effectiveConfig
            ) {
                Log.i(TAG, "Reusing existing LiteRT engine for $modelPath")
                return activeLoadResult ?: EngineLoadResult(
                    activeBackend = effectiveConfig.computeBackend,
                    activeGpuLayers = 0,
                    speculativeDecodingEnabled = effectiveConfig.speculativeDecoding,
                )
            }
        }

        val loadOutcome = withContext(Dispatchers.IO) {
            when (effectiveConfig.computeBackend) {
                LocalComputeBackend.GPU -> initializeGpuWithFallback(modelPath, effectiveConfig)
                LocalComputeBackend.NPU_EXPERIMENTAL -> initializeNpuWithFallback(modelPath, effectiveConfig)
                LocalComputeBackend.CPU -> {
                    createInitializedEngine(
                        modelPath = modelPath,
                        backend = Backend.CPU(effectiveConfig.cpuThreads),
                        visionBackend = Backend.CPU(effectiveConfig.cpuThreads),
                        audioBackend = Backend.CPU(effectiveConfig.cpuThreads),
                        maxNumTokens = effectiveConfig.contextSize,
                        speculativeDecoding = effectiveConfig.speculativeDecoding,
                    ) to EngineLoadResult(
                        activeBackend = LocalComputeBackend.CPU,
                        activeGpuLayers = 0,
                        speculativeDecodingEnabled = effectiveConfig.speculativeDecoding,
                    )
                }
            }
        }

        return mutex.withLock {
            closeConversationLocked()
            closeEngineLocked()
            engine = loadOutcome.first
            this@LiteRtLocalInferenceEngine.modelPath = modelPath
            activeLoadConfig = effectiveConfig
            activeLoadResult = loadOutcome.second
            Log.i(
                TAG,
                "LiteRT model ready path=$modelPath activeBackend=${loadOutcome.second.activeBackend} " +
                    "mtp=${loadOutcome.second.speculativeDecodingEnabled} fallback=${loadOutcome.second.fallbackReason}",
            )
            loadOutcome.second
        }
    }

    private fun resolveAutomaticMtp(modelPath: String, config: EngineLoadConfig): EngineLoadConfig {
        if (config.speculativeDecoding != null) return config
        val installed = LocalModelStorageRepository.listInstalled(context)
            .firstOrNull { it.absolutePath == modelPath }
            ?: return config
        val file = File(modelPath)
        val supported = supportsSpeculativeDecoding(modelPath)
        val mode = LocalMtpSettingsRepository.getMode(context, installed.id)
        val recommendation = LocalMtpSettingsRepository.cachedRecommendation(
            context = context,
            modelId = installed.id,
            backend = config.computeBackend,
            modelSignature = LocalMtpSettingsRepository.modelSignature(
                path = file.absolutePath,
                sizeBytes = file.length(),
                lastModifiedMs = file.lastModified(),
            ),
        )
        return config.copy(
            speculativeDecoding = LocalMtpResolver.resolve(
                mode = mode,
                supported = supported,
                cachedRecommendation = recommendation,
            ),
        )
    }

    private fun initializeGpuWithFallback(
        modelPath: String,
        config: EngineLoadConfig,
    ): Pair<Engine, EngineLoadResult> {
        return runCatching {
            createInitializedEngine(
                modelPath = modelPath,
                backend = Backend.GPU(),
                visionBackend = Backend.CPU(config.cpuThreads),
                audioBackend = Backend.CPU(config.cpuThreads),
                maxNumTokens = config.contextSize,
                speculativeDecoding = config.speculativeDecoding,
            ) to EngineLoadResult(
                activeBackend = LocalComputeBackend.GPU,
                activeGpuLayers = config.gpuLayers.coerceAtLeast(0),
                speculativeDecodingEnabled = config.speculativeDecoding,
            )
        }.recoverCatching { gpuMediaErr ->
            Log.w(TAG, "LiteRT media backend failed on GPU: ${compactError(gpuMediaErr)}", gpuMediaErr)
            createInitializedEngine(
                modelPath = modelPath,
                backend = Backend.GPU(),
                visionBackend = null,
                audioBackend = null,
                maxNumTokens = config.contextSize,
                speculativeDecoding = config.speculativeDecoding,
            ) to EngineLoadResult(
                activeBackend = LocalComputeBackend.GPU,
                activeGpuLayers = config.gpuLayers.coerceAtLeast(0),
                fallbackReason =
                    "LiteRT GPU audio/vision backend mismatch (${compactError(gpuMediaErr)}). " +
                        "Continuing with GPU text acceleration.",
                speculativeDecodingEnabled = config.speculativeDecoding,
            )
        }.recoverCatching { gpuErr ->
            Log.w(TAG, "LiteRT GPU unavailable, falling back to CPU: ${compactError(gpuErr)}", gpuErr)
            createInitializedEngine(
                modelPath = modelPath,
                backend = Backend.CPU(config.cpuThreads),
                visionBackend = Backend.CPU(config.cpuThreads),
                audioBackend = Backend.CPU(config.cpuThreads),
                maxNumTokens = config.contextSize,
                speculativeDecoding = config.speculativeDecoding,
            ) to EngineLoadResult(
                activeBackend = LocalComputeBackend.CPU,
                activeGpuLayers = 0,
                fallbackReason = "LiteRT GPU unavailable (${compactError(gpuErr)}). Fell back to CPU.",
                speculativeDecodingEnabled = config.speculativeDecoding,
            )
        }.getOrThrow()
    }

    private fun initializeNpuWithFallback(
        modelPath: String,
        config: EngineLoadConfig,
    ): Pair<Engine, EngineLoadResult> {
        val npuBackend = Backend.NPU(nativeLibraryDir = context.applicationInfo.nativeLibraryDir)
        return runCatching {
            val npu = LiteRtNpuSupport.status(context)
            check(npu.available) { npu.reason }
            LiteRtNpuSupport.compiledFor(modelPath)?.let { target ->
                check(target == npu.socModel) { "model is compiled for $target, this phone is ${npu.socModel}" }
            }
            createInitializedEngine(
                modelPath = modelPath,
                backend = npuBackend,
                visionBackend = Backend.GPU(),
                audioBackend = Backend.CPU(config.cpuThreads),
                maxNumTokens = config.contextSize,
                speculativeDecoding = config.speculativeDecoding,
            ) to EngineLoadResult(
                activeBackend = LocalComputeBackend.NPU_EXPERIMENTAL,
                activeGpuLayers = 0,
                speculativeDecodingEnabled = config.speculativeDecoding,
            )
        }.recoverCatching { npuErr ->
            Log.w(TAG, "LiteRT NPU unavailable; trying GPU: ${compactError(npuErr)}", npuErr)
            val gpu = initializeGpuWithFallback(modelPath, config.copy(computeBackend = LocalComputeBackend.GPU))
            val reason = buildString {
                append("LiteRT NPU unavailable (${compactError(npuErr)}). ")
                append("Fell back to ${gpu.second.activeBackend.label}.")
                gpu.second.fallbackReason?.takeIf { it.isNotBlank() }?.let { append(" $it") }
            }
            gpu.first to gpu.second.copy(fallbackReason = reason)
        }.getOrThrow()
    }

    @OptIn(ExperimentalApi::class)
    private fun createInitializedEngine(
        modelPath: String,
        backend: Backend,
        visionBackend: Backend?,
        audioBackend: Backend?,
        maxNumTokens: Int,
        speculativeDecoding: Boolean?,
    ): Engine {
        // Text-only bundles (custom exports, fine-tunes) must not get media backends.
        val media = runCatching { LiteRtModelProbe.mediaSupport(modelPath) }.getOrNull()
        val vision = visionBackend?.takeIf { media?.vision != false }
        val audio = audioBackend?.takeIf { media?.audio != false }
        Log.i(
            TAG,
            "Initializing LiteRT engine backend=${backend.javaClass.simpleName} " +
                "vision=${vision?.javaClass?.simpleName ?: "none"} " +
                "audio=${audio?.javaClass?.simpleName ?: "none"} " +
                "maxTokens=$maxNumTokens mtp=$speculativeDecoding",
        )
        val config = EngineConfig(
            modelPath = modelPath,
            backend = backend,
            visionBackend = vision,
            audioBackend = audio,
            maxNumTokens = maxNumTokens.coerceAtLeast(1024),
            cacheDir = context.cacheDir.path,
        )
        val created = synchronized(engineCreationLock) {
            val previous = ExperimentalFlags.enableSpeculativeDecoding
            try {
                ExperimentalFlags.enableSpeculativeDecoding = speculativeDecoding
                Engine(config).also { it.initialize() }
            } finally {
                ExperimentalFlags.enableSpeculativeDecoding = previous
            }
        }
        if (media != null || (vision == null && audio == null)) return created
        // The runtime could not report modalities (0.16): media backends initialize
        // but a text-only bundle fails on the first conversation. Probe once, cheaply.
        val missingEncoder = runCatching {
            created.createConversation(ConversationConfig(prefillPrefaceOnInit = false)).close()
        }.exceptionOrNull()?.takeIf { MISSING_MEDIA_ENCODER.containsMatchIn(it.message.orEmpty()) }
            ?: return created
        Log.w(TAG, "LiteRT bundle has no media encoder; reloading text-only: ${compactError(missingEncoder)}")
        created.close()
        return createInitializedEngine(modelPath, backend, null, null, maxNumTokens, speculativeDecoding)
    }

    override suspend fun unloadModel() = operationMutex.withLock {
        mutex.withLock {
            Log.i(TAG, "Unloading LiteRT model ${modelPath.orEmpty()}")
            closeConversationLocked()
            closeEngineLocked()
            modelPath = null
            activeLoadConfig = null
            activeLoadResult = null
        }
    }

    override suspend fun generate(config: GenerationConfig, onToken: (String) -> Unit): GenerationResult = operationMutex.withLock {
        generateSerialized(config, onToken)
    }

    override suspend fun score(userPrompt: String, labels: List<String>): DecisionLogits = operationMutex.withLock {
        check(BuildConfig.LITERT_DECISION_PROTOTYPE) { "LiteRT decision prototype is disabled" }
        withContext(Dispatchers.IO) {
            val llm = mutex.withLock { engine } ?: error("LiteRT engine is not initialized")
            LiteRtDecisionBinding.score(llm, renderDecisionPrompt(llm, userPrompt), labels)
        }
    }

    /** Exact text scored for [userPrompt]; exposed for HIL prompt-rendering diagnostics. */
    suspend fun renderedDecisionPrompt(userPrompt: String): String = operationMutex.withLock {
        withContext(Dispatchers.IO) {
            renderDecisionPrompt(mutex.withLock { engine } ?: error("LiteRT engine is not initialized"), userPrompt)
        }
    }

    @OptIn(ExperimentalApi::class)
    private fun renderDecisionPrompt(llm: Engine, userPrompt: String): String {
        // Render with the model's own template, including BOS/assistant prefix.
        // Rendering performs no prefill and the temporary conversation is closed
        // before allocating the actual decision context on the same engine.
        val rendered = llm.createConversation(
            ConversationConfig(
                prefillPrefaceOnInit = false,
                thinkingConfig = ThinkingConfig(enableThinking = false),
            ),
        ).use { conversation ->
            conversation.renderMessageIntoString(
                Message.user(Contents.of(userPrompt)),
                mapOf("enable_thinking" to false),
            )
        }
        return rendered + "⊲"
    }

    private suspend fun generateSerialized(config: GenerationConfig, onToken: (String) -> Unit): GenerationResult {
        Log.i(
            TAG,
            "generate promptChars=${config.prompt.length} images=${config.imagePaths.size} " +
                "hasAudio=${!config.audioPath.isNullOrBlank()} maxTokens=${config.maxTokens}",
        )
        val llm = mutex.withLock {
            engine ?: throw IllegalStateException("LiteRT engine is not initialized")
        }
        val conversation = withContext(Dispatchers.IO) {
            llm.createConversation(buildConversationConfig(config))
        }
        mutex.withLock {
            closeConversationLocked()
            activeConversation = conversation
        }
        return try {
            val text = withContext(Dispatchers.IO) {
                val userContents = buildUserContents(config)
                runCatching {
                    generateFromConversation(conversation, config.prompt, userContents, onToken)
                }.recoverCatching {
                    if (userContents == null) throw it
                    generateFromConversation(conversation, config.prompt, null, onToken)
                }.getOrThrow()
            }
            GenerationResult(text = text, tokenCount = tokenizeEstimate(text))
        } catch (t: Throwable) {
            Log.e(TAG, "LiteRT generation failed", t)
            throw t
        } finally {
            mutex.withLock {
                if (activeConversation === conversation) closeConversationLocked()
            }
        }
    }

    override suspend fun cancelGeneration() {
        val conv = mutex.withLock { activeConversation } ?: return
        withContext(Dispatchers.IO) {
            Log.i(TAG, "Cancelling active LiteRT generation")
            runCatching { conv.cancelProcess() }
        }
    }

    override suspend fun tokenizeCount(text: String): Int = tokenizeEstimate(text)
    override fun isModelLoaded(): Boolean = engine != null
    override fun loadedModelPath(): String? = modelPath

    private fun buildConversationConfig(config: GenerationConfig): ConversationConfig {
        val sampler = SamplerConfig(
            topK = config.topK.coerceIn(1, 200),
            topP = config.topP.coerceIn(0.0, 1.0),
            temperature = config.temperature.coerceIn(0.0, 2.0),
            seed = config.seed,
        )
        return ConversationConfig(
            systemInstruction = Contents.of(""),
            initialMessages = emptyList<Message>(),
            tools = emptyList<ToolProvider>(),
            samplerConfig = sampler,
            automaticToolCalling = true,
        )
    }

    private fun extractText(message: Message): String {
        val chunks = message.contents.contents.mapNotNull { content ->
            when (content) {
                is Content.Text -> content.text
                else -> null
            }
        }
        return if (chunks.isNotEmpty()) chunks.joinToString(separator = "") else message.toString()
    }

    private suspend fun generateFromConversation(
        conversation: Conversation,
        prompt: String,
        userContents: Contents?,
        onToken: (String) -> Unit,
    ): String {
        val contents = userContents ?: Contents.of(Content.Text(prompt))
        val assembled = StringBuilder()
        conversation.sendMessageAsync(contents, emptyMap<String, Any>()).collect { message ->
            val currentText = extractText(message)
            if (currentText.isBlank()) return@collect
            val delta = incrementalDelta(assembled.toString(), currentText)
            if (delta.isNotEmpty()) {
                assembled.append(delta)
                onToken(delta)
            }
        }
        return assembled.toString()
    }

    private fun buildUserContents(config: GenerationConfig): Contents? {
        val hasImage = config.imagePaths.isNotEmpty()
        val hasAudio = !config.audioPath.isNullOrBlank()
        if (!hasImage && !hasAudio) return null
        val parts = ArrayList<Content>()
        config.imagePaths.forEach { rawPath ->
            val path = rawPath.trim()
            if (path.isBlank()) return@forEach
            val file = File(path)
            if (file.exists()) parts += Content.ImageFile(file.absolutePath)
        }
        config.audioPath?.trim()?.takeIf { it.isNotBlank() }?.let { path ->
            val file = File(path)
            if (file.exists()) {
                val ext = file.extension.lowercase(Locale.US)
                if (ext !in supportedAudioExtensions) {
                    throw IllegalArgumentException(
                        "Unsupported audio format '.$ext'. LiteRT-LM supports wav, mp3, and flac audio attachments.",
                    )
                }
                parts += Content.AudioFile(file.absolutePath)
            }
        }
        parts += Content.Text(config.prompt)
        return Contents.of(parts)
    }

    private fun closeConversationLocked() {
        runCatching { activeConversation?.close() }
        activeConversation = null
    }

    internal fun incrementalDelta(previous: String, current: String): String {
        if (previous.isBlank()) return current
        if (current.startsWith(previous)) return current.substring(previous.length)
        if (previous.startsWith(current)) return ""
        val maxPrefix = previous.length.coerceAtMost(current.length)
        var overlap = 0
        var i = maxPrefix
        while (i > 0) {
            if (previous.endsWith(current.substring(0, i))) {
                overlap = i
                break
            }
            i--
        }
        return current.substring(overlap)
    }

    private fun closeEngineLocked() {
        runCatching { engine?.close() }
        engine = null
    }

    private fun compactError(err: Throwable?): String {
        if (err == null) return "unknown error"
        val msg = err.message?.replace('\n', ' ')?.replace(Regex("\\s+"), " ")?.trim()
        return if (msg.isNullOrEmpty()) err::class.java.simpleName else msg
    }

    private fun tokenizeEstimate(text: String): Int {
        val words = text.trim().split(Regex("\\s+")).filter { it.isNotBlank() }.size
        return (words * 1.5).toInt().coerceAtLeast(1)
    }

    companion object {
        private const val TAG = "LiteRtLocalEngine"
        private val engineCreationLock = Any()
        // e.g. "NOT_FOUND: TF_LITE_AUDIO_ENCODER_HW not found in the model."
        internal val MISSING_MEDIA_ENCODER = Regex("""NOT_FOUND: \S*(AUDIO|VISION)\S*ENCODER\S* not found in the model""")

        fun supportsSpeculativeDecoding(modelPath: String): Boolean {
            if (modelPath.isBlank()) return false
            return runCatching {
                LiteRtModelProbe.supportsSpeculativeDecoding(modelPath)
            }.getOrDefault(false)
        }
    }
}
