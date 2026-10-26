package com.fersaiyan.cyanbridge.localmodels.engine

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class LiteRtLocalInferenceEngineStreamingTest {
    private val engine by lazy {
        LiteRtLocalInferenceEngine(ApplicationProvider.getApplicationContext())
    }

    @Test
    fun cumulativeCallbacksEmitOnlyNewSuffix() {
        assertEquals("There", engine.incrementalDelta("", "There"))
        assertEquals(" is", engine.incrementalDelta("There", "There is"))
        assertEquals(" a door", engine.incrementalDelta("There is", "There is a door"))
        // A newline-only chunk is kept (it ends a spoken-decision payload line).
        assertEquals("\n", engine.incrementalDelta("[search] weather today", "\n"))
    }

    @Test
    fun duplicateOrOlderCumulativeCallbacksEmitNothing() {
        assertEquals("", engine.incrementalDelta("There is", "There is"))
        assertEquals("", engine.incrementalDelta("There is", "There"))
    }

    @Test
    fun deltaStyleCallbacksRemainUsable() {
        assertEquals(" is", engine.incrementalDelta("There", " is"))
        assertEquals(" ahead", engine.incrementalDelta("There is", " ahead"))
    }

    @Test
    fun overlappingFragmentsDoNotRepeatSharedText() {
        assertEquals(" ahead", engine.incrementalDelta("Door is", "is ahead"))
    }

    @Test
    fun textOnlyBundleDetectionMatchesOnlyMissingMediaEncoders() {
        val pattern = LiteRtLocalInferenceEngine.MISSING_MEDIA_ENCODER
        assertTrue(pattern.containsMatchIn("Failed to create conversation: NOT_FOUND: TF_LITE_AUDIO_ENCODER_HW not found in the model."))
        assertTrue(pattern.containsMatchIn("NOT_FOUND: TF_LITE_VISION_ENCODER not found in the model."))
        assertFalse(pattern.containsMatchIn("NOT_FOUND: TF_LITE_PREFILL_DECODE not found in the model."))
        assertFalse(pattern.containsMatchIn("INTERNAL: audio encoder failed to allocate"))
    }
}
