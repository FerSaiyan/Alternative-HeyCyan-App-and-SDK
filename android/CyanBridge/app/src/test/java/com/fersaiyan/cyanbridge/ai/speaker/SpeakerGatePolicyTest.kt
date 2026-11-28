package com.fersaiyan.cyanbridge.ai.speaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The app-side speaker gate (SpeakerMath and its tests moved to the shared module's common code). */
class SpeakerGatePolicyTest {
    @Test fun `gate blocks only below the model's lower threshold and samples long questions evenly`() {
        val spec = SpeakerModels.DEFAULT
        assertEquals(SpeakerVerdict.NOT_WEARER, SpeakerGatePolicy.verdict(spec, spec.blockBelow - 0.01f))
        assertEquals(SpeakerVerdict.UNCERTAIN, SpeakerGatePolicy.verdict(spec, spec.blockBelow))
        assertEquals(SpeakerVerdict.WEARER, SpeakerGatePolicy.verdict(spec, spec.wearerAt))
        SpeakerModels.all.forEach { assertTrue(it.id, it.blockBelow < it.wearerAt) }
        assertEquals(listOf(0, 1), SpeakerGatePolicy.spread(listOf(0, 1)))
        assertEquals(listOf(0, 5, 9), SpeakerGatePolicy.spread((0..9).toList()))
    }
}
