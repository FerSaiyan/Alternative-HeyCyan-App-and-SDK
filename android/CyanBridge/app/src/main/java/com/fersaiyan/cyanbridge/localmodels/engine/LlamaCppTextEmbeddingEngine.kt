package com.fersaiyan.cyanbridge.localmodels.engine

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.nehuatl.llamacpp.LlamaAndroid
import org.nehuatl.llamacpp.LlamaContext
import java.io.File

/**
 * Real GGUF embedding path using the embedding API already present in
 * llamacpp-kotlin 0.4.0. This is intentionally independent of the chat session
 * manager: embedding GGUFs are not chat models and must be initialized with
 * `embedding=true`.
 */
class LlamaCppTextEmbeddingEngine(
    context: Context,
    private val modelFile: File,
    private val contextSize: Int = 2048,
    private val cpuThreads: Int = 4,
) : TextEmbeddingEngine {
    private val appContext = context.applicationContext
    private var llama: LlamaAndroid? = null
    private var contextId: Int? = null

    override suspend fun embed(text: String): TextEmbeddingResult {
        require(text.isNotBlank()) { "Embedding input must not be blank" }
        ensureLoaded()
        val engine = llama ?: error("llama embedding engine is not initialized")
        val id = contextId ?: error("llama embedding context is not initialized")
        val started = System.nanoTime()
        val raw = withContext(Dispatchers.IO) {
            engine.embedding(id, text).first()
        }
        val vector = extractVector(raw)
        val elapsedMs = ((System.nanoTime() - started) / 1_000_000L).coerceAtLeast(1L)
        val result = TextEmbeddingResult(
            vector = vector,
            modelPath = modelFile.absolutePath,
            elapsedMs = elapsedMs,
            backend = "llama.cpp-cpu",
            rawKeys = raw.keys,
        )
        Log.i(
            TAG,
            "embedded model=${modelFile.name} chars=${text.length} dim=${result.dimension} " +
                "norm=${result.l2Norm()} finite=${result.isFinite()} elapsedMs=$elapsedMs keys=${raw.keys}",
        )
        check(result.isFinite()) { "Embedding output contains NaN/Infinity: keys=${raw.keys}" }
        check(result.dimension > 0) { "Embedding output is empty: keys=${raw.keys}" }
        return result
    }

    override fun close() {
        val engine = llama
        val id = contextId
        if (engine != null && id != null) {
            runCatching { engine.releaseContext(id) }
        }
        contextId = null
        llama = null
    }

    private suspend fun ensureLoaded() {
        if (contextId != null) return
        withContext(Dispatchers.IO) {
            require(modelFile.isFile) { "Embedding GGUF does not exist: ${modelFile.absolutePath}" }
            verifyEmbeddingJniContract()
            val engine = llama ?: createLlamaAndroid().also { llama = it }
            val pfd = ParcelFileDescriptor.open(modelFile, ParcelFileDescriptor.MODE_READ_ONLY)
            val fd = pfd.detachFd()
            var started = false
            try {
                val params = mapOf<String, Any>(
                    "model" to Uri.fromFile(modelFile).toString(),
                    "model_fd" to fd,
                    "n_ctx" to contextSize.coerceAtLeast(256),
                    "n_batch" to contextSize.coerceAtLeast(256),
                    "n_threads" to cpuThreads.coerceAtLeast(1),
                    "n_gpu_layers" to 0,
                    "use_mmap" to true,
                    "use_mlock" to false,
                    "embedding" to true,
                )
                Log.i(TAG, "loading embedding GGUF=${modelFile.absolutePath} params=${params.keys}")
                val result = engine.startEngine(params) { _: String -> Unit }
                    ?: error("llama embedding startEngine returned null")
                val newId = (result["contextId"] as? Number)?.toInt()
                    ?: error("llama embedding context id missing: keys=${result.keys}")
                contextId = newId
                started = true
                Log.i(TAG, "embedding GGUF ready model=${modelFile.name} contextId=$newId")
            } finally {
                // The V2 runtime owns model_fd after successful initialization.
                if (!started) runCatching { ParcelFileDescriptor.adoptFd(fd).close() }
            }
        }
    }

    private fun createLlamaAndroid(): LlamaAndroid =
        LlamaAndroid(appContext.contentResolver)

    /**
     * llamacpp-kotlin 0.4.0 declares the native method as Map, while its JNI
     * implementation returns ArrayList. ART aborts on that mismatch. The
     * embedding probe must fail clearly when the unpatched AAR is selected,
     * rather than taking down the instrumentation/app process.
     */
    private fun verifyEmbeddingJniContract() {
        val method = LlamaContext::class.java.getDeclaredMethod(
            "embedding",
            Long::class.javaPrimitiveType,
            String::class.java,
        )
        check(List::class.java.isAssignableFrom(method.returnType)) {
            "llamacpp-kotlin embedding JNI contract is incompatible: " +
                "native return=${method.returnType.name}; use the patched embedding AAR"
        }
    }

    private fun extractVector(raw: Map<String, Any>): FloatArray {
        val candidates = listOf("embedding", "embeddings", "data", "vector")
        for (key in candidates) {
            raw[key]?.let { value ->
                toFloatArray(value)?.let { return it }
            }
        }
        raw.values.forEach { value ->
            toFloatArray(value)?.let { return it }
        }
        error("llama embedding response has no numeric vector: keys=${raw.keys}")
    }

    private fun toFloatArray(value: Any): FloatArray? = when (value) {
        is FloatArray -> value
        is DoubleArray -> FloatArray(value.size) { value[it].toFloat() }
        is IntArray -> FloatArray(value.size) { value[it].toFloat() }
        is LongArray -> FloatArray(value.size) { value[it].toFloat() }
        is List<*> -> {
            if (value.isEmpty() || value.any { it !is Number }) null
            else FloatArray(value.size) { (value[it] as Number).toFloat() }
        }
        else -> null
    }

    private companion object {
        private const val TAG = "JEV_EMBED"
    }
}
