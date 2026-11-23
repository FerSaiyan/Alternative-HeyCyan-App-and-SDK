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
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.ResponseFormat
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ToolProvider
import com.google.ai.edge.litertlm.ThinkingConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import java.io.File
import java.util.Collections
import java.util.IdentityHashMap
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
    /** False while the engine has an audio encoder that has not run yet (see [primeAudioEncoder]). */
    @Volatile private var audioEncoderReady = true
    /** False until an audio request succeeds on the loaded engine (see [generate]'s retry). */
    @Volatile private var audioSucceededSinceLoad = false
    /** Key of the [SessionTurn] conversation kept open in [activeConversation], or null. */
    private var sessionKey: String? = null
    /** Estimated tokens in the open session's KV cache (system turn, every user turn and reply). */
    private var sessionTokens = 0
    /**
     * System-prompt cache (LiteRT-LM 0.19 [cloneOrNull]): one conversation primed with a request's system turn
     * (prefillPrefaceOnInit), cloned for every request with the same system turn and sampler, so only the user
     * turn is prefilled (host CPU, Gemma 4 E2B, 653-token spoken-decision turn: first token 4.4-4.8 s -> 0.7-0.9 s;
     * /mnt/seagate/ML/litertlm-clone-research-20261123/REPORT.md). CPU/GPU only (the NPU executor cannot clone),
     * never persisted, rebuilt after every load. Guarded by [mutex].
     */
    private class PrimedRoot(val key: String, val conversation: Conversation)

    /**
     * Photo questions: the photo and the spoken question arrive in parallel, in either order, and processing them
     * after both arrived made the image encoder (or the audio) the wait (worklog 2026-10-23). [prepareFirstPart]
     * opens the question's conversation with whichever arrived first already prefilled (as its own user turn and a
     * one-word model reply); the question's request then processes only the other one. Works on every backend
     * (no clone). Guarded by [mutex].
     */
    /**
     * GPU/NPU system-prompt reuse ("spare", /mnt/seagate/ML/gpu-prefill-research-20261123/REPORT.md): once a spoken
     * question's conversation is gone, its system turn is prefilled into a new conversation in the background (while
     * the answer is spoken), and the next spoken question with the same system turn uses it. No clone, so no KV-cache
     * copies (on the GPU each clone cost two 37.7 MB copies, ~1.3 s each). Device Run, 44 paired questions: first
     * token 0.84 -> 0.49 s (S24 Ultra), 1.19 -> 0.63 s (Tab S10+); routes agreed 42/44, both changes to the expected
     * tag. Created only when no other conversation is open, and closed before anything else creates one (otherwise
     * switching would copy the KV cache). Guarded by [mutex].
     */
    private var spare: PrimedRoot? = null
    /** The system turn (and sampler) the next spare is primed with: the last spoken question's, or [primeSystemTurn]'s. */
    @Volatile private var spareBase: GenerationConfig? = null
    private val spareScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
    @Volatile private var spareJob: kotlinx.coroutines.Job? = null

    private class PreparedPart(val key: String, val imagePath: String?, val audioPath: String?, val conversation: Conversation)
    private var preparedPart: PreparedPart? = null
    private var primedRoot: PrimedRoot? = null
    @Volatile private var rootCacheUsable = true

    /** Set by [cancelGeneration] (logged when the stopped turn fails). */
    @Volatile private var cancelRequested = false
    /**
     * Native turn of each conversation, completed when LiteRT-LM reports its end. Closing a conversation
     * while its prefill runs crashes LiteRT-LM (0.16 to 0.19, Play vitals SIGSEGV in liblitertlm_jni.so):
     * ~Conversation clears `session_` and then waits for the session's tasks, and the prefill callback calls
     * `session_->RunDecodeAsync` on the cleared pointer. See [streamTurn] and [closeWhenIdle].
     */
    private val turns: MutableMap<Conversation, CompletableDeferred<Unit>> = Collections.synchronizedMap(IdentityHashMap())

    override suspend fun loadModel(modelPath: String, config: EngineLoadConfig): EngineLoadResult = operationMutex.withLock {
        loadModelSerialized(modelPath, config)
    }

    private suspend fun loadModelSerialized(modelPath: String, config: EngineLoadConfig): EngineLoadResult {
        // SoC-compiled NPU packages (e.g. *_Qualcomm_SM8650.litertlm) only run on the NPU,
        // whatever backend an imported model's settings name.
        val effectiveConfig = resolveAutomaticMtp(modelPath, config).let {
            if (LiteRtNpuSupport.compiledFor(modelPath) != null) it.copy(computeBackend = LocalComputeBackend.NPU_EXPERIMENTAL) else it
        }
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
            closeRootLocked()
            closePreparedPartLocked()
            closeSpareLocked()
            closeEngineLocked()
            rootCacheUsable = true
            engine = loadOutcome.first
            this@LiteRtLocalInferenceEngine.modelPath = modelPath
            activeLoadConfig = effectiveConfig
            activeLoadResult = loadOutcome.second
            // Runs once this load releases operationMutex (see warmUpAudio).
            spareScope.launch { runCatching { warmUpAudio() } }
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
            // Photos are encoded on the GPU too: the CPU vision encoder took 5.4–8.7 s per photo on the
            // Fold6 vs 2.2–2.4 s on the GPU. The audio encoder stays on CPU threads, as on the NPU path.
            createInitializedEngine(
                modelPath = modelPath,
                backend = Backend.GPU(),
                visionBackend = Backend.GPU(),
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
        // A package compiled for one SoC's NPU cannot run on GPU/CPU, so report the NPU error.
        val target = LiteRtNpuSupport.compiledTarget(modelPath)
        val npuOnly = target != null
        return runCatching {
            val npu = LiteRtNpuSupport.status(context).let { status ->
                // Normally fetched with the model (or by NpuRuntimePrefetch); last resort if both failed.
                val hexagon = status.hexagon
                if (status.available || !status.skelDownloadable || hexagon == null) return@let status
                Log.w(TAG, "Hexagon $hexagon NPU library missing at load; downloading it now")
                runCatching { QnnSkels.ensure(context, hexagon) }
                    .onFailure { Log.w(TAG, "NPU library download at load failed: ${compactError(it)}") }
                LiteRtNpuSupport.status(context)
            }
            check(npu.available) { npu.reason }
            if (target != null) {
                check(target.vendor == npu.vendor && target.socModel == npu.socModel) {
                    "model is compiled for ${target.vendor.label} ${target.socModel}, this phone is ${npu.vendor?.label.orEmpty()} ${npu.socModel}"
                }
            }
            val npuBackend = Backend.NPU(nativeLibraryDir = LiteRtNpuSupport.dispatchDir(context, npu))
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
            if (npuOnly) throw IllegalStateException("NPU model could not start: ${compactError(npuErr)}", npuErr)
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
        audioEncoderReady = audio == null || vision == null
        audioSucceededSinceLoad = false
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

    /**
     * Prefills [config]'s system turn and the one part of a photo question that has arrived (its single photo, or
     * its recording when [config] has no photo) into a new conversation, kept for the question's request with the
     * same system turn and sampler. Returns false when nothing was prepared.
     */
    suspend fun prepareFirstPart(config: GenerationConfig): Boolean = operationMutex.withLock {
        val image = config.imagePaths.singleOrNull()?.trim()?.takeIf { File(it).isFile }
        val audio = config.audioPath?.trim()?.takeIf { image == null && File(it).isFile }
        if (image == null && audio == null) return@withLock false
        // The question's own conversation replaces any open one; closing it first avoids the GPU KV-cache copy.
        val llm = mutex.withLock { closeSpareLocked(); closeConversationLocked(); engine } ?: return@withLock false
        val started = System.currentTimeMillis()
        val conversation = runCatching {
            withContext(Dispatchers.IO) {
                llm.createConversation(
                    buildConversationConfig(
                        config,
                        prefillPreface = true,
                        preparedTurn = firstPartMessages(image, audio, config.turnContext, config.photoNote),
                    ),
                )
            }
        }.onFailure { Log.w(TAG, "Prefill of the first part failed; the question processes it then: ${compactError(it)}") }
            .getOrNull() ?: return@withLock false
        mutex.withLock {
            closePreparedPartLocked()
            preparedPart = PreparedPart(rootKey(config), image, audio, conversation)
        }
        Log.i(TAG, "Prefilled the ${if (image != null) "photo" else "recording"} before the other part in ${System.currentTimeMillis() - started} ms")
        true
    }

    /** The prepared conversation when [config] is its question, else null (a stale one is closed). */
    private suspend fun takePreparedPart(config: GenerationConfig): PreparedPart? = mutex.withLock {
        val prepared = preparedPart ?: return@withLock null
        preparedPart = null
        val images = config.imagePaths.map(String::trim)
        val matches = prepared.key == rootKey(config) && when {
            prepared.imagePath != null -> images == listOf(prepared.imagePath)
            else -> config.audioPath?.trim() == prepared.audioPath
        }
        if (matches) prepared else {
            closeWhenIdle(prepared.conversation)
            null
        }
    }

    private suspend fun closePreparedPartLocked() {
        preparedPart?.let { closeWhenIdle(it.conversation) }
        preparedPart = null
    }

    override suspend fun unloadModel() = operationMutex.withLock {
        spareJob?.cancel()
        mutex.withLock {
            Log.i(TAG, "Unloading LiteRT model ${modelPath.orEmpty()}")
            closeConversationLocked()
            closeRootLocked()
            closePreparedPartLocked()
            closeSpareLocked()
            closeEngineLocked()
            modelPath = null
            activeLoadConfig = null
            activeLoadResult = null
        }
    }

    override suspend fun generate(config: GenerationConfig, onToken: (String) -> Unit): GenerationResult = operationMutex.withLock {
        var emitted = false
        try {
            generateSerialized(config) { emitted = true; onToken(it) }
        } catch (error: Throwable) {
            // Observed on the AVD after every install/update: the audio encoder's XNNPack delegate
            // "failed to prepare" on the first audio request while its weight cache is being written;
            // the same engine succeeds afterwards. Retry that one case once, before any output.
            fun firstAudioFailure(e: Throwable) = !config.audioPath.isNullOrBlank() && !audioSucceededSinceLoad && !emitted &&
                e.message.orEmpty().contains("audio_litert_compiled_model_executor")
            if (!firstAudioFailure(error)) throw error
            Log.w(TAG, "LiteRT audio encoder failed on its first request after load; retrying")
            kotlinx.coroutines.delay(FIRST_AUDIO_RETRY_DELAY_MS)
            // On the 16 KB-page AVD a process that rebuilds the XNNPack weight cache ("remap failed: Bad
            // address") keeps a broken audio encoder (executor.cc:817) even in a fresh engine; reloading
            // without the weight cache fixes it but needs ~3 GB more RAM (killed by lmkd), so fail cleanly.
            generateSerialized(config) { emitted = true; onToken(it) }
        }
    }

    override suspend fun score(userPrompt: String, labels: List<String>): DecisionLogits = operationMutex.withLock {
        check(BuildConfig.LITERT_DECISION_PROTOTYPE) { "LiteRT decision prototype is disabled" }
        mutex.withLock { closeSpareLocked() }
        withContext(Dispatchers.IO) {
            val llm = mutex.withLock { engine } ?: error("LiteRT engine is not initialized")
            LiteRtDecisionBinding.score(llm, renderDecisionPrompt(llm, userPrompt), labels)
        }
    }

    /** Exact text scored for [userPrompt]; exposed for HIL prompt-rendering diagnostics. */
    suspend fun renderedDecisionPrompt(userPrompt: String): String = operationMutex.withLock {
        mutex.withLock { closeSpareLocked() }
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
        val hasAudio = !config.audioPath.isNullOrBlank()
        val session = config.session
        val turnTokens = estimateTurnTokens(config)
        val conversation: Conversation
        var prepared: PreparedPart? = null
        if (session?.continueExisting == true) {
            conversation = mutex.withLock {
                val open = activeConversation
                if (open == null || sessionKey != session.key) {
                    throw SessionUnavailableException("No open session '${session.key}'")
                }
                val contextSize = activeLoadConfig?.contextSize ?: DEFAULT_CONTEXT_TOKENS
                sessionTokens = runCatching { open.getTokenCount() }.getOrDefault(sessionTokens)
                if (sessionTokens + turnTokens + SESSION_REPLY_RESERVE_TOKENS > contextSize) {
                    Log.i(TAG, "Session '${session.key}' is full (~$sessionTokens + $turnTokens of $contextSize tokens)")
                    closeConversationLocked()
                    throw SessionUnavailableException("Session '${session.key}' would exceed the context")
                }
                open
            }
        } else {
            // A new conversation: the open one (a kept spoken conversation) is closed first. While two are alive the
            // GPU executor copies the KV cache on every switch (Device Run 2026-11-23, S24 Ultra GPU: a photo question
            // asked with the previous question's conversation still open, first token 2.7 s instead of 1.7 s).
            mutex.withLock { closeConversationLocked() }
            if (!audioEncoderReady && !hasAudio && config.imagePaths.isNotEmpty()) primeAudioEncoder(llm)
            prepared = takePreparedPart(config)
            val spareConversation = if (prepared == null) takeSpare(config) else mutex.withLock { closeSpareLocked(); null }
            conversation = prepared?.conversation ?: spareConversation ?: cloneOfPrimedRoot(llm, config) ?: withContext(Dispatchers.IO) {
                llm.createConversation(buildConversationConfig(config))
            }
            mutex.withLock {
                closeConversationLocked()
                activeConversation = conversation
                if (session != null) {
                    sessionKey = session.key
                    sessionTokens = tokenizeEstimate(config.systemInstruction.orEmpty())
                }
            }
        }
        cancelRequested = false
        var keepSession = session != null
        var replyTokens = 0
        return try {
            suspend fun runTurn() = withContext(Dispatchers.IO) {
                val userContents = buildUserContents(config, prepared)
                val decideOnce = config.decideOnce
                if (decideOnce != null) {
                    generateDecideOnce(conversation, config, userContents, decideOnce, onToken)
                } else {
                    generateFromConversation(conversation, config.prompt, userContents, onToken, config.maxTokens)
                }
            }
            val text = runTurn()
            if (hasAudio) {
                audioEncoderReady = true
                audioSucceededSinceLoad = true
            }
            replyTokens = tokenizeEstimate(text)
            val held = if (session != null) runCatching { conversation.getTokenCount() }.getOrNull() else null
            GenerationResult(text = text, tokenCount = replyTokens, sessionTokens = held)
        } catch (t: Throwable) {
            // LiteRT-LM fails every later message of a cancelled conversation ("Task cancelled"), so a stopped
            // or failed turn ends the session; spoken decisions in a session finish instead of being cancelled.
            keepSession = false
            Log.e(TAG, "LiteRT generation failed (cancelled=$cancelRequested)", t)
            throw t
        } finally {
            withContext(NonCancellable) { mutex.withLock {
                if (activeConversation === conversation) {
                    if (keepSession) {
                        sessionTokens = runCatching { conversation.getTokenCount() }.getOrDefault(sessionTokens + turnTokens + replyTokens)
                        Log.i(TAG, "Session '${session?.key}' open, $sessionTokens tokens")
                    } else {
                        closeConversationLocked()
                    }
                }
            } }
            scheduleSpare(config, sessionConversation = conversation.takeIf { keepSession })
        }
    }

    /**
     * Estimated prompt tokens of one turn: Gemma 4 audio is 25 tokens per second (16 kHz mono PCM16 WAV), an
     * image about 280, text about 1.5 per word; a small allowance covers the turn's template tokens.
     */
    private fun estimateTurnTokens(config: GenerationConfig): Int {
        val audio = config.audioPath?.trim()?.takeIf { it.isNotBlank() }?.let { path ->
            val bytes = File(path).length().coerceAtLeast(44L) - 44L
            (bytes / 32_000.0 * AUDIO_TOKENS_PER_SECOND).toInt() + 4
        } ?: 0
        return audio + config.imagePaths.size * IMAGE_TOKENS + tokenizeEstimate(config.prompt) +
            tokenizeEstimate(config.session?.note.orEmpty()) + 8
    }

    /**
     * LiteRT-LM 0.18: once the vision encoder has run in an engine, its audio encoder fails to
     * prepare (XNNPack "failed to prepare", audio_litert_compiled_model_executor.cc:887);
     * audio that ran first keeps working. So run audio once, on a short silent clip, before
     * the engine's first image request.
     */
    /**
     * Runs a short silent clip through the loaded model's audio encoder. The first spoken question pays the audio
     * path's start-up otherwise: ~3 s the first time on a phone (filled once, by the download's "Optimizing" step) and
     * ~1-1.5 s after every load (Device Run FirstAudioLatencyTest, Tab S10+ / S24 Ultra GPU: first question after a
     * reload 5.1 / 4.6 s, after the next reload 1.9 / 2.0 s, warm 0.4-0.9 s). Called after each load in the
     * background, so a question asked meanwhile waits for it instead of paying it itself.
     */
    suspend fun warmUpAudio() = operationMutex.withLock {
        val (llm, path) = mutex.withLock { engine to modelPath }
        if (llm == null || path == null) return@withLock
        if (runCatching { LiteRtModelProbe.mediaSupport(path) }.getOrNull()?.audio == false) return@withLock
        primeAudioEncoder(llm)
    }

    private suspend fun primeAudioEncoder(llm: Engine) = withContext(Dispatchers.IO) {
        mutex.withLock { closeSpareLocked() }
        val start = System.currentTimeMillis()
        runCatching {
            val silence = File(context.cacheDir, "litert_audio_prime.wav")
            if (!silence.isFile) silence.writeBytes(silentWav(samples = 8_000))
            val primer = llm.createConversation(ConversationConfig())
            try {
                primer.streamTurn(Contents.of(listOf(Content.AudioFile(silence.absolutePath), Content.Text(".")))).take(1).collect()
            } finally {
                closeWhenIdle(primer)
            }
        }.onFailure { Log.w(TAG, "LiteRT audio encoder priming failed: ${compactError(it)}", it) }
        audioEncoderReady = true
        Log.i(TAG, "Primed LiteRT audio encoder in ${System.currentTimeMillis() - start} ms")
    }

    /** 16 kHz mono PCM16 WAV of [samples] zero samples. */
    private fun silentWav(samples: Int): ByteArray {
        val data = samples * 2
        return java.nio.ByteBuffer.allocate(44 + data).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + data); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1); putInt(16_000); putInt(32_000)
            putShort(2); putShort(16)
            put("data".toByteArray()); putInt(data)
        }.array()
    }

    override suspend fun cancelGeneration() {
        val conv = mutex.withLock { activeConversation } ?: return
        cancelRequested = true
        withContext(Dispatchers.IO) {
            Log.i(TAG, "Cancelling active LiteRT generation")
            runCatching { conv.cancelProcess() }
        }
    }

    override suspend fun tokenizeCount(text: String): Int = tokenizeEstimate(text)
    override fun isModelLoaded(): Boolean = engine != null
    override fun loadedModelPath(): String? = modelPath

    /**
     * Decide-once turn ([DecideOnceRequest]): a short constrained decision turn writes the heard
     * question and a route label, then the same conversation answers, either after a tiny "answer
     * now" turn or after the photo. The question's audio is prefilled once and stays in the KV cache.
     */
    private suspend fun generateDecideOnce(
        conversation: Conversation,
        config: GenerationConfig,
        userContents: Contents?,
        request: DecideOnceRequest,
        onToken: (String) -> Unit,
    ): String {
        if (request.streamAnswer) return generateDecideOnceStreaming(conversation, config, userContents, request, onToken)
        val first = userContents?.contents.orEmpty().filterNot { it is Content.Text } +
            Content.Text(listOf(config.prompt, request.routingInstruction).filter { it.isNotBlank() }.joinToString("\n\n"))
        val started = System.currentTimeMillis()
        val decision = StringBuilder()
        conversation.streamTurn(
            Contents.of(first),
            maxOutputToken = DecideOncePrompt.DECISION_MAX_TOKENS,
            responseFormat = ResponseFormat.regex(DecideOncePrompt.RESPONSE_REGEX),
        ).collect { message ->
            val current = extractText(message)
            if (current.isNotBlank()) decision.append(incrementalDelta(decision.toString(), current))
        }
        Log.i(TAG, "Decide-once decision shape=${DecideOncePrompt.shape(decision.toString())}")
        val (route, labelled) = DecideOncePrompt.parseRouteOrAnswer(decision.toString())
        if (!labelled) Log.w(TAG, "Decide-once reply had no usable label; answering without the camera")
        val decisionMs = System.currentTimeMillis() - started
        Log.i(TAG, "Decide-once route=$route decisionMs=$decisionMs heardChars=${DecideOncePrompt.heardQuestion(decision.toString()).length}")
        request.onRoute(route, decisionMs)

        val second = if (route == DecideOnceRoute.ANSWER) {
            Contents.of(Content.Text(request.answerInstruction))
        } else {
            val image = request.imageWhenNeeded()?.let(::File)?.takeIf { it.isFile && it.length() > 0 }
            Log.i(TAG, "Decide-once camera turn image=${image != null}")
            if (image != null) {
                Contents.of(listOf(Content.ImageFile(image.absolutePath), Content.Text(request.imageInstruction)))
            } else {
                Contents.of(Content.Text(request.noImageInstruction))
            }
        }
        val answer = generateFromConversation(
            conversation, request.answerInstruction, second, DecideOncePrompt.labelStrippingSink(onToken),
        )
        return DecideOncePrompt.cleanAnswer(answer)
    }

    /** Streaming decide-once: see [DecideOnceRequest.streamAnswer]. */
    private suspend fun generateDecideOnceStreaming(
        conversation: Conversation,
        config: GenerationConfig,
        userContents: Contents?,
        request: DecideOnceRequest,
        onToken: (String) -> Unit,
    ): String = coroutineScope {
        val first = userContents?.contents.orEmpty().filterNot { it is Content.Text } +
            Content.Text(listOf(config.prompt, request.routingInstruction).filter { it.isNotBlank() }.joinToString("\n\n"))
        val started = System.currentTimeMillis()
        val raw = StringBuilder()
        val sink = DecideOncePrompt.labelStrippingSink(onToken)
        var route: DecideOnceRoute? = null
        var answerStart = 0
        var photo: kotlinx.coroutines.Deferred<String?>? = null
        conversation.streamTurn(Contents.of(first)).collect { message ->
            val current = extractText(message)
            if (current.isBlank()) return@collect
            val delta = incrementalDelta(raw.toString(), current)
            if (delta.isEmpty()) return@collect
            raw.append(delta)
            if (route != null) {
                if (route == DecideOnceRoute.ANSWER) sink(delta)
                return@collect
            }
            val text = raw.toString()
            val a = text.indexOf(DecideOncePrompt.ANSWER_LABEL)
            val b = text.indexOf(DecideOncePrompt.CAMERA_LABEL)
            val decided = when {
                b >= 0 && (a < 0 || b < a) -> DecideOnceRoute.CAMERA
                a >= 0 -> DecideOnceRoute.ANSWER
                else -> return@collect
            }
            route = decided
            val decisionMs = System.currentTimeMillis() - started
            Log.i(TAG, "Decide-once (streaming) route=$decided decisionMs=$decisionMs")
            request.onRoute(decided, decisionMs)
            if (decided == DecideOnceRoute.CAMERA) {
                photo = async { request.imageWhenNeeded() }
            } else {
                answerStart = a + DecideOncePrompt.ANSWER_LABEL.length
                text.substring(answerStart).takeIf { it.isNotEmpty() }?.let(sink)
            }
        }
        Log.i(TAG, "Decide-once (streaming) turn-1 shape=${DecideOncePrompt.shape(raw.take(48).toString())}")
        when (route) {
            DecideOnceRoute.ANSWER -> {
                val streamed = DecideOncePrompt.cleanAnswer(raw.substring(answerStart))
                if (streamed.isNotBlank()) {
                    streamed
                } else {
                    // The model obeyed "write nothing after the label": ask for the answer in turn 2.
                    DecideOncePrompt.cleanAnswer(
                        generateFromConversation(
                            conversation, request.answerInstruction, Contents.of(Content.Text(request.answerInstruction)),
                            DecideOncePrompt.labelStrippingSink(onToken),
                        ),
                    )
                }
            }
            null -> {
                // No label: whatever the model wrote after its "Q:" line is the answer.
                Log.w(TAG, "Decide-once (streaming) reply had no label; using it as the answer")
                request.onRoute(DecideOnceRoute.ANSWER, System.currentTimeMillis() - started)
                DecideOncePrompt.cleanAnswer(raw.toString()).also { if (it.isNotEmpty()) onToken(it) }
            }
            DecideOnceRoute.CAMERA -> {
                val image = photo?.await()?.let(::File)?.takeIf { it.isFile && it.length() > 0 }
                Log.i(TAG, "Decide-once (streaming) camera turn image=${image != null}")
                val second = if (image != null) {
                    Contents.of(listOf(Content.ImageFile(image.absolutePath), Content.Text(request.imageInstruction)))
                } else {
                    Contents.of(Content.Text(request.noImageInstruction))
                }
                DecideOncePrompt.cleanAnswer(
                    generateFromConversation(conversation, request.imageInstruction, second, DecideOncePrompt.labelStrippingSink(onToken)),
                )
            }
        }
    }

    private fun buildConversationConfig(
        config: GenerationConfig,
        prefillPreface: Boolean = false,
        /** The first part of a photo question, prefilled as an earlier exchange ([prepareFirstPart]). */
        preparedTurn: List<Message>? = null,
    ): ConversationConfig {
        val sampler = SamplerConfig(
            topK = config.topK.coerceIn(1, 200),
            topP = config.topP.coerceIn(0.0, 1.0),
            temperature = config.temperature.coerceIn(0.0, 2.0),
            seed = config.seed,
        )
        return ConversationConfig(
            systemInstruction = Contents.of(config.systemInstruction?.trim().orEmpty()),
            initialMessages = preparedTurn ?: emptyList<Message>(),
            tools = emptyList<ToolProvider>(),
            samplerConfig = sampler,
            automaticToolCalling = true,
            enableResponseFormat = config.decideOnce != null,
            prefillPrefaceOnInit = prefillPreface,
        )
    }

    /**
     * The prefilled first part: the photo with a note that the question follows, or the recording (after the turn
     * context, the time) with a note that the photo follows; then, unless [photoTurnAck] is off, a one-word reply.
     * A decided photo question ([photoNote]) has its note with the photo instead of the note that asks for an answer
     * (its reply starts with a route tag).
     */
    private fun firstPartMessages(image: String?, audio: String?, turnContext: String?, photoNote: String? = null): List<Message> = buildList {
        // The turn context (the time) goes into this first turn: placed right before the recording in the second
        // turn, it made Gemma answer the time instead of the question (Device Run 2026-11-23, pt-BR read_sign).
        val parts = if (image != null) {
            listOfNotNull(
                turnContext?.takeIf { it.isNotBlank() }?.let { Content.Text(it) },
                Content.ImageFile(File(image).absolutePath),
                Content.Text(photoNote?.let { "$it $QUESTION_NEXT_NOTE" } ?: PHOTO_TURN_NOTE),
            )
        } else {
            listOfNotNull(
                turnContext?.takeIf { it.isNotBlank() }?.let { Content.Text(it) },
                Content.AudioFile(File(audio!!).absolutePath),
                Content.Text(QUESTION_BEFORE_PHOTO_NOTE),
            )
        }
        add(Message.user(Contents.of(parts)))
        if (photoTurnAck) add(Message.model("OK."))
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
        /** LiteRT stops the reply itself at this length (no cancel, so a session stays usable). */
        maxOutputTokens: Int? = null,
    ): String {
        val contents = userContents ?: Contents.of(Content.Text(prompt))
        val assembled = StringBuilder()
        conversation.streamTurn(contents, maxOutputToken = maxOutputTokens).collect { message ->
            val currentText = extractText(message)
            // Only skip empty chunks: a newline-only chunk ends a decision payload line ("[search] weather today\n").
            if (currentText.isEmpty()) return@collect
            val delta = incrementalDelta(assembled.toString(), currentText)
            if (delta.isNotEmpty()) {
                assembled.append(delta)
                onToken(delta)
            }
        }
        return assembled.toString()
    }

    private fun buildUserContents(config: GenerationConfig, prepared: PreparedPart? = null): Contents? {
        val skipImages = prepared?.imagePath != null
        val skipAudio = prepared?.audioPath != null
        val hasImage = config.imagePaths.isNotEmpty() && !skipImages
        val hasAudio = !config.audioPath.isNullOrBlank() && !skipAudio
        if (!hasImage && !hasAudio) {
            return config.turnContext?.takeIf { it.isNotBlank() }?.let { Contents.of(Content.Text("$it\n\n${config.prompt}")) }
        }
        val parts = ArrayList<Content>()
        // Context goes before the attachments (text after a recording reads as the question): the per-request
        // context (current time), then a session turn's note.
        config.turnContext?.takeIf { it.isNotBlank() && prepared == null }?.let { parts += Content.Text(it) }
        config.session?.note?.takeIf { it.isNotBlank() }?.let { parts += Content.Text(it) }
        config.imagePaths.takeUnless { skipImages }.orEmpty().forEach { rawPath ->
            val path = rawPath.trim()
            if (path.isBlank()) return@forEach
            val file = File(path)
            require(file.isFile && file.length() > 0) { "Image attachment is missing: $path" }
            parts += Content.ImageFile(file.absolutePath)
        }
        // A decided photo question: its note right after the photo, before the recording. (Its recording is never
        // prefilled first: ImageQuestionFlow decides on it alone then.)
        config.photoNote?.takeIf { it.isNotBlank() && hasImage && !skipAudio }?.let { parts += Content.Text(it) }
        config.audioPath?.trim()?.takeIf { it.isNotBlank() && !skipAudio }?.let { path ->
            val file = File(path)
            require(file.isFile && file.length() > 0) { "Audio attachment is missing: $path" }
            run {
                val ext = file.extension.lowercase(Locale.US)
                if (ext !in supportedAudioExtensions) {
                    throw IllegalArgumentException(
                        "Unsupported audio format '.$ext'. LiteRT-LM supports wav, mp3, and flac audio attachments.",
                    )
                }
                parts += Content.AudioFile(file.absolutePath)
            }
        }
        // The recording came first: this turn is the photo it asked about.
        if (skipAudio) parts += Content.Text(PHOTO_AFTER_QUESTION_NOTE)
        // A spoken question is the attachments alone; empty text adds nothing to the user turn.
        if (config.prompt.isNotBlank()) parts += Content.Text(config.prompt)
        return Contents.of(parts)
    }

    /**
     * [Conversation.sendMessageAsync] as a Flow whose cancellation also cancels the native turn (the library's
     * own Flow leaves the turn running when its collector stops), and which records the turn in [turns].
     */
    private fun Conversation.streamTurn(
        contents: Contents,
        maxOutputToken: Int? = null,
        responseFormat: ResponseFormat? = null,
    ): Flow<Message> = callbackFlow {
        val conversation = this@streamTurn
        val ended = CompletableDeferred<Unit>()
        turns[conversation] = ended
        val callback = object : MessageCallback {
            override fun onMessage(message: Message) {
                trySend(message)
            }

            override fun onDone() {
                ended.complete(Unit)
                close()
            }

            override fun onError(throwable: Throwable) {
                ended.complete(Unit)
                close(throwable)
            }
        }
        try {
            conversation.sendMessageAsync(
                contents, callback, emptyMap<String, Any>(), maxOutputToken = maxOutputToken, responseFormat = responseFormat,
            )
        } catch (t: Throwable) {
            ended.complete(Unit) // Rejected before anything was scheduled.
            throw t
        }
        awaitClose {
            if (!ended.isCompleted) runCatching { conversation.cancelProcess() }
        }
    }

    /**
     * Closes [conversation] once its native turn has ended, cancelling a running one first ([turns]). A turn
     * that does not end within [TURN_END_TIMEOUT_MS] leaves the conversation open: a leak instead of a crash.
     */
    private suspend fun closeWhenIdle(conversation: Conversation) = withContext(NonCancellable) {
        val turn = turns[conversation]
        if (turn != null && !turn.isCompleted) {
            Log.w(TAG, "Closing a LiteRT conversation whose turn is still running; cancelling it first")
            // Cancelling reaches only the tasks queued so far; a prefill that had just finished still queues
            // its decode, so repeat until the turn reports its end.
            val ended = withTimeoutOrNull(TURN_END_TIMEOUT_MS) {
                while (!turn.isCompleted) {
                    runCatching { conversation.cancelProcess() }
                    withTimeoutOrNull(TURN_CANCEL_RETRY_MS) { turn.await() }
                }
            }
            if (ended == null) {
                Log.e(TAG, "LiteRT turn did not end within $TURN_END_TIMEOUT_MS ms; leaving its conversation open")
                return@withContext
            }
        }
        turns.remove(conversation)
        runCatching { conversation.close() }
    }

    /**
     * A clone of the primed conversation for [config]'s system turn (priming it first when the turn changed), or
     * null to create a fresh conversation: no clone API, NPU backend, short system turn, or a clone error.
     */
    private suspend fun cloneOfPrimedRoot(llm: Engine, config: GenerationConfig): Conversation? {
        val system = config.systemInstruction?.trim().orEmpty()
        if (!promptCacheEnabled || !CONVERSATION_CLONE_AVAILABLE || !rootCacheUsable || system.length < MIN_CACHED_SYSTEM_CHARS) return null
        // Photo turns stay fresh: their system turn is short (the image encoder dominates), a second key would evict
        // the spoken-decision root on every [camera] question, and the host research saw wording drift in clones
        // after image turns. A decided photo question ([GenerationConfig.photoNote]) has the spoken questions' system
        // turn, so it shares their root.
        if (config.imagePaths.isNotEmpty() && config.photoNote == null) return null
        // CPU only: on the GPU every clone after the first restores the full KV cache from host memory (0.19.1,
        // b/572018960), which cost ~2 s per question on the S24 Ultra and Tab S10+ (PromptCacheRouteTest,
        // 2026-11-23: first token 1.2 s fresh vs 3.1-3.7 s cached); the NPU executor cannot clone at all.
        if (mutex.withLock { activeLoadResult?.activeBackend } != LocalComputeBackend.CPU) return null
        val key = rootKey(config)
        val root = mutex.withLock { primedRoot }?.takeIf { it.key == key } ?: run {
            mutex.withLock { closeRootLocked() }
            val started = System.currentTimeMillis()
            // Never clone an unprimed (step 0) conversation: 0.19.1 then reuses another conversation's KV.
            val primed = runCatching {
                withContext(Dispatchers.IO) {
                    llm.createConversation(buildConversationConfig(config, prefillPreface = true))
                }
            }.onFailure {
                Log.w(TAG, "System-prompt cache off for this load: priming failed (${compactError(it)})")
                rootCacheUsable = false
            }.getOrNull() ?: return null
            Log.i(TAG, "Primed the system turn (${system.length} chars) in ${System.currentTimeMillis() - started} ms")
            PrimedRoot(key, primed).also { mutex.withLock { primedRoot = it } }
        }
        return runCatching { withContext(Dispatchers.IO) { root.conversation.cloneOrNull() } }
            .onFailure {
                Log.w(TAG, "System-prompt cache off for this load: clone failed (${compactError(it)})")
                rootCacheUsable = false
                mutex.withLock { closeRootLocked() }
            }
            .getOrNull()
    }

    /** The primed turn is reusable only for the same system text, sampler and response-format mode. */
    private fun rootKey(config: GenerationConfig): String =
        listOf(
            config.systemInstruction?.trim().orEmpty(), config.topK, config.topP, config.temperature, config.seed,
            config.decideOnce != null,
        ).joinToString("\u0000")

    /**
     * Spoken questions (and photo questions decided with the same system turn) on the GPU/NPU (CPU uses the clone
     * cache): a system turn worth prefilling ahead of time.
     */
    private suspend fun spareEligible(config: GenerationConfig): Boolean =
        promptCacheEnabled && config.audioPath != null && (config.imagePaths.isEmpty() || config.photoNote != null) &&
            config.decideOnce == null &&
            config.systemInstruction.orEmpty().trim().length >= MIN_CACHED_SYSTEM_CHARS &&
            mutex.withLock { activeLoadResult?.activeBackend } != LocalComputeBackend.CPU

    /**
     * After a spoken turn: prime the spare now (the turn's conversation is closed), or, when the turn keeps a
     * conversation open for follow-ups, once that conversation has been idle past the follow-up window
     * (SpokenConversation.CONTINUE_WITHIN_MS): the next question then starts a new conversation anyway.
     */
    private suspend fun scheduleSpare(config: GenerationConfig, sessionConversation: Conversation?) {
        if (!spareEligible(config)) return
        spareBase = config.copy(prompt = "", audioPath = null, imagePaths = emptyList(), session = null, turnContext = null, photoNote = null)
        spareJob?.cancel()
        spareJob = spareScope.launch {
            if (sessionConversation != null) {
                kotlinx.coroutines.delay(com.fersaiyan.cyanbridge.shared.assistant.SpokenConversation.CONTINUE_WITHIN_MS + 5_000L)
            }
            runCatching { primeSpare(sessionConversation) }
                .onFailure { Log.w(TAG, "Spare system turn not primed: ${compactError(it)}") }
        }
    }

    /**
     * The system turn the next spoken or photo question will use changed (the Local Agent was set up or removed, so
     * [phone] joined or left the route list): prime [config]'s system turn now in the background, the CPU root or the
     * GPU/NPU spare. Runs after a request in flight, never inside it. On GPU/NPU with a kept conversation still open,
     * the spare scheduled for when that conversation lapses uses this system turn.
     */
    suspend fun primeSystemTurn(config: GenerationConfig) {
        val base = config.copy(prompt = "", audioPath = null, imagePaths = emptyList(), session = null, turnContext = null, photoNote = null)
        if (!promptCacheEnabled || base.systemInstruction.orEmpty().trim().length < MIN_CACHED_SYSTEM_CHARS) return
        val backend = mutex.withLock { activeLoadResult?.activeBackend.takeIf { engine != null } } ?: return
        if (backend == LocalComputeBackend.CPU) {
            operationMutex.withLock {
                val llm = mutex.withLock { engine } ?: return@withLock
                if (mutex.withLock { primedRoot?.key } == rootKey(base)) return@withLock
                // cloneOfPrimedRoot primes for a new key; the clone it returns is not needed.
                cloneOfPrimedRoot(llm, base)?.let { mutex.withLock { closeWhenIdle(it) } }
            }
            Log.i(TAG, "System turn re-primed (CPU root)")
            return
        }
        spareBase = base
        if (mutex.withLock { activeConversation != null }) return // the scheduled spare picks it up when it lapses
        spareJob?.cancel()
        spareJob = spareScope.launch {
            runCatching { primeSpare(null) }.onFailure { Log.w(TAG, "Spare system turn not primed: ${compactError(it)}") }
        }
    }

    private suspend fun primeSpare(expiredSession: Conversation?) = operationMutex.withLock {
        val config = spareBase ?: return@withLock
        val llm = mutex.withLock {
            val idle = if (expiredSession != null) {
                activeConversation === expiredSession && turns[expiredSession]?.isCompleted != false
            } else {
                activeConversation == null
            }
            // Anything else open (a newer conversation, a prepared photo, a primed root) would be copied out on switch.
            if (!idle || preparedPart != null || primedRoot != null) return@withLock null
            if (expiredSession == null && spare?.key == rootKey(config)) return@withLock null
            if (expiredSession != null) closeConversationLocked()
            closeSpareLocked()
            engine
        } ?: return@withLock
        val started = System.currentTimeMillis()
        val primed = withContext(Dispatchers.IO) { llm.createConversation(buildConversationConfig(config, prefillPreface = true)) }
        mutex.withLock {
            if (engine !== llm || activeConversation != null) {
                closeWhenIdle(primed)
            } else {
                spare = PrimedRoot(rootKey(config), primed)
                Log.i(TAG, "Spare system turn primed in ${System.currentTimeMillis() - started} ms")
            }
        }
    }

    /** The spare when [config] has its system turn and sampler; a non-matching spare is closed. */
    private suspend fun takeSpare(config: GenerationConfig): Conversation? = mutex.withLock {
        val current = spare ?: return@withLock null
        spare = null
        if (current.key == rootKey(config)) current.conversation else {
            closeWhenIdle(current.conversation)
            null
        }
    }

    private suspend fun closeSpareLocked() {
        spare?.let { closeWhenIdle(it.conversation) }
        spare = null
    }

    private suspend fun closeRootLocked() {
        primedRoot?.let { closeWhenIdle(it.conversation) }
        primedRoot = null
    }

    private suspend fun closeConversationLocked() {
        activeConversation?.let { closeWhenIdle(it) }
        activeConversation = null
        sessionKey = null
        sessionTokens = 0
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
        val running = synchronized(turns) { turns.values.count { !it.isCompleted } }
        if (running > 0) {
            // Deleting the engine under a running turn is no safer; keep it (and its memory) instead.
            Log.e(TAG, "Not closing the LiteRT engine: $running conversation turn(s) still running")
        } else {
            runCatching { engine?.close() }
        }
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
        /** Tests turn the system-prompt cache off to measure the fresh-conversation baseline (PromptCacheRouteTest). */
        @Volatile internal var promptCacheEnabled = true

        /** Prefilled photo turn layout: photo user turn followed by a short model turn (tests compare both). */
        @Volatile internal var photoTurnAck = true

        /** Text with a prefilled photo; the recording follows in the next user turn. */
        private const val PHOTO_TURN_NOTE = "Here is a photo from my glasses camera of what I'm looking at. My question about it comes next."
        /** Text after a prefilled recording; the photo follows in the next user turn. */
        private const val QUESTION_BEFORE_PHOTO_NOTE = "A photo from my glasses camera of what I'm looking at comes next."
        /** The photo turn after a prefilled recording. */
        private const val PHOTO_AFTER_QUESTION_NOTE = "Here is the photo. Answer my question now."
        /** After a decided photo question's prefilled photo and its note: the recording follows. */
        private const val QUESTION_NEXT_NOTE = "My question comes next."

        /** Shorter system turns (a plain text chat) are cheap to prefill; caching them is not worth a primed context. */
        private const val MIN_CACHED_SYSTEM_CHARS = 400
        private const val TAG = "LiteRtLocalEngine"
        private const val FIRST_AUDIO_RETRY_DELAY_MS = 750L
        /** A cancelled turn ends within a prefill chunk or a decode step; this only bounds a wedged runtime. */
        private const val TURN_END_TIMEOUT_MS = 30_000L
        private const val TURN_CANCEL_RETRY_MS = 250L
        private const val DEFAULT_CONTEXT_TOKENS = 4096
        /** Room left for the reply when deciding whether a session turn still fits the context. */
        private const val SESSION_REPLY_RESERVE_TOKENS = 512
        private const val AUDIO_TOKENS_PER_SECOND = 25
        private const val IMAGE_TOKENS = 280
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
