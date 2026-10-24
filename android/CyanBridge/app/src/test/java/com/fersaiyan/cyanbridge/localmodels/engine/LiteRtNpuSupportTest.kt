package com.fersaiyan.cyanbridge.localmodels.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
