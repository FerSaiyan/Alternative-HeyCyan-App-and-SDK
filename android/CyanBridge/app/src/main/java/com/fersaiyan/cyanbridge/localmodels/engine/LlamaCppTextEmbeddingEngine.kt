package com.fersaiyan.cyanbridge.localmodels.engine

import android.content.Context
import com.fersaiyan.cyanbridge.llama.UpstreamLlamaBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Separate pooled-embedding context using the same pinned upstream runtime as chat/media. */
class LlamaCppTextEmbeddingEngine(
    context: Context,
    private val modelFile: File,
    private val contextSize: Int = 2048,
    private val cpuThreads: Int = 4,
) : TextEmbeddingEngine {
    private var handle = 0L
    override suspend fun embed(text: String): TextEmbeddingResult = withContext(Dispatchers.IO) {
        require(text.isNotBlank())
        require(modelFile.isFile) { "Embedding GGUF is missing" }
        if (handle == 0L) handle = UpstreamLlamaBridge.load(modelFile.absolutePath, null, contextSize, cpuThreads, true)
        val start = System.nanoTime()
        val result = TextEmbeddingResult(
            vector = UpstreamLlamaBridge.embed(handle, text.toByteArray(Charsets.UTF_8)),
            modelPath = modelFile.absolutePath,
            elapsedMs = (System.nanoTime() - start) / 1_000_000,
            backend = "llama.cpp-cpu", rawKeys = setOf("embedding"),
        )
        check(result.dimension > 0 && result.isFinite()) { "Invalid embedding output" }
        result
    }
    override fun close() {
        if (handle != 0L) UpstreamLlamaBridge.release(handle)
        handle = 0
    }
}
