package com.fersaiyan.cyanbridge.localmodels.catalog

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class NpuVariantResolverTest {
    private val npuEntry = LocalModelCatalogRepository.findById("gemma4-e2b-it-npu")!!

    @Test
    fun resolvesOurMultimodalV3FilesAtThePinnedRevision() {
        val resolved = NpuVariantResolver.resolve(npuEntry, "SM8650")!!
        assertEquals("gemma-4-E2B-it-v3mm_Qualcomm_SM8650.litertlm", resolved.expectedFilename)
        assertEquals(
            "https://huggingface.co/Fertroll10/gemma-4-E2B-it-litert-lm-npu/resolve/" +
                "78988cdf3e3f045985062fb685d9c24b1cdcd333/gemma-4-E2B-it-v3mm_Qualcomm_SM8650.litertlm",
            resolved.sourceUrl,
        )
        assertEquals("06fc9d05bfca142e6392bd1fac995781e3ce987b01d403f7536d293300839f44", resolved.sha256)
        assertEquals(2_643_630_708L, resolved.sizeBytes)
        assertEquals("gemma-4-E2B-it-v3mm_Qualcomm_SM8550.litertlm", NpuVariantResolver.resolve(npuEntry, "SM8550")!!.expectedFilename)
        assertEquals("gemma-4-E2B-it-v3mm_Qualcomm_SM8850.litertlm", NpuVariantResolver.resolve(npuEntry, "SM8850")!!.expectedFilename)
        val mtk = NpuVariantResolver.resolve(npuEntry, "MT6989")!!
        assertEquals("gemma-4-E2B-it-v3mm_MediaTek_MT6989.litertlm", mtk.expectedFilename)
        assertEquals("a9af92618d67f3663cf90e4ffbd99ac69de7bdfe23029a5d7f8f7a1f8d44ef00", mtk.sha256)
    }

    @Test
    fun resolvesOurSm8750MultimodalFile() {
        val resolved = NpuVariantResolver.resolve(npuEntry, "SM8750")!!
        assertEquals("gemma-4-E2B-it-v3mm_Qualcomm_SM8750.litertlm", resolved.expectedFilename)
        assertEquals("3b6266108cb13cd21e8445de71bb959a44576c5982677dcd8513a542219d1f60", resolved.sha256)
    }

    @Test
    fun unpublishedOrUnknownSocHasNoDownload() {
        assertNull(NpuVariantResolver.resolve(npuEntry, "SM8450"))
        assertNull(NpuVariantResolver.resolve(npuEntry, null))
    }

    @Test
    fun entriesWithoutVariantsAreUnchanged() {
        val cpu = LocalModelCatalogRepository.findById("gemma4-e2b-it-litert")!!
        assertSame(cpu, NpuVariantResolver.resolve(cpu, "SM8650"))
    }

    @Test
    fun pixel10ResolvesGooglesTensorG5FileAtThePinnedRevision() {
        val resolved = NpuVariantResolver.resolve(npuEntry, "Tensor G5")!!
        assertEquals("gemma-4-E2B-it_Google_Tensor_G5.litertlm", resolved.expectedFilename)
        assertEquals(
            "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/" +
                "b3ca0d2f076785a8f4b2219ddbd2bdb99954eae1/gemma-4-E2B-it_Google_Tensor_G5.litertlm",
            resolved.sourceUrl,
        )
        assertEquals("af1082986639ecde7db95d91be6fe54f8b6b458104734c5bafc204e69d6852dc", resolved.sha256)
    }

    @Test
    fun exynos2400HasNoBuild() {
        assertNull(NpuVariantResolver.resolve(npuEntry, "s5e9945"))
    }

    private fun entryWith(vararg sets: NpuVariantSet) = npuEntry.copy(npuVariants = sets.toList())

    @Test
    fun vendorTokenAndSocFileNamesBuildTheFileName() {
        val entry = entryWith(
            NpuVariantSet(repo = "o/r", filePrefix = "m", vendor = "MediaTek", files = mapOf("MT6989" to NpuVariantFile("a", 1))),
            NpuVariantSet(repo = "o/g", filePrefix = "m", vendor = "Google", files = mapOf("Tensor G5" to NpuVariantFile("b", 2))),
            NpuVariantSet(repo = "o/s", filePrefix = "m", vendor = "Samsung", files = mapOf("s5e9965" to NpuVariantFile("c", 3)),
                socFileNames = mapOf("s5e9965" to "E9965")),
        )
        assertEquals("m_MediaTek_MT6989.litertlm", NpuVariantResolver.resolve(entry, "MT6989")!!.expectedFilename)
        assertEquals("m_Google_Tensor_G5.litertlm", NpuVariantResolver.resolve(entry, "Tensor G5")!!.expectedFilename)
        assertEquals("m_Samsung_E9965.litertlm", NpuVariantResolver.resolve(entry, "s5e9965")!!.expectedFilename)
        assertEquals("https://huggingface.co/o/s/resolve/main/m_Samsung_E9965.litertlm", NpuVariantResolver.resolve(entry, "s5e9965")!!.sourceUrl)
    }

    @Test
    fun socModelMatchesCaseInsensitivelyAndTrimmed() {
        val entry = entryWith(NpuVariantSet(repo = "o/r", filePrefix = "m", vendor = "MediaTek", files = mapOf("MT6989" to NpuVariantFile("a", 1))))
        assertEquals("m_MediaTek_MT6989.litertlm", NpuVariantResolver.resolve(entry, " mt6989 ")!!.expectedFilename)
        assertNull(NpuVariantResolver.resolve(entry, ""))
    }
}
