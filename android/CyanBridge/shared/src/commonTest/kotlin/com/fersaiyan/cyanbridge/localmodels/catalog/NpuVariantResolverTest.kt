package com.fersaiyan.cyanbridge.localmodels.catalog

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class NpuVariantResolverTest {
    private val npuEntry = LocalModelCatalogRepository.curatedModels.first { it.id == "gemma4-e2b-npu-coming-soon" }.copy(
        npuVariants = NpuVariantSet(repo = "example/gemma-4-E2B-it-npu", filePrefix = "gemma-4-E2B-it", socs = setOf("SM8650", "SM8750")),
    )

    @Test
    fun resolvesThePhonesSocFile() {
        val resolved = NpuVariantResolver.resolve(npuEntry, "SM8650")
        assertEquals("gemma-4-E2B-it_Qualcomm_SM8650.litertlm", resolved?.expectedFilename)
        assertEquals(
            "https://huggingface.co/example/gemma-4-E2B-it-npu/resolve/main/gemma-4-E2B-it_Qualcomm_SM8650.litertlm",
            resolved?.sourceUrl,
        )
    }

    @Test
    fun unpublishedOrUnknownSocHasNoDownload() {
        assertNull(NpuVariantResolver.resolve(npuEntry, "SM8550"))
        assertNull(NpuVariantResolver.resolve(npuEntry, null))
    }

    @Test
    fun entriesWithoutVariantsAreUnchanged() {
        val cpu = LocalModelCatalogRepository.findById("gemma4-e2b-it-litert")!!
        assertSame(cpu, NpuVariantResolver.resolve(cpu, "SM8650"))
        assertNull(NpuVariantResolver.fileName(cpu, "SM8650"))
    }
}
