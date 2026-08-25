package com.fersaiyan.cyanbridge.localmodels.engine

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test

class LiteRtLocalInferenceEngineStreamingTest {
    private val engine = LiteRtLocalInferenceEngine(ApplicationProvider.getApplicationContext())

    @Test
    fun cumulativeCallbacksEmitOnlyNewSuffix() {
        assertEquals("There", engine.incrementalDelta("", "There"))
        assertEquals(" is", engine.incrementalDelta("There", "There is"))
        assertEquals(" a door", engine.incrementalDelta("There is", "There is a door"))
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
}
