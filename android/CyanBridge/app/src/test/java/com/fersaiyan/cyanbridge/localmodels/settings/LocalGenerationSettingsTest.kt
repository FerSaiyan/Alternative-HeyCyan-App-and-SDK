package com.fersaiyan.cyanbridge.localmodels.settings

import com.fersaiyan.cyanbridge.localmodels.catalog.LocalModelCatalogRepository
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalGenerationSettingsTest {
    @Test
    fun everyProfileStartsWithEditableSpeechFirstPrompt() {
        LocalModelPerformanceProfile.entries.forEach { profile ->
            val settings = LocalGenerationSettings.defaultsFor(entry = null, profile = profile)
            assertEquals(LocalGenerationSettings.DEFAULT_SYSTEM_PROMPT, settings.systemPromptOverride)
        }
    }

    @Test
    fun legacyEightWordDefaultMigratesWithoutReplacingCustomPrompts() {
        assertEquals(
            LocalGenerationSettings.DEFAULT_SYSTEM_PROMPT,
            LocalGenerationSettings.migrateDefaultSystemPrompt(
                LocalGenerationSettings.LEGACY_EIGHT_WORD_SYSTEM_PROMPT,
            ),
        )
        assertEquals("Custom prompt", LocalGenerationSettings.migrateDefaultSystemPrompt("Custom prompt"))
    }

    @Test
    fun npuOnlyCatalogModelsDefaultToTheNpuAndLiteRt() {
        val npu = LocalGenerationSettings.defaultsFor(LocalModelCatalogRepository.findById("gemma4-e2b-it-npu"))
        assertEquals(LocalComputeBackend.NPU_EXPERIMENTAL, npu.computeBackend)
        assertEquals(LocalModelRuntime.LITERT, npu.modelRuntime)
        assertEquals(4096, npu.contextSize)
        val gpu = LocalGenerationSettings.defaultsFor(LocalModelCatalogRepository.findById("gemma4-e2b-it-litert"))
        assertEquals(LocalComputeBackend.GPU, gpu.computeBackend)
    }
}
