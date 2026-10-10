package com.fersaiyan.cyanbridge.localmodels.engine

import com.fersaiyan.cyanbridge.ai.decision.DecisionLogits
import com.fersaiyan.cyanbridge.ai.decision.NextTokenDecisionRuntime
import com.fersaiyan.cyanbridge.llama.SharedGemmaBridge
import com.fersaiyan.cyanbridge.localmodels.settings.LocalComputeBackend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext

/** One native GGUF/context serves both text chat and shadow decision logits. */
class SharedGemmaInferenceEngine : LocalInferenceEngine, NextTokenDecisionRuntime {
    private val lifecycle = Mutex()
    @Volatile private var handle = 0L
    @Volatile private var modelPath: String? = null
    private var loadConfig: EngineLoadConfig? = null
    private var loadResult: EngineLoadResult? = null

    override suspend fun loadModel(modelPath: String, config: EngineLoadConfig): EngineLoadResult = lifecycle.withLock {
        withContext(Dispatchers.IO) {
            if (handle != 0L && this@SharedGemmaInferenceEngine.modelPath == modelPath && loadConfig == config) return@withContext loadResult!!
            require(File(modelPath).isFile) { "Model file does not exist" }
            release()
            handle = SharedGemmaBridge.load(modelPath, config.contextSize, config.cpuThreads)
            this@SharedGemmaInferenceEngine.modelPath = modelPath
            loadConfig = config
            EngineLoadResult(
                activeBackend = LocalComputeBackend.CPU, activeGpuLayers = 0,
                fallbackReason = if (config.computeBackend != LocalComputeBackend.CPU) "Shared-Gemma prototype uses CPU execution." else null,
            ).also { loadResult = it }
        }
    }

    override suspend fun unloadModel() = lifecycle.withLock { withContext(Dispatchers.IO) { release() } }
    private fun release() {
        val id = handle; handle = 0L
        if (id != 0L) SharedGemmaBridge.release(id)
        modelPath = null; loadConfig = null; loadResult = null
    }

    override suspend fun score(userPrompt: String, labels: List<String>): DecisionLogits = lifecycle.withLock {
        withContext(Dispatchers.IO) {
            check(handle != 0L) { "No chat model loaded" }
            val values = SharedGemmaBridge.decide(handle, userPrompt.toByteArray(Charsets.UTF_8), labels.toTypedArray())
            coroutineContext.ensureActive()
            check(values.size == labels.size + 1) { "Unexpected native logit shape" }
            DecisionLogits(values.copyOfRange(0, labels.size), values.last())
        }
    }

    override suspend fun generate(config: GenerationConfig, onToken: (String) -> Unit): GenerationResult = lifecycle.withLock {
        withContext(Dispatchers.IO) {
            check(handle != 0L) { "No model loaded" }
            require(config.imagePaths.isEmpty() && config.audioPath.isNullOrBlank()) { "Shared-Gemma prototype currently accepts text only" }
            val decoder = Utf8TokenDecoder()
            val stats = IntArray(2)
            val generationContext = coroutineContext
            val result = SharedGemmaBridge.generate(
                handle, config.prompt.toByteArray(Charsets.UTF_8), config.maxTokens, config.temperature.toFloat(),
                config.topP.toFloat(), config.topK, config.repetitionPenalty.toFloat(), config.seed,
                if (config.structuredJson) JSON_OBJECT_GRAMMAR else null,
                SharedGemmaBridge.TokenSink { bytes ->
                    generationContext.ensureActive()
                    decoder.offer(bytes).takeIf { it.isNotEmpty() }?.let(onToken)
                }, stats,
            )
            coroutineContext.ensureActive()
            decoder.finish().takeIf { it.isNotEmpty() }?.let(onToken)
            GenerationResult(String(result, Charsets.UTF_8), stats[0], stats[1] != 0)
        }
    }

    override suspend fun cancelGeneration() { handle.takeIf { it != 0L }?.let(SharedGemmaBridge::cancel) }
    override suspend fun tokenizeCount(text: String): Int = lifecycle.withLock {
        withContext(Dispatchers.IO) {
            handle.takeIf { it != 0L }?.let { SharedGemmaBridge.tokenCount(it, text.toByteArray(Charsets.UTF_8)) } ?: text.length / 4
        }
    }
    override fun isModelLoaded() = handle != 0L
    override fun loadedModelPath() = modelPath

    companion object {
        private val JSON_OBJECT_GRAMMAR = """
            root ::= object
            object ::= "{" ws (pair (ws "," ws pair)*)? ws "}"
            pair ::= string ws ":" ws value
            value ::= string | number | object | array | "true" | "false" | "null"
            array ::= "[" ws (value (ws "," ws value)*)? ws "]"
            string ::= "\"" ([^"\\\x00-\x1F] | "\\" (["\\/bfnrt] | "u" [0-9a-fA-F]{4}))* "\""
            number ::= "-"? ("0" | [1-9] [0-9]*) ("." [0-9]+)? ([eE] [+-]? [0-9]+)?
            ws ::= [ \t\n\r]*
        """.trimIndent()
    }
}
