package com.fersaiyan.cyanbridge.plugins.walkingaid

import android.graphics.RectF
import com.fersaiyan.cyanbridge.plugins.walkingaid.vision.DetectedObject
import com.fersaiyan.cyanbridge.plugins.walkingaid.vision.DetectionResult
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class WalkingAidWarningEngineTest {

    @Before
    fun setUp() {
        WalkingAidWarningEngine.reset()
    }

    @Test
    fun pluralWatchlistTermMatchesSingularCocoLabel() {
        val detection = DetectionResult(
            objects = listOf(
                DetectedObject(
                    label = "cat",
                    confidence = 0.9f,
                    boundingBox = RectF(0.25f, 0.2f, 0.75f, 0.8f),
                    position = "center",
                )
            )
        )

        val decision = WalkingAidWarningEngine.evaluate(
            detection,
            depth = null,
            focusDescription = "Please pay extra attention to cats",
        )

        assertTrue(decision.shouldWarn)
    }

    @Test
    fun misspelledWatchlistTermMatchesDetectedLabel() {
        val detection = DetectionResult(
            objects = listOf(
                DetectedObject(
                    label = "bicycle",
                    confidence = 0.9f,
                    boundingBox = RectF(0.05f, 0.2f, 0.2f, 0.45f),
                    position = "left",
                ),
            ),
        )

        val decision = WalkingAidWarningEngine.evaluate(
            detection,
            depth = null,
            focusDescription = "Please warn me about bicylces",
        )

        assertTrue(decision.shouldWarn)
    }

    @Test
    fun staleFrameNeverProducesWarningAndReportsAge() {
        val decision = WalkingAidWarningEngine.evaluate(
            detection = largePerson(trackId = 1L),
            depth = null,
            focusDescription = "",
            frameTimestampMs = 1_000L,
            nowMs = 8_000L,
        )

        assertFalse(decision.shouldWarn)
        assertTrue(decision.isStale)
        assertEquals(7_000L, decision.frameAgeMs)
    }

    @Test
    fun cooldownIsScopedToStableTrackId() {
        val first = WalkingAidWarningEngine.evaluate(
            largePerson(trackId = 10L),
            depth = null,
            focusDescription = "",
            frameTimestampMs = 10_000L,
            nowMs = 10_000L,
        )
        val sameTrack = WalkingAidWarningEngine.evaluate(
            largePerson(trackId = 10L),
            depth = null,
            focusDescription = "",
            frameTimestampMs = 11_000L,
            nowMs = 11_000L,
        )
        val differentTrack = WalkingAidWarningEngine.evaluate(
            largePerson(trackId = 11L),
            depth = null,
            focusDescription = "",
            frameTimestampMs = 11_000L,
            nowMs = 11_000L,
        )

        assertTrue(first.shouldWarn)
        assertFalse(sameTrack.shouldWarn)
        assertTrue(differentTrack.shouldWarn)
    }

    @Test
    fun approachingCooldownDoesNotFallThroughToObstacleWarning() {
        val approaching = largePerson(trackId = 20L, approaching = true)
        assertTrue(
            WalkingAidWarningEngine.evaluate(
                approaching, null, "", frameTimestampMs = 20_000L, nowMs = 20_000L,
            ).shouldWarn
        )

        val coolingDown = WalkingAidWarningEngine.evaluate(
            approaching, null, "", frameTimestampMs = 21_000L, nowMs = 21_000L,
        )

        assertFalse(coolingDown.shouldWarn)
    }

    private fun largePerson(trackId: Long, approaching: Boolean = false): DetectionResult = DetectionResult(
        objects = listOf(
            DetectedObject(
                label = "person",
                confidence = 0.9f,
                boundingBox = RectF(0.25f, 0.15f, 0.75f, 0.85f),
                position = "center",
                approaching = approaching,
                trackId = trackId,
            )
        )
    )
}
