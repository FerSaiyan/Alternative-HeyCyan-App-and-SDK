package com.fersaiyan.cyanbridge.plugins.walkingaid

import com.fersaiyan.cyanbridge.plugins.walkingaid.vision.DetectionResult

data class ExploreObjectGroup(val label: String, val position: String, val count: Int)

data class ExploreAnnouncement(val group: ExploreObjectGroup, val sceneRevision: Long)

/**
 * Small scene memory for YOLO-only narration. Counts are grouped by label and direction, so track
 * ID churn does not repeat an unchanged scene. A change must persist across observations before
 * it becomes narratable; an unconfirmed disappearance cannot clear announcement memory.
 */
class WalkingAidExploreEngine(
    private val confirmationMs: Long = 600L,
    private val maxFrameAgeMs: Long = WalkingAidWarningEngine.MAX_WARNING_FRAME_AGE_MS,
) {
    private data class Observation(val group: ExploreObjectGroup?, val sinceMs: Long, var count: Int = 1)

    private var candidate = emptyList<ExploreObjectGroup>()
    private var confirmed = emptyList<ExploreObjectGroup>()
    private val observations = mutableMapOf<Pair<String, String>, Observation>()
    private var latestFrameAtMs: Long? = null
    private var revision = 0L
    private val groupRevisions = mutableMapOf<ExploreObjectGroup, Long>()
    private val announced = mutableSetOf<ExploreObjectGroup>()

    @Synchronized
    fun update(detection: DetectionResult, frameTimestampMs: Long, nowMs: Long = System.currentTimeMillis()) {
        if (detection.isError || nowMs - frameTimestampMs > maxFrameAgeMs) {
            // Keep memory, but do not narrate old detections during an error or stale feed.
            latestFrameAtMs = null
            observations.clear()
            return
        }
        if (latestFrameAtMs?.let { frameTimestampMs <= it } == true) return
        val scene = detection.objects.asSequence()
            .filter { it.confidence.isFinite() && it.confidence >= 0.35f && it.label.isNotBlank() }
            .groupingBy { it.label to it.position }
            .eachCount()
            .map { (key, count) -> ExploreObjectGroup(key.first, key.second, count) }
            .sortedWith(compareBy<ExploreObjectGroup> { positionOrder(it.position) }.thenBy { it.label })
        latestFrameAtMs = frameTimestampMs
        candidate = scene
        val current = scene.associateBy { it.label to it.position }
        val stable = confirmed.associateBy { it.label to it.position }.toMutableMap()
        // Confirm each class/direction separately: one flickering detection must not silence the
        // rest of a crowded scene. Disappearances use the same confirmation as appearances.
        (observations.keys + current.keys + stable.keys).toSet().forEach { key ->
            val group = current[key]
            val previous = observations[key]
            val observation = if (previous != null && previous.group == group) {
                previous.apply { count++ }
            } else {
                Observation(group, nowMs).also { observations[key] = it }
            }
            if (observation.count >= 2 && nowMs - observation.sinceMs >= confirmationMs && stable[key] != group) {
                stable.remove(key)?.let {
                    announced.remove(it)
                    groupRevisions.remove(it)
                }
                if (group != null) {
                    stable[key] = group
                    groupRevisions[group] = ++revision
                }
            }
            if (group == null && key !in stable && nowMs - observation.sinceMs >= confirmationMs) {
                observations.remove(key)
            }
        }
        confirmed = stable.values.sortedWith(compareBy<ExploreObjectGroup> { positionOrder(it.position) }.thenBy { it.label })
    }

    @Synchronized
    fun nextAnnouncement(nowMs: Long = System.currentTimeMillis()): ExploreAnnouncement? {
        val frameAtMs = latestFrameAtMs ?: return null
        if (nowMs - frameAtMs > maxFrameAgeMs) return null
        // Never speak a queued group which has already disappeared from the newest frame.
        val group = confirmed.firstOrNull { it !in announced && it in candidate } ?: return null
        return ExploreAnnouncement(group, groupRevisions.getValue(group))
    }

    @Synchronized
    fun isCurrent(announcement: ExploreAnnouncement, nowMs: Long = System.currentTimeMillis()): Boolean {
        val frameAtMs = latestFrameAtMs ?: return false
        val observation = observations[announcement.group.label to announcement.group.position]
        val stillVisible = announcement.group in candidate ||
            (observation != null && nowMs - observation.sinceMs < confirmationMs)
        return nowMs - frameAtMs <= maxFrameAgeMs && stillVisible &&
            groupRevisions[announcement.group] == announcement.sceneRevision
    }

    @Synchronized
    fun complete(announcement: ExploreAnnouncement) {
        // Completion of an obsolete/interrupted scene must not mark its replacement as heard.
        if (groupRevisions[announcement.group] == announcement.sceneRevision) {
            announced += announcement.group
        }
    }

    @Synchronized
    fun reset() {
        candidate = emptyList()
        confirmed = emptyList()
        observations.clear()
        latestFrameAtMs = null
        announced.clear()
        groupRevisions.clear()
        revision++
    }

    private fun positionOrder(position: String): Int = when (position) {
        "center" -> 0
        "left" -> 1
        else -> 2
    }
}
