package com.fersaiyan.cyanbridge.localmodels.catalog

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class NpuVariantResolverTest {
    private val npuEntry = LocalModelCatalogRepository.findById("gemma4-e2b-it-npu")!!

    @Test
    fun resolvesOurSm8650FileAtThePinnedRevision() {
        val resolved = NpuVariantResolver.resolve(npuEntry, "SM8650")!!
        assertEquals("gemma-4-E2B-it_Qualcomm_SM8650.litertlm", resolved.expectedFilename)
        assertTrue(resolved.sourceUrl!!.startsWith("https://huggingface.co/Fertroll10/gemma-4-E2B-it-litert-lm-npu/resolve/"))
        assertTrue(resolved.sourceUrl!!.endsWith("/gemma-4-E2B-it_Qualcomm_SM8650.litertlm"))
        assertTrue("/resolve/main/" !in resolved.sourceUrl!!, "pinned to a commit")
        assertEquals("70c0223bdd24b3653b32b7ffb0c064babff86fd8e8697fe6cb9a0fecb127eed4", resolved.sha256)
        assertEquals(2_322_196_704L, resolved.sizeBytes)
    }

    @Test
    fun resolvesGooglesSm8750FileWithLitertCommunityNaming() {
        val resolved = NpuVariantResolver.resolve(npuEntry, "SM8750")!!
        assertEquals("gemma-4-E2B-it_qualcomm_sm8750.litertlm", resolved.expectedFilename)
        assertEquals(
            "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/" +
                "b3ca0d2f076785a8f4b2219ddbd2bdb99954eae1/gemma-4-E2B-it_qualcomm_sm8750.litertlm",
            resolved.sourceUrl,
        )
        assertEquals("41dd675fbe735b6029012b5576a5716bac614fd8156de0128db4c9dff3cebd4e", resolved.sha256)
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
    }
}
