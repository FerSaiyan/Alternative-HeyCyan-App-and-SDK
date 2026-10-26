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
import com.google.ai.edge.litertlm.ResponseFormat
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ToolProvider
import com.google.ai.edge.litertlm.ThinkingConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
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
    /** False while the engine has an audio encoder that has not run yet (see [primeAudioEncoder]). */
    @Volatile private var audioEncoderReady = true
    /** False until an audio request succeeds on the loaded engine (see [generate]'s retry). */
    @Volatile private var audioSucceededSinceLoad = false
    /** Key of the [SessionTurn] conversation kept open in [activeConversation], or null. */
    private var sessionKey: String? = null
    /** Estimated tokens in the open session's KV cache (system turn, every user turn and reply). */
    private var sessionTokens = 0
    /** Set by [cancelGeneration] (logged when the stopped turn fails). */
    @Volatile private var cancelRequested = false

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
        val npuBackend = Backend.NPU(nativeLibraryDir = context.applicationInfo.nativeLibraryDir)
        // A package compiled for one SoC's NPU cannot run on GPU/CPU, so report the NPU error.
        val npuOnly = LiteRtNpuSupport.compiledFor(modelPath) != null
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
        val hasAudio = !config.audioPath.isNullOrBlank()
        val session = config.session
        val turnTokens = estimateTurnTokens(config)
        val conversation: Conversation
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
            if (!audioEncoderReady && !hasAudio && config.imagePaths.isNotEmpty()) primeAudioEncoder(llm)
            conversation = withContext(Dispatchers.IO) {
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
                val userContents = buildUserContents(config)
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
            mutex.withLock {
                if (activeConversation === conversation) {
                    if (keepSession) {
                        sessionTokens = runCatching { conversation.getTokenCount() }.getOrDefault(sessionTokens + turnTokens + replyTokens)
                        Log.i(TAG, "Session '${session?.key}' open, $sessionTokens tokens")
                    } else {
                        closeConversationLocked()
                    }
                }
            }
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
    private suspend fun primeAudioEncoder(llm: Engine) = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        runCatching {
            val silence = File(context.cacheDir, "litert_audio_prime.wav")
            if (!silence.isFile) silence.writeBytes(silentWav(samples = 8_000))
            llm.createConversation(ConversationConfig()).use { primer ->
                primer.sendMessageAsync(
                    Contents.of(listOf(Content.AudioFile(silence.absolutePath), Content.Text("."))),
                    emptyMap<String, Any>(),
                ).take(1).collect()
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
        conversation.sendMessageAsync(
            Contents.of(first),
            emptyMap<String, Any>(),
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
        conversation.sendMessageAsync(Contents.of(first), emptyMap<String, Any>()).collect { message ->
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

    private fun buildConversationConfig(config: GenerationConfig): ConversationConfig {
        val sampler = SamplerConfig(
            topK = config.topK.coerceIn(1, 200),
            topP = config.topP.coerceIn(0.0, 1.0),
            temperature = config.temperature.coerceIn(0.0, 2.0),
            seed = config.seed,
        )
        return ConversationConfig(
            systemInstruction = Contents.of(config.systemInstruction?.trim().orEmpty()),
            initialMessages = emptyList<Message>(),
            tools = emptyList<ToolProvider>(),
            samplerConfig = sampler,
            automaticToolCalling = true,
            enableResponseFormat = config.decideOnce != null,
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
        /** LiteRT stops the reply itself at this length (no cancel, so a session stays usable). */
        maxOutputTokens: Int? = null,
    ): String {
        val contents = userContents ?: Contents.of(Content.Text(prompt))
        val assembled = StringBuilder()
        conversation.sendMessageAsync(contents, emptyMap<String, Any>(), maxOutputToken = maxOutputTokens).collect { message ->
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

    private fun buildUserContents(config: GenerationConfig): Contents? {
        val hasImage = config.imagePaths.isNotEmpty()
        val hasAudio = !config.audioPath.isNullOrBlank()
        if (!hasImage && !hasAudio) return null
        val parts = ArrayList<Content>()
        // Context for a session turn goes before the attachments (text after a recording reads as the question).
        config.session?.note?.takeIf { it.isNotBlank() }?.let { parts += Content.Text(it) }
        config.imagePaths.forEach { rawPath ->
            val path = rawPath.trim()
            if (path.isBlank()) return@forEach
            val file = File(path)
            require(file.isFile && file.length() > 0) { "Image attachment is missing: $path" }
            parts += Content.ImageFile(file.absolutePath)
        }
        config.audioPath?.trim()?.takeIf { it.isNotBlank() }?.let { path ->
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
        // A spoken question is the attachments alone; empty text adds nothing to the user turn.
        if (config.prompt.isNotBlank()) parts += Content.Text(config.prompt)
        return Contents.of(parts)
    }

    private fun closeConversationLocked() {
        runCatching { activeConversation?.close() }
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
        private const val FIRST_AUDIO_RETRY_DELAY_MS = 750L
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
