package com.fersaiyan.cyanbridge.localmodels.engine
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.nehuatl.llamacpp.LlamaAndroid
import com.fersaiyan.cyanbridge.localmodels.settings.LocalComputeBackend
import com.fersaiyan.cyanbridge.ui.MyApplication
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

class LlamaCppLocalInferenceEngine : LocalInferenceEngine {
    private val tokenCounter = AtomicInteger(0)
    private val appContext = MyApplication.CONTEXT
    private val tokenCallbackLock = Any()
    private val eventScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var llama: LlamaAndroid? = null
    private var contextId: Int? = null
    private var modelPath: String? = null
    private var activeLoadConfig: EngineLoadConfig? = null
    private var activeLoadResult: EngineLoadResult? = null
    private var tokenCollector: ((String) -> Unit)? = null
    private var eventCollectorJob: Job? = null

    override suspend fun loadModel(modelPath: String, config: EngineLoadConfig): EngineLoadResult {
        return withContext(Dispatchers.IO) {
            Log.i(
                TAG,
                "loadModel path=$modelPath backend=${config.computeBackend} cpuThreads=${config.cpuThreads} context=${config.contextSize} gpuLayers=${config.gpuLayers}",
            )
            if (
                this@LlamaCppLocalInferenceEngine.modelPath == modelPath &&
                contextId != null &&
                activeLoadConfig == config
            ) {
                Log.i(TAG, "Reusing existing llama.cpp context for $modelPath")
                return@withContext activeLoadResult ?: EngineLoadResult(
                    activeBackend = config.computeBackend,
                    activeGpuLayers = if (config.computeBackend == LocalComputeBackend.GPU_EXPERIMENTAL) {
                        normalizeGpuLayerSetting(config.gpuLayers)
                    } else {
                        0
                    },
                )
            }

            unloadModel()

            val engine = llama ?: createLlamaAndroid().also { llama = it }
            val runtimeApi = detectRuntimeApi(engine)
            Log.i(TAG, "Detected llama runtime API: $runtimeApi")
            val file = File(modelPath)
            require(file.exists()) { "Model file does not exist" }
            val modelUri = Uri.fromFile(file).toString()

            val createInitParams: (Int) -> Map<String, Any> = { gpuLayers ->
                val safeBatch = (config.contextSize / 8).coerceIn(32, 128)
                val params = mutableMapOf<String, Any>(
                    "model" to if (runtimeApi == LlamaRuntimeApi.V2) modelUri else file.absolutePath,
                    "n_ctx" to config.contextSize,
                    "n_batch" to safeBatch,
                    "n_threads" to config.cpuThreads,
                    "n_gpu_layers" to gpuLayers,
                    "use_mmap" to true,
                    "use_mlock" to false,
                )
                if (runtimeApi == LlamaRuntimeApi.V2) {
                    params["model_fd"] = openModelFd(file)
                }
                params
            }

            val (result, loadResult) = if (config.computeBackend == LocalComputeBackend.GPU_EXPERIMENTAL) {
                val requestedGpuLayers = normalizeGpuLayerSetting(config.gpuLayers)
                val attempts = gpuLayerAttempts(requestedGpuLayers)
                var selectedGpuLayers: Int? = null
                var selectedResult: Map<String, Any>? = null
                val failures = mutableListOf<String>()

                for (candidateLayers in attempts) {
                    Log.i(TAG, "Trying llama GPU init with n_gpu_layers=$candidateLayers")
                    runCatching {
                        initializeContext(engine, runtimeApi, createInitParams(candidateLayers))
                    }.onSuccess { init ->
                        selectedGpuLayers = candidateLayers
                        selectedResult = init
                    }.onFailure { err ->
                        Log.w(TAG, "llama GPU init failed for n_gpu_layers=$candidateLayers: ${compactError(err)}", err)
                        failures += "n_gpu_layers=$candidateLayers -> ${compactError(err)}"
                    }
                    if (selectedResult != null) break
                }

                val gpuResult = selectedResult
                val gpuLayers = selectedGpuLayers
                if (gpuResult != null && gpuLayers != null) {
                    gpuResult to EngineLoadResult(
                        activeBackend = LocalComputeBackend.GPU_EXPERIMENTAL,
                        activeGpuLayers = gpuLayers,
                    )
                } else {
                    val cpuInit = initializeContext(engine, runtimeApi, createInitParams(0))
                    cpuInit to EngineLoadResult(
                        activeBackend = LocalComputeBackend.CPU,
                        activeGpuLayers = 0,
                        fallbackReason = buildGpuFallbackReason(requestedGpuLayers, failures),
                    )
                }
            } else {
                initializeContext(engine, runtimeApi, createInitParams(0)) to EngineLoadResult(
                    activeBackend = LocalComputeBackend.CPU,
                    activeGpuLayers = 0,
                )
            }

            val newContextId = (result["contextId"] as? Number)?.toInt()
                ?: throw IllegalStateException("llama.cpp context id missing")
            setupLegacyEventCollector(engine, runtimeApi, newContextId)
            Log.i(
                TAG,
                "llama.cpp model ready path=$modelPath contextId=$newContextId backend=${loadResult.activeBackend} fallback=${loadResult.fallbackReason}",
            )

            this@LlamaCppLocalInferenceEngine.contextId = newContextId
            this@LlamaCppLocalInferenceEngine.modelPath = modelPath
            this@LlamaCppLocalInferenceEngine.activeLoadConfig = config
            this@LlamaCppLocalInferenceEngine.activeLoadResult = loadResult
            return@withContext loadResult
        }
    }

    override suspend fun unloadModel() {
        withContext(Dispatchers.IO) {
            Log.i(TAG, "Unloading llama.cpp model ${modelPath.orEmpty()} ctx=${contextId ?: -1}")
            val engine = llama
            val ctx = contextId
            if (ctx != null) {
                runCatching {
                    eventCollectorJob?.cancel()
                    eventCollectorJob = null
                    if (engine != null) invokeLegacyUnsetEventCollector(engine, ctx)
                }
                runCatching { engine?.releaseContext(ctx) }
            }
            contextId = null
            modelPath = null
            activeLoadConfig = null
            activeLoadResult = null
        }
    }

    override suspend fun generate(config: GenerationConfig, onToken: (String) -> Unit): GenerationResult {
        Log.i(
            TAG,
            "generate promptChars=${config.prompt.length} maxTokens=${config.maxTokens} temperature=${config.temperature}",
        )
        val engine = llama ?: throw IllegalStateException("Inference engine is not initialized")
        val ctx = contextId ?: throw IllegalStateException("No model loaded")

        tokenCounter.set(0)
        val tokenBuilder = StringBuilder()
        synchronized(tokenCallbackLock) {
            tokenCollector = { token ->
                tokenCounter.incrementAndGet()
                tokenBuilder.append(token)
                onToken(token)
            }
        }

        val params = mutableMapOf<String, Any>(
            "prompt" to config.prompt,
            "emit_partial_completion" to true,
            "temperature" to config.temperature,
            "top_p" to config.topP,
            "top_k" to config.topK,
            "n_predict" to config.maxTokens,
            "penalty_repeat" to config.repetitionPenalty,
            "seed" to config.seed,
            "stop" to listOf("<|im_end|>", "<end_of_turn>"),
        )
        if (config.structuredJson) {
            params["grammar"] = JSON_OBJECT_GRAMMAR
        }

        val result = try {
            withContext(Dispatchers.IO) {
                engine.launchCompletion(ctx, params)
            } ?: emptyMap()
        } catch (t: Throwable) {
            Log.e(TAG, "llama.cpp generation failed", t)
            throw t
        } finally {
            synchronized(tokenCallbackLock) {
                tokenCollector = null
            }
        }

        val streamedText = tokenBuilder.toString()
        val resultText = (result["text"] as? String).orEmpty()
        val text = when {
            streamedText.isNotBlank() -> streamedText
            resultText.isNotBlank() -> resultText
            else -> ""
        }

        return GenerationResult(
            text = text,
            tokenCount = tokenCounter.get(),
        )
    }

    override suspend fun cancelGeneration() {
        withContext(Dispatchers.IO) {
            val engine = llama ?: return@withContext
            val ctx = contextId ?: return@withContext
            Log.i(TAG, "Cancelling llama.cpp generation ctx=$ctx")
            runCatching { engine.stopCompletion(ctx) }
        }
    }

    override suspend fun tokenizeCount(text: String): Int {
        return withContext(Dispatchers.IO) {
            val engine = llama ?: return@withContext roughTokenEstimate(text)
            val ctx = contextId ?: return@withContext roughTokenEstimate(text)
            val result = runCatching {
                engine.tokenize(ctx, text).firstOrNull()
            }.getOrNull()
            val tokens = result?.get("tokens")
            if (tokens is List<*>) {
                tokens.size
            } else {
                roughTokenEstimate(text)
            }
        }
    }

    override fun isModelLoaded(): Boolean = contextId != null

    override fun loadedModelPath(): String? = modelPath

    private fun roughTokenEstimate(text: String): Int {
        val words = text.trim().split(Regex("\\s+")).filter { it.isNotBlank() }.size
        return (words * 1.5).toInt().coerceAtLeast(1)
    }

    private fun initializeContext(
        engine: LlamaAndroid,
        runtimeApi: LlamaRuntimeApi,
        params: Map<String, Any>,
    ): Map<String, Any> {
        Log.i(TAG, "Initializing llama context runtime=$runtimeApi params=${params.keys}")
        val modelFd = params["model_fd"] as? Int
        val result: Any? = try {
            runCatching {
                startEngineCompat(engine, runtimeApi, params)
            }.getOrElse { error ->
                if (error is UnsatisfiedLinkError) {
                    throw IllegalStateException(
                        "Local model runtime is unavailable on this build/device ABI. " +
                            "Reinstall the latest app build and verify the device is 64-bit (arm64-v8a or x86_64).",
                        error,
                    )
                }
                throw error
            }
        } finally {
            if (modelFd != null) {
                runCatching { ParcelFileDescriptor.adoptFd(modelFd).close() }
            }
        }
        if (result == null) {
            throw IllegalStateException(buildContextInitError(engine, runtimeApi, params))
        }
        @Suppress("UNCHECKED_CAST")
        return result as? Map<String, Any>
            ?: throw IllegalStateException("Invalid llama.cpp initialization result")
    }

    private fun openModelFd(file: File): Int {
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            ?: throw IllegalStateException("Unable to open model file descriptor")
        return pfd.detachFd()
    }

    private fun createLlamaAndroid(): LlamaAndroid {
        val clazz = LlamaAndroid::class.java
        return runCatching {
            clazz.getConstructor(android.content.ContentResolver::class.java)
                .newInstance(appContext.contentResolver)
        }.getOrElse {
            clazz.getConstructor().newInstance()
        }
    }

    private fun detectRuntimeApi(engine: LlamaAndroid): LlamaRuntimeApi {
        val hasV2 = engine.javaClass.methods.any { it.name == "startEngine" && it.parameterTypes.size == 2 }
        return if (hasV2) LlamaRuntimeApi.V2 else LlamaRuntimeApi.V1
    }

    private fun startEngineCompat(
        engine: LlamaAndroid,
        runtimeApi: LlamaRuntimeApi,
        params: Map<String, Any>,
    ): Any? {
        return if (runtimeApi == LlamaRuntimeApi.V2) {
            val startEngine = engine.javaClass.methods.first { it.name == "startEngine" && it.parameterTypes.size == 2 }
            val callback = { token: String ->
                synchronized(tokenCallbackLock) {
                    tokenCollector?.invoke(token)
                }
            }
            startEngine.invoke(engine, params, callback)
        } else {
            val initContext = engine.javaClass.methods.first { it.name == "initContext" && it.parameterTypes.size == 1 }
            initContext.invoke(engine, params)
        }
    }

    private fun isGgufCompat(engine: LlamaAndroid, uri: Uri): Boolean? {
        val checker = engine.javaClass.methods.firstOrNull { it.name == "isGGUF" && it.parameterTypes.size == 1 }
            ?: return null
        return runCatching { checker.invoke(engine, uri) as? Boolean }.getOrNull()
    }

    private fun buildContextInitError(
        engine: LlamaAndroid,
        runtimeApi: LlamaRuntimeApi,
        params: Map<String, Any>,
    ): String {
        val hints = mutableListOf<String>()
        val model = params["model"] as? String
        val modelUri = model?.let { runCatching { Uri.parse(it) }.getOrNull() }
        if (model.isNullOrBlank()) {
            hints += "missing model URI"
        } else if (runtimeApi == LlamaRuntimeApi.V2) {
            val scheme = modelUri?.scheme.orEmpty()
            if (scheme != "file" && scheme != "content") {
                hints += "model must be file:// or content:// URI"
            }
            val ggufOk = modelUri?.let { uri -> isGgufCompat(engine, uri) }
            if (ggufOk == false) {
                hints += "runtime GGUF header check failed"
            }
        }

        if (runtimeApi == LlamaRuntimeApi.V2 && params["model_fd"] !is Int) {
            hints += "missing readable model file descriptor"
        }

        return if (hints.isEmpty()) {
            "Failed to initialize local llama context"
        } else {
            "Failed to initialize local llama context (${hints.joinToString(", ")})"
        }
    }

    private fun normalizeGpuLayerSetting(raw: Int): Int {
        return if (raw < 0) -1 else raw.coerceAtLeast(1)
    }

    private fun gpuLayerAttempts(requestedGpuLayers: Int): List<Int> {
        if (requestedGpuLayers == -1) {
            return listOf(-1, 64, 48, 32, 24, 16, 12, 8, 4, 2, 1)
        }

        val attempts = mutableListOf(requestedGpuLayers.coerceAtLeast(1))
        var next = attempts.first()
        while (next > 1) {
            next /= 2
            if (next >= 1) attempts += next
        }
        attempts += listOf(24, 16, 12, 8, 4, 2, 1)
        return attempts.distinct()
    }

    private fun compactError(err: Throwable?): String {
        if (err == null) return "unknown error"
        val msg = err.message
            ?.replace('\n', ' ')
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
        return if (msg.isNullOrEmpty()) err::class.java.simpleName else msg
    }

    private fun buildGpuFallbackReason(requestedGpuLayers: Int, failures: List<String>): String {
        val requestedText = if (requestedGpuLayers == -1) {
            "auto (-1)"
        } else {
            requestedGpuLayers.toString()
        }
        val details = failures.take(4).joinToString(" | ")
        val overflow = (failures.size - 4).coerceAtLeast(0)
        val suffix = if (overflow > 0) " | +$overflow more attempts" else ""
        return if (details.isBlank()) {
            "GPU initialization failed for n_gpu_layers=$requestedText. Fell back to CPU."
        } else {
            "GPU initialization failed for n_gpu_layers=$requestedText ($details$suffix). Fell back to CPU."
        }
    }

    private fun setupLegacyEventCollector(engine: LlamaAndroid, runtimeApi: LlamaRuntimeApi, ctx: Int) {
        if (runtimeApi != LlamaRuntimeApi.V1) return

        runCatching {
            eventCollectorJob?.cancel()

            val setCollectorMethod = engine.javaClass.methods.firstOrNull {
                it.name == "setEventCollector" && it.parameterTypes.size == 2
            } ?: return

            val eventFlowAny = setCollectorMethod.invoke(engine, ctx, eventScope) ?: return
            @Suppress("UNCHECKED_CAST")
            val eventFlow = eventFlowAny as? Flow<Any?> ?: return

            eventCollectorJob = eventScope.launch {
                eventFlow.collect { event ->
                    val pair = event as? Pair<*, *> ?: return@collect
                    if (pair.first != "token" && pair.first != "tokenResult") return@collect
                    val payload = pair.second
                    val token = when (payload) {
                        is Map<*, *> -> payload["token"] as? String
                        is String -> payload
                        else -> null
                    }
                    if (!token.isNullOrEmpty()) {
                        synchronized(tokenCallbackLock) {
                            tokenCollector?.invoke(token)
                        }
                    }
                }
            }
        }
    }

    private fun invokeLegacyUnsetEventCollector(engine: LlamaAndroid, ctx: Int) {
        val unsetCollectorMethod = engine.javaClass.methods.firstOrNull {
            it.name == "unsetEventCollector" && it.parameterTypes.size == 1
        } ?: return
        unsetCollectorMethod.invoke(engine, ctx)
    }

    private enum class LlamaRuntimeApi {
        V1,
        V2,
    }

    private companion object {
        const val TAG = "LlamaCppLocalEngine"
        const val JSON_OBJECT_GRAMMAR = "root ::= object\\nobject ::= \"{\" ws pair (ws \",\" ws pair)* ws \"}\"\\npair ::= string ws \":\" ws value\\nvalue ::= string | number | object | array | \"true\" | \"false\" | \"null\"\\narray ::= \"[\" ws (value (ws \",\" ws value)*)? ws \"]\"\\nstring ::= \"\\\"\" chars \"\\\"\"\\nchars ::= ([^\\\"\\\\]|\\\\[\\\"\\\\/bfnrt]|\\\\u[0-9a-fA-F]{4})*\\nnumber ::= -?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?\\nws ::= [ \\t\\n\\r]*"
    }
}
