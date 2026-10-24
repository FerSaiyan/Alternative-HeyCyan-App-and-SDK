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
        assertEquals("gemma4-e2b-it-npu", models.last().id)
        assertTrue(models.all { entry ->
            val hasDownloadSource = entry.sourceUrl?.startsWith("https://") == true
            val isDisabledComingSoonPlaceholder =
                entry.comingSoon && !entry.enabled && entry.sourceUrl == null
            // Per-SoC NPU entries get their URL and checksum when resolved for a phone.
            val hasPerSocSources = entry.npuVariants.isNotEmpty() && entry.npuOnly &&
                entry.npuVariants.all { set -> set.files.values.all { it.sha256?.length == 64 && it.sizeBytes > 0 } }

            entry.id.isNotBlank() &&
                entry.expectedFilename.isNotBlank() &&
                (hasDownloadSource || isDisabledComingSoonPlaceholder || hasPerSocSources) &&
                entry.sizeBytes > 0 &&
                entry.contextSizeDefault > 0 &&
                entry.minRamGb > 0.0 &&
                entry.minStorageGb > 0.0
        })
    }

    @Test
    fun everyEntryMatchesItsRuntimeFileFormat() {
        LocalModelCatalogRepository.curatedModels.forEach { entry ->
            assertEquals("local", entry.providerType)
            if (entry.engine == "litert") {
                assertEquals("litertlm", entry.format)
                assertTrue(entry.expectedFilename.endsWith(".litertlm"))
            } else {
                assertEquals("llama", entry.engine)
                assertEquals("gguf", entry.format)
                assertTrue(entry.expectedFilename.endsWith(".gguf"))
            }
            assertTrue(entry.contextSizeDefault >= 2048, entry.id)
        }
    }

    @Test
    fun lookupRejectsBlankIdsAndFindsCurrentQwenStarter() {
        assertNull(LocalModelCatalogRepository.findById(null))
        assertNull(LocalModelCatalogRepository.findById("  "))
        val qwen = LocalModelCatalogRepository.findById("qwen3.5-0.8b-q4")
        assertEquals("qwen3.5-0.8b-q4", qwen?.id)
        assertEquals("qwen", qwen?.family)
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
