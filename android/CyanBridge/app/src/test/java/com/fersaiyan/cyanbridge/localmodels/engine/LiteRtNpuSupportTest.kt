package com.fersaiyan.cyanbridge.localmodels.engine

import androidx.test.core.app.ApplicationProvider
import com.fersaiyan.cyanbridge.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class LiteRtNpuSupportTest {
    @Test
    fun socSpecificModelNamesAreRecognised() {
        assertEquals("SM8650", LiteRtNpuSupport.compiledFor("/x/embeddinggemma-2-text-270m_Qualcomm_SM8650.litertlm"))
        assertEquals("SM8750", LiteRtNpuSupport.compiledFor("wespeaker_resnet34_lm_2s_npu_Qualcomm_SM8750_apply_plugin.tflite"))
        assertEquals("SM8750", LiteRtNpuSupport.compiledFor("/x/gemma-4-E2B-it_qualcomm_sm8750.litertlm"))
        assertNull(LiteRtNpuSupport.compiledFor("/x/gemma-4-E2B-it.litertlm"))
    }

    @Test
    fun npuIsUnavailableWithoutRuntimeOrSupportedSoc() {
        val status = LiteRtNpuSupport.status(ApplicationProvider.getApplicationContext())
        assertFalse(status.available)
        // Without the runtime AAR the build itself is the reason; with it, Robolectric's SoC is.
        if (!BuildConfig.LITERT_NPU_RUNTIME) assertTrue(status.reason, status.reason.contains("no NPU runtime"))
    }
}
