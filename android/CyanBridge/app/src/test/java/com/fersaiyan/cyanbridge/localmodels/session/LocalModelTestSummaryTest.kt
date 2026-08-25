package com.fersaiyan.cyanbridge.localmodels.session

import com.fersaiyan.cyanbridge.localmodels.settings.LocalComputeBackend
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalModelTestSummaryTest {
    @Test
    fun summaryShowsOneClearlyNamedThroughputMetric() {
        val summary = LocalModelTestSummary.success(
            generatedTokens = 20,
            elapsedMs = 1_000,
            backend = LocalComputeBackend.GPU,
            activeGpuLayers = -1,
            fallbackReason = null,
        )

        assertTrue(summary.contains("Output speed: 20.00 tok/s"))
        assertTrue(summary.contains("Total time: 1000 ms"))
        assertTrue(summary.contains("Backend: GPU"))
        assertFalse(summary.contains("total tok/s"))
        assertFalse(summary.contains("gen tok/s"))
    }

    @Test
    fun cpuFallbackIsExplicitWithoutInventingSecondSpeed() {
        val summary = LocalModelTestSummary.success(
            generatedTokens = 8,
            elapsedMs = 2_000,
            backend = LocalComputeBackend.CPU,
            activeGpuLayers = 0,
            fallbackReason = "GPU unavailable",
        )

        assertTrue(summary.contains("Output speed: 4.00 tok/s"))
        assertTrue(summary.contains("GPU fallback: CPU"))
    }
}
