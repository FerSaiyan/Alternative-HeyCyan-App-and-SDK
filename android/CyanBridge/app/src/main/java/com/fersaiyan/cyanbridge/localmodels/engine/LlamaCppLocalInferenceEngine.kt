package com.fersaiyan.cyanbridge.localmodels.engine

import android.util.Log
import com.fersaiyan.cyanbridge.llama.UpstreamLlamaBridge
import com.fersaiyan.cyanbridge.localmodels.settings.LocalComputeBackend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext

class LlamaCppLocalInferenceEngine : LocalInferenceEngine {
    @Volatile private var handle = 0L
    private var modelPath: String? = null
    private var loadedConfig: EngineLoadConfig? = null
    private var loadResult: EngineLoadResult? = null

    override suspend fun loadModel(modelPath: String, config: EngineLoadConfig): EngineLoadResult = withContext(Dispatchers.IO) {
        if (handle != 0L && this@LlamaCppLocalInferenceEngine.modelPath == modelPath && loadedConfig == config) return@withContext loadResult!!
        require(File(modelPath).isFile) { "Model file does not exist" }
        config.projectorPath?.let { require(File(it).isFile) { "Projector file does not exist" } }
        unloadModel()
        handle = UpstreamLlamaBridge.load(modelPath, config.projectorPath, config.contextSize, config.cpuThreads, false)
        this@LlamaCppLocalInferenceEngine.modelPath = modelPath
        loadedConfig = config
        // The former wrapper was CPU-only too. Report the backend actually compiled into this bridge.
        val result = EngineLoadResult(
            activeBackend = LocalComputeBackend.CPU, activeGpuLayers = 0,
            fallbackReason = if (config.computeBackend != LocalComputeBackend.CPU) "Upstream llama.cpp Android build uses CPU execution." else null,
        )
        loadResult = result
        Log.i(TAG, "Loaded upstream=${UpstreamLlamaBridge.REVISION} mediaCaps=${UpstreamLlamaBridge.capabilities(handle)} projector=${config.projectorPath}")
        result
    }

    override suspend fun unloadModel() = withContext(Dispatchers.IO) {
        val old = handle
        handle = 0
        if (old != 0L) UpstreamLlamaBridge.release(old)
        modelPath = null
        loadedConfig = null
        loadResult = null
    }

    override suspend fun generate(config: GenerationConfig, onToken: (String) -> Unit): GenerationResult = withContext(Dispatchers.IO) {
        val id = handle
        check(id != 0L) { "No model loaded" }
        val caps = UpstreamLlamaBridge.capabilities(id)
        if (config.imagePaths.isNotEmpty()) check(caps and UpstreamLlamaBridge.VISION != 0) { "Selected GGUF/projector does not support images" }
        if (!config.audioPath.isNullOrBlank()) check(caps and UpstreamLlamaBridge.AUDIO != 0) { "Selected GGUF/projector does not support audio" }
        (config.imagePaths + listOfNotNull(config.audioPath)).forEach { require(File(it).isFile) { "Attachment is missing: $it" } }
        val decoder = Utf8TokenDecoder()
        val stats = IntArray(2)
        val generationContext = coroutineContext
        val bytes = UpstreamLlamaBridge.generate(
            id, config.prompt.toByteArray(Charsets.UTF_8), config.imagePaths.toTypedArray(), config.audioPath,
            config.maxTokens, config.temperature.toFloat(), config.topP.toFloat(), config.topK,
            config.repetitionPenalty.toFloat(), config.seed, if (config.structuredJson) JSON_OBJECT_GRAMMAR else null,
            UpstreamLlamaBridge.TokenSink { chunk ->
                generationContext.ensureActive()
                decoder.offer(chunk).takeIf { it.isNotEmpty() }?.let(onToken)
            }, stats,
        )
        coroutineContext.ensureActive()
        decoder.finish().takeIf { it.isNotEmpty() }?.let(onToken)
        GenerationResult(String(bytes, Charsets.UTF_8), stats[0], cappedByMaxTokens = stats[1] != 0)
    }

    override suspend fun cancelGeneration() { handle.takeIf { it != 0L }?.let(UpstreamLlamaBridge::cancel) }
    override suspend fun tokenizeCount(text: String): Int = withContext(Dispatchers.IO) {
        handle.takeIf { it != 0L }?.let { UpstreamLlamaBridge.tokenCount(it, text.toByteArray(Charsets.UTF_8)) }
            ?: text.length / 4
    }
    override fun isModelLoaded(): Boolean = handle != 0L
    override fun loadedModelPath(): String? = modelPath

    companion object {
        private const val TAG = "LlamaCppLocalEngine"
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
