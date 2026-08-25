package com.fersaiyan.cyanbridge.localmodels.session

import com.fersaiyan.cyanbridge.localmodels.settings.LocalComputeBackend
import java.util.Locale

/** Builds the single, user-facing performance summary shown by "Test model now". */
object LocalModelTestSummary {
    fun success(
        generatedTokens: Int,
        elapsedMs: Long,
        backend: LocalComputeBackend,
        activeGpuLayers: Int,
        fallbackReason: String?,
    ): String {
        val safeElapsed = elapsedMs.coerceAtLeast(1L)
        val outputTokensPerSecond = generatedTokens.coerceAtLeast(1) * 1000.0 / safeElapsed
        val backendLabel = when (backend) {
            LocalComputeBackend.NPU_EXPERIMENTAL -> "NPU"
            LocalComputeBackend.GPU -> "GPU"
            LocalComputeBackend.CPU -> "CPU"
        }
        val accelerated = backend != LocalComputeBackend.CPU
        val layersSuffix = if (accelerated) {
            val layers = if (activeGpuLayers == -1) "auto" else activeGpuLayers.toString()
            " · GPU layers: $layers"
        } else {
            ""
        }
        val fallbackSuffix = if (!fallbackReason.isNullOrBlank() && !accelerated) {
            " · GPU fallback: CPU"
        } else {
            ""
        }
        return "Model test complete · Output speed: ${String.format(Locale.US, "%.2f", outputTokensPerSecond)} tok/s" +
            " · Total time: ${safeElapsed} ms · Backend: $backendLabel$layersSuffix$fallbackSuffix"
    }
}
