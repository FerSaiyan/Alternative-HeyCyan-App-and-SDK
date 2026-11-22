package com.fersaiyan.cyanbridge.localmodels.engine

import com.google.ai.edge.litertlm.Backend
import com.fersaiyan.cyanbridge.localmodels.settings.LocalComputeBackend

/** LiteRT-LM 0.18: EmbeddingGemma 2 `.litertlm` embedding is available. */
object LiteRtEmbeddingFactory {
    const val AVAILABLE = true

    fun create(
        modelPath: String,
        computeBackend: LocalComputeBackend,
        cacheDir: String,
        nativeLibraryDir: String,
        outputSize: Int? = null,
        audio: Boolean = false,
    ): TextEmbeddingEngine {
        val backend = when (computeBackend) {
            // EmbeddingGemma 2 on the bundled OpenCL accelerator SIGSEGVs during delegate
            // application (Galaxy Z Fold6, LiteRT-LM 0.18); a native crash is uncatchable.
            // CPU is ~96 ms/embedding there, so GPU requests stay on CPU for now.
            LocalComputeBackend.CPU, LocalComputeBackend.GPU -> Backend.CPU()
            LocalComputeBackend.NPU_EXPERIMENTAL -> Backend.NPU(nativeLibraryDir)
        }
        return LiteRtEmbeddingEngine(modelPath, backend, cacheDir, outputSize, audio)
    }
}
