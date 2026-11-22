package com.fersaiyan.cyanbridge.localmodels.engine

import com.fersaiyan.cyanbridge.localmodels.engine.LiteRtNpuSupport.CompiledTarget
import com.fersaiyan.cyanbridge.localmodels.engine.LiteRtNpuSupport.Vendor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class LiteRtNpuSupportTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun socSpecificModelNamesAreRecognised() {
        assertEquals("SM8650", LiteRtNpuSupport.compiledFor("/x/embeddinggemma-2-text-270m_Qualcomm_SM8650.litertlm"))
        assertEquals("SM8750", LiteRtNpuSupport.compiledFor("wespeaker_resnet34_lm_2s_npu_Qualcomm_SM8750_apply_plugin.tflite"))
        assertEquals("SM8750", LiteRtNpuSupport.compiledFor("/x/gemma-4-E2B-it_qualcomm_sm8750.litertlm"))
        assertNull(LiteRtNpuSupport.compiledFor("/x/gemma-4-E2B-it.litertlm"))
    }

    @Test
    fun otherVendorsModelNamesAreRecognised() {
        assertEquals(
            CompiledTarget(Vendor.MEDIATEK, "MT6989"),
            LiteRtNpuSupport.compiledTarget("det_tiny_a8_ema_MediaTek_MT6989_apply_plugin.tflite"),
        )
        assertEquals(
            CompiledTarget(Vendor.GOOGLE_TENSOR, "Tensor_G5"),
            LiteRtNpuSupport.compiledTarget("/x/rec320_avg_Google_Tensor_G5_apply_plugin.tflite"),
        )
        assertEquals(
            CompiledTarget(Vendor.SAMSUNG, "E9965"),
            LiteRtNpuSupport.compiledTarget("det_tiny_a8_ema_Samsung_E9965_apply_plugin.tflite"),
        )
        assertEquals(
            CompiledTarget(Vendor.QUALCOMM, "SM8650"),
            LiteRtNpuSupport.compiledTarget("wespeaker_2s_fp32_s_Qualcomm_SM8650_apply_plugin.tflite"),
        )
    }

    @Test
    fun phoneVendorAndSocIdsMatchModelNames() {
        assertEquals(Vendor.QUALCOMM, LiteRtNpuSupport.vendorFor("QTI", "SM8650"))
        assertEquals(Vendor.MEDIATEK, LiteRtNpuSupport.vendorFor("Mediatek", "MT6989"))
        assertEquals(Vendor.GOOGLE_TENSOR, LiteRtNpuSupport.vendorFor("Google", "Tensor G5"))
        assertEquals(Vendor.SAMSUNG, LiteRtNpuSupport.vendorFor("Samsung", "s5e9965"))
        assertNull(LiteRtNpuSupport.vendorFor("Unisoc", "T820"))
        assertNull(LiteRtNpuSupport.vendorFor(null, null))

        assertEquals("E9965", LiteRtNpuSupport.canonicalSoc(Vendor.SAMSUNG, "s5e9965"))
        assertEquals("Tensor_G5", LiteRtNpuSupport.canonicalSoc(Vendor.GOOGLE_TENSOR, "Tensor G5"))
        assertEquals("MT6989", LiteRtNpuSupport.canonicalSoc(Vendor.MEDIATEK, "mt6989"))
        assertEquals("SM8650", LiteRtNpuSupport.canonicalSoc(Vendor.QUALCOMM, "SM8650"))
        assertEquals("MT6989", LiteRtNpuSupport.canonicalSoc(Vendor.MEDIATEK, "MT6989V/CZA"))
        assertEquals("SM8650", LiteRtNpuSupport.canonicalSoc(Vendor.QUALCOMM, "SM8650-AB"))
    }

    /** Build.SOC_MANUFACTURER / SOC_MODEL as real phones report them (Device Run 2026-11-22, Samsung RTL). */
    @Test
    fun realPhoneSocStringsMapToModelTags() {
        fun tag(manufacturer: String, soc: String): String? {
            val vendor = LiteRtNpuSupport.vendorFor(manufacturer, soc) ?: return null
            return "${vendor.fileTag}_${LiteRtNpuSupport.canonicalSoc(vendor, soc)}"
        }
        assertEquals("Google_Tensor_G5", tag("Google", "Tensor G5")) // Pixel 10
        assertEquals("MediaTek_MT6989", tag("Mediatek", "MT6989")) // Galaxy Tab S10+
        assertEquals("Samsung_E9945", tag("Samsung", "s5e9945")) // Galaxy S24 (Exynos 2400)
        assertEquals("Samsung_E9965", tag("Samsung", "s5e9965")) // Galaxy S26 (Exynos 2600)
        assertEquals("Qualcomm_SM8750", tag("QTI", "SM8750")) // Galaxy S25 (pa1q)
        assertEquals("Qualcomm_SM8850", tag("QTI", "SM8850")) // Galaxy S26 (m1q)
    }

    @Test
    fun qualcommNeedsQnnLibrariesAndTheSkel() {
        val lib = libDir("libLiteRtDispatch_Qualcomm.so", "libQnnHtp.so", "libQnnSystem.so", "libQnnHtpV75Stub.so")
        val skels = tmp.newFolder("skels")
        val missingSkel = LiteRtNpuSupport.statusFor(Vendor.QUALCOMM, "SM8650", lib, skels)
        assertFalse(missingSkel.available)
        assertTrue(missingSkel.reason, "not downloaded" in missingSkel.reason)

        File(skels, "libQnnHtpV75Skel.so").writeText("skel")
        val ready = LiteRtNpuSupport.statusFor(Vendor.QUALCOMM, "SM8650", lib, skels)
        assertTrue(ready.reason, ready.available)
        assertEquals("V75", ready.hexagon)
        assertEquals("Qualcomm_SM8650", ready.modelTag)

        assertFalse(LiteRtNpuSupport.statusFor(Vendor.QUALCOMM, "SM7325", lib, skels).available)
    }

    @Test
    fun otherVendorsNeedTheirDispatchLibrary() {
        val lib = libDir("libLiteRtDispatch_MediaTek.so", "libLiteRt.so")
        val mtk = LiteRtNpuSupport.statusFor(Vendor.MEDIATEK, "MT6989", lib, null)
        assertTrue(mtk.reason, mtk.available)
        assertEquals("MediaTek_MT6989", mtk.modelTag)

        val exynos = LiteRtNpuSupport.statusFor(Vendor.SAMSUNG, "E9965", lib, null)
        assertFalse(exynos.available)
        assertTrue(exynos.reason, "libLiteRtDispatch_Samsung.so" in exynos.reason)
    }

    @Test
    fun dispatchDirectoryHoldsOnlyThisVendorsDispatchLibrary() {
        val lib = libDir(
            "libLiteRtDispatch_Qualcomm.so", "libLiteRtDispatch_MediaTek.so", "libLiteRtDispatch_GoogleTensor.so",
            "libLiteRtDispatch_Samsung.so", "libLiteRt.so", "libQnnHtp.so", "libQnnHtpV79Stub.so",
        )
        val skels = tmp.newFolder("skels2").also { File(it, "libQnnHtpV79Skel.so").writeText("skel") }

        val mtk = LiteRtNpuSupport.linkTargets(Vendor.MEDIATEK, lib, skels, null)
        assertEquals(setOf("libLiteRtDispatch_MediaTek.so", "libLiteRt.so", "libQnnHtp.so", "libQnnHtpV79Stub.so"), mtk.keys)

        val qc = LiteRtNpuSupport.linkTargets(Vendor.QUALCOMM, lib, skels, "V79")
        assertTrue("libLiteRtDispatch_Qualcomm.so" in qc.keys)
        assertFalse("libLiteRtDispatch_MediaTek.so" in qc.keys)
        assertEquals(File(skels, "libQnnHtpV79Skel.so"), qc["libQnnHtpV79Skel.so"])
    }

    /** Download-mode AARs ship assets/npu_runtime/skels.json; a missing skel listed there is downloadable. */
    @Test
    fun missingSkelIsDownloadableWhenTheAarPinsIt() {
        val manifest = QnnSkels.parse(
            """{"qairt": "2.52.0.261104115025", "release_tag": "npu-runtime-qairt2.52.0",
               "skels": {"libQnnHtpV75Skel.so": {"bytes": 13411540,
               "sha256": "D469C99CD295FC2D9FA5A576BC977AD78A42505AFB12729B19028FD429E0F92F"}}}""",
        )
        assertEquals("npu-runtime-qairt2.52.0", manifest.releaseTag)
        assertEquals(13411540L, manifest.skels.getValue("libQnnHtpV75Skel.so").bytes)
        assertEquals("d469c99cd295fc2d9fa5a576bc977ad78a42505afb12729b19028fd429e0f92f", manifest.skels.getValue("libQnnHtpV75Skel.so").sha256)
        assertEquals(
            "https://github.com/FerSaiyan/Alternative-HeyCyan-App-and-SDK/releases/download/npu-runtime-qairt2.52.0/libQnnHtpV75Skel.so",
            QnnSkels.url(manifest, "libQnnHtpV75Skel.so"),
        )

        val lib = libDir("libLiteRtDispatch_Qualcomm.so", "libQnnHtp.so", "libQnnSystem.so", "libQnnHtpV75Stub.so")
        val status = LiteRtNpuSupport.statusFor(
            Vendor.QUALCOMM, "SM8650", lib, tmp.newFolder("noskel"), downloadableSkels = manifest.skels.keys,
        )
        assertFalse(status.available)
        assertTrue(status.skelDownloadable)

        // Not pinned (e.g. another arch) or the QNN libraries themselves missing: not downloadable.
        assertFalse(LiteRtNpuSupport.statusFor(Vendor.QUALCOMM, "SM8750", lib, null, manifest.skels.keys).skelDownloadable)
        val partial = libDir("libLiteRtDispatch_Qualcomm.so", "libQnnHtpV75Stub.so")
        assertFalse(LiteRtNpuSupport.statusFor(Vendor.QUALCOMM, "SM8650", partial, null, manifest.skels.keys).skelDownloadable)
    }

    private fun libDir(vararg names: String): File =
        tmp.newFolder().also { dir -> names.forEach { File(dir, it).writeText("so") } }
}
