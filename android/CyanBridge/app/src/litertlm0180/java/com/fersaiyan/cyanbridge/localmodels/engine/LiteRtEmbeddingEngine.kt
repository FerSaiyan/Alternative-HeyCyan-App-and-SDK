package com.fersaiyan.cyanbridge.localmodels.engine

import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.EmbeddingEngine
import com.google.ai.edge.litertlm.EmbeddingEngineConfig
import com.google.ai.edge.litertlm.EmbeddingOptions
import com.google.ai.edge.litertlm.InputData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * EmbeddingGemma 2 (`.litertlm`) through LiteRT-LM 0.18 EmbeddingEngine: the
 * embedding path for users whose chat model is not a LiteRT Gemma (cloud or
 * llama.cpp). Text inputs need the model's task prefixes (see companion);
 * `embedAudio` requires an audio-capable bundle (e.g. the 740M variant).
 */
class LiteRtEmbeddingEngine(
    private val modelPath: String,
    private val backend: Backend,
    private val cacheDir: String,
    private val outputSize: Int? = null,
    private val audio: Boolean = false,
) : TextEmbeddingEngine {
    private val mutex = Mutex()
    private var engine: EmbeddingEngine? = null

    override suspend fun embed(text: String): TextEmbeddingResult {
        require(text.isNotBlank()) { "Embedding input must not be blank" }
        return compute(listOf(InputData.Text(text)))
    }

    /** Audio embedding in the same space as text (audio-capable bundles only). */
    suspend fun embedAudio(audioBytes: ByteArray): TextEmbeddingResult {
        check(audio) { "Engine was created without an audio backend" }
        require(audioBytes.isNotEmpty()) { "Audio input must not be empty" }
        return compute(listOf(InputData.Audio(audioBytes)))
    }

    private suspend fun compute(inputs: List<InputData>): TextEmbeddingResult = mutex.withLock {
        withContext(Dispatchers.IO) {
            val loaded = engine ?: EmbeddingEngine(
                EmbeddingEngineConfig(
                    modelPath = modelPath,
                    backend = backend,
                    audioBackend = if (audio) Backend.CPU() else null,
                    cacheDir = cacheDir,
                ),
            ).also {
                it.initialize()
                engine = it
            }
            val started = System.nanoTime()
            val vector = loaded.computeEmbedding(inputs, EmbeddingOptions(normalize = true, outputSize = outputSize)).embedding
            val result = TextEmbeddingResult(
                vector = vector,
                modelPath = modelPath,
                elapsedMs = ((System.nanoTime() - started) / 1_000_000L).coerceAtLeast(1L),
                backend = "litert-${backend.javaClass.simpleName.lowercase()}",
            )
            check(result.isFinite() && result.dimension > 0) { "Invalid LiteRT embedding (dim=${result.dimension})" }
            Log.d(TAG, "embedded dim=${result.dimension} elapsedMs=${result.elapsedMs} backend=${result.backend}")
            result
        }
    }

    override fun close() {
        engine?.close()
        engine = null
    }

    companion object {
        private const val TAG = "LiteRtEmbedding"
        const val QUERY_PREFIX = "task: search result | query: "
        const val DOCUMENT_PREFIX = "title: none | text: "
    }
}
