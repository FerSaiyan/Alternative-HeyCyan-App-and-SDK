package com.fersaiyan.cyanbridge.localmodels

import com.fersaiyan.cyanbridge.localmodels.catalog.LocalModelCatalogRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalModelCatalogRepositoryTest {
    @Test
    fun catalog_contains_current_curated_models() {
        val ids = LocalModelCatalogRepository.curatedModels.map { it.id }.toSet()
        assertTrue(ids.contains("qwen3.5-0.8b-q4"))
        assertTrue(ids.contains("gemma4-e2b-it-litert"))
        assertTrue(ids.contains("gemma4-e4b-it-litert"))
        assertTrue(ids.none { it.startsWith("qwen2.5") })
    }

    @Test
    fun every_catalog_entry_has_consistent_runtime_contract() {
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
            assertTrue(entry.contextSizeDefault >= 2048)
        }
    }

    @Test
    fun can_find_current_qwen_by_id() {
        val model = LocalModelCatalogRepository.findById("qwen3.5-0.8b-q4")
        assertNotNull(model)
        assertEquals("qwen", model?.family)
        assertEquals("Qwen3.5-0.8B-Q4_0.gguf", model?.expectedFilename)
    }
}
