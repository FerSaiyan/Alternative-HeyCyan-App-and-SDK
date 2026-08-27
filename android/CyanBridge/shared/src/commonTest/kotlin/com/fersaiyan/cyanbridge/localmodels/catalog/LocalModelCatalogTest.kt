package com.fersaiyan.cyanbridge.localmodels.catalog

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocalModelCatalogTest {
    @Test
    fun curatedCatalogUsesStableUniqueAndValidEntries() {
        val models = LocalModelCatalogRepository.curatedModels

        assertEquals(models.size, models.map(LocalModelCatalogEntry::id).toSet().size)
        assertEquals("gemma4-e2b-npu-coming-soon", models.last().id)
        assertTrue(models.all { entry ->
            val hasDownloadSource = entry.sourceUrl?.startsWith("https://") == true
            val isDisabledComingSoonPlaceholder =
                entry.comingSoon && !entry.enabled && entry.sourceUrl == null

            entry.id.isNotBlank() &&
                entry.expectedFilename.isNotBlank() &&
                (hasDownloadSource || isDisabledComingSoonPlaceholder) &&
                entry.sizeBytes > 0 &&
                entry.contextSizeDefault > 0 &&
                entry.minRamGb > 0.0 &&
                entry.minStorageGb > 0.0
        })
    }

    @Test
    fun lookupRejectsBlankIdsAndFindsCurrentQwenStarter() {
        assertNull(LocalModelCatalogRepository.findById(null))
        assertNull(LocalModelCatalogRepository.findById("  "))
        val qwen = LocalModelCatalogRepository.findById("qwen3.5-0.8b-q4")
        assertEquals("qwen3.5-0.8b-q4", qwen?.id)
        assertEquals("Qwen3.5-0.8B-Q4_0.gguf", qwen?.expectedFilename)
        assertEquals(0.35, qwen?.minStorageGb)
        assertTrue(LocalModelCatalogRepository.curatedModels.none { it.id.startsWith("qwen2.5") })
    }

    @Test
    fun ramFloorsAndStarterRecommendationsMatchSupportedDevices() {
        assertEquals(6.0, LocalModelCatalogRepository.findById("gemma4-e2b-it-litert")?.minRamGb)
        assertEquals(8.0, LocalModelCatalogRepository.findById("gemma4-e4b-it-litert")?.minRamGb)
        assertEquals(4.0, LocalModelCatalogRepository.findById("qwen3.5-0.8b-q4")?.minRamGb)

        assertEquals("gemma4-e2b-it-litert", LocalModelCatalogRepository.recommendedStarterForRam(8.0)?.id)
        assertEquals("gemma4-e2b-it-litert", LocalModelCatalogRepository.recommendedStarterForRam(6.0)?.id)
        assertEquals("qwen3.5-0.8b-q4", LocalModelCatalogRepository.recommendedStarterForRam(4.0)?.id)
        assertNull(LocalModelCatalogRepository.recommendedStarterForRam(3.0))
    }
}
