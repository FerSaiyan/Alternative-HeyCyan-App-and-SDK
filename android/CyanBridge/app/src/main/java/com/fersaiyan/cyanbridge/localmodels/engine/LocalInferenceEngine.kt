package com.fersaiyan.cyanbridge.localmodels.engine

import com.fersaiyan.cyanbridge.localmodels.settings.LocalComputeBackend

data class EngineLoadConfig(
    val contextSize: Int,
    val cpuThreads: Int,
    val computeBackend: LocalComputeBackend,
    val gpuLayers: Int,
    /** null uses the runtime/model default; true/false explicitly controls MTP/speculative decode. */
    val speculativeDecoding: Boolean? = null,
)

data class EngineLoadResult(
    val activeBackend: LocalComputeBackend,
    val activeGpuLayers: Int,
    val fallbackReason: String? = null,
    val speculativeDecodingEnabled: Boolean? = null,
)

data class GenerationConfig(
    val prompt: String,
    val temperature: Double,
    val topP: Double,
    val topK: Int,
    val maxTokens: Int,
    val repetitionPenalty: Double,
    val seed: Int,
    val structuredJson: Boolean,
    val imagePaths: List<String> = emptyList(),
    val audioPath: String? = null,
)

data class GenerationResult(
    val text: String,
    val tokenCount: Int,
    val cappedByMaxTokens: Boolean = false,
)

interface LocalInferenceEngine {
    suspend fun loadModel(modelPath: String, config: EngineLoadConfig): EngineLoadResult
    suspend fun unloadModel()
    suspend fun generate(config: GenerationConfig, onToken: (String) -> Unit): GenerationResult
    suspend fun cancelGeneration()
    suspend fun tokenizeCount(text: String): Int
    fun isModelLoaded(): Boolean
    fun loadedModelPath(): String?
}
