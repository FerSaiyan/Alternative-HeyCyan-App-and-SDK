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

    private fun libDir(vararg names: String): File =
        tmp.newFolder().also { dir -> names.forEach { File(dir, it).writeText("so") } }
}
