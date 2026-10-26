package com.fersaiyan.cyanbridge.localmodels.engine

import com.fersaiyan.cyanbridge.localmodels.settings.LocalComputeBackend

data class EngineLoadConfig(
    val contextSize: Int,
    val cpuThreads: Int,
    val computeBackend: LocalComputeBackend,
    val gpuLayers: Int,
    /** null uses the runtime/model default; true/false explicitly controls MTP/speculative decode. */
    val speculativeDecoding: Boolean? = null,
    val projectorPath: String? = null,
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
    /** System turn for runtimes that apply the model's own chat template (LiteRT-LM). */
    val systemInstruction: String? = null,
    /** Prototype decide-once turn; only engines that keep a conversation across turns honour it. */
    val decideOnce: DecideOnceRequest? = null,
    /** Keeps the conversation open for later turns (LiteRT-LM only); see [SessionTurn]. */
    val session: SessionTurn? = null,
)

/**
 * One turn of a multi-turn conversation kept open in the engine, so earlier turns (audio, photos) stay in
 * its KV cache and are not prefilled again. [continueExisting] = false starts a new conversation (the
 * config's system instruction) and keeps it open under [key]; true adds this turn to the open
 * conversation with that key, or throws [SessionUnavailableException] when there is none (another
 * request used the engine, the model reloaded) or the turn would not fit the context. Callers then start
 * a new session or fall back to a self-contained request. [note] is text placed before this turn's
 * attachments, e.g. what the app said in the previous turn without the model (a web answer read out).
 */
data class SessionTurn(val key: String, val continueExisting: Boolean, val note: String? = null)

class SessionUnavailableException(message: String) : IllegalStateException(message)

data class GenerationResult(
    val text: String,
    val tokenCount: Int,
    val cappedByMaxTokens: Boolean = false,
    /** Estimated tokens held by the open session after this turn, or null without a session. */
    val sessionTokens: Int? = null,
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
