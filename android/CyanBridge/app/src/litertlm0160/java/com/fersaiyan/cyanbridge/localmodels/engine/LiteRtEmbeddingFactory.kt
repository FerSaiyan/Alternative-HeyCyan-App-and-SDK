package com.fersaiyan.cyanbridge.localmodels.engine

import com.fersaiyan.cyanbridge.localmodels.settings.LocalComputeBackend

/** LiteRT-LM 0.16 has no EmbeddingEngine; build with -PliteRtLmVersion=0.18.0. */
object LiteRtEmbeddingFactory {
    const val AVAILABLE = false

    fun create(
        modelPath: String,
        computeBackend: LocalComputeBackend,
        cacheDir: String,
        nativeLibraryDir: String,
        outputSize: Int? = null,
        audio: Boolean = false,
    ): TextEmbeddingEngine =
        throw UnsupportedOperationException("LiteRT embeddings require LiteRT-LM 0.18 (-PliteRtLmVersion=0.18.0)")
}
