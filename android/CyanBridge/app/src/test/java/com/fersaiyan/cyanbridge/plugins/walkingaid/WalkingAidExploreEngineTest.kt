package com.fersaiyan.cyanbridge.plugins.walkingaid

import android.graphics.RectF
import com.fersaiyan.cyanbridge.plugins.walkingaid.vision.DetectedObject
import com.fersaiyan.cyanbridge.plugins.walkingaid.vision.DetectionResult
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class WalkingAidExploreEngineTest {
    private val engine = WalkingAidExploreEngine()

    @Test
    fun groupsCountsAndWaitsForConfirmationBeforeNarration() {
        val scene = scene("person", "person", "chair")
        engine.update(scene, 1_000, 1_000)
        assertNull(engine.nextAnnouncement(1_000))
        engine.update(scene, 1_700, 1_700)
        val first = requireNotNull(engine.nextAnnouncement(1_700))
        assertEquals(ExploreObjectGroup("chair", "center", 1), first.group)
        engine.complete(first)
        assertEquals(ExploreObjectGroup("person", "center", 2), engine.nextAnnouncement(1_700)?.group)
    }

    @Test
    fun duplicateFramesDoNotConfirmANewScene() {
        engine.update(scene("person"), 1_000, 1_000)
        engine.update(scene("person"), 1_000, 1_700)
        assertNull(engine.nextAnnouncement(1_700))
    }

    @Test
    fun completedSceneStaysQuietDespiteTrackIdChurn() {
        confirm(scene("person"))
        engine.complete(requireNotNull(engine.nextAnnouncement(1_700)))
        val changedTrackId = scene("person").let { it.copy(objects = it.objects.map { obj -> obj.copy(trackId = 99) }) }
        engine.update(changedTrackId, 2_000, 2_000)
        assertNull(engine.nextAnnouncement(2_000))
    }

    @Test
    fun flickeringObjectDoesNotPreventStableObjectsFromBeingAnnounced() {
        engine.update(scene("person", "chair"), 1_000, 1_000)
        engine.update(scene("person"), 1_300, 1_300)
        engine.update(scene("person", "chair"), 1_700, 1_700)
        assertEquals("person", engine.nextAnnouncement(1_700)?.group?.label)
    }

    @Test
    fun oneMissedFrameDoesNotForgetWhatWasHeard() {
        confirm(scene("person"))
        engine.complete(requireNotNull(engine.nextAnnouncement(1_700)))
        engine.update(scene(), 2_000, 2_000)
        engine.update(scene("person"), 2_200, 2_200)
        engine.update(scene("person"), 2_900, 2_900)
        assertNull(engine.nextAnnouncement(2_900))
    }

    @Test
    fun sustainedDisappearanceAllowsAnnouncementOnReturn() {
        confirm(scene("person"))
        engine.complete(requireNotNull(engine.nextAnnouncement(1_700)))
        engine.update(scene(), 2_000, 2_000)
        engine.update(scene(), 2_700, 2_700)
        engine.update(scene("person"), 3_000, 3_000)
        engine.update(scene("person"), 3_700, 3_700)
        assertNotNull(engine.nextAnnouncement(3_700))
    }

    @Test
    fun changedCountIsNarratedWithoutRepeatingUnchangedGroups() {
        confirm(scene("person", "chair"))
        repeat(2) { engine.complete(requireNotNull(engine.nextAnnouncement(1_700))) }
        engine.update(scene("person", "person", "chair"), 2_000, 2_000)
        engine.update(scene("person", "person", "chair"), 2_700, 2_700)
        val announcement = requireNotNull(engine.nextAnnouncement(2_700))
        assertEquals(ExploreObjectGroup("person", "center", 2), announcement.group)
        engine.complete(announcement)
        assertNull(engine.nextAnnouncement(2_700))
    }

    @Test
    fun staleAndFailedFramesCannotProduceNarration() {
        confirm(scene("person"))
        assertNull(engine.nextAnnouncement(8_000))
        engine.update(DetectionResult(objects = emptyList(), isError = true), 2_000, 2_000)
        assertNull(engine.nextAnnouncement(2_000))
    }

    @Test
    fun pendingObjectsArePrunedImmediatelyButSpeechToleratesBriefDetectionFlicker() {
        confirm(scene("person"))
        val announcement = requireNotNull(engine.nextAnnouncement(1_700))
        engine.update(scene("car"), 2_000, 2_000)
        assertNull(engine.nextAnnouncement(2_000))
        assertTrue(engine.isCurrent(announcement, 2_000))
        engine.update(scene("car"), 2_700, 2_700)
        assertFalse(engine.isCurrent(announcement, 2_700))
    }

    @Test
    fun completionSurvivesUnrelatedSceneChangesButNotRemovalAndReturn() {
        confirm(scene("person"))
        val person = requireNotNull(engine.nextAnnouncement(1_700))
        engine.update(scene("person", "chair"), 2_000, 2_000)
        engine.update(scene("person", "chair"), 2_700, 2_700)
        engine.complete(person)
        assertEquals("chair", engine.nextAnnouncement(2_700)?.group?.label)
        engine.update(scene(), 3_000, 3_000)
        engine.update(scene(), 3_700, 3_700)
        engine.update(scene("person"), 4_000, 4_000)
        engine.update(scene("person"), 4_700, 4_700)
        engine.complete(person)
        assertNotNull(engine.nextAnnouncement(4_700))
    }

    @Test
    fun interruptedSpeechIsNotRememberedAsCompleted() {
        confirm(scene("person"))
        val first = engine.nextAnnouncement(1_700)
        assertEquals(first, engine.nextAnnouncement(1_800))
        engine.reset()
        assertNull(engine.nextAnnouncement(1_800))
    }

    private fun confirm(detection: DetectionResult) {
        engine.update(detection, 1_000, 1_000)
        engine.update(detection, 1_700, 1_700)
    }

    private fun scene(vararg labels: String) = DetectionResult(objects = labels.mapIndexed { index, label ->
        DetectedObject(label, 0.9f, RectF(0.3f, 0.2f, 0.7f, 0.8f), "center", trackId = index.toLong())
    })
}
