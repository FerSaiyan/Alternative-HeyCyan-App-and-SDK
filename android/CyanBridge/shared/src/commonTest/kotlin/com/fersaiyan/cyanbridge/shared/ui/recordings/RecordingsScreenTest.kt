package com.fersaiyan.cyanbridge.shared.ui.recordings

import com.fersaiyan.cyanbridge.shared.recordings.RecordingItem
import com.fersaiyan.cyanbridge.shared.recordings.SyncedMediaItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RecordingsScreenTest {

    @Test
    fun recordingItemCapturesMetadata() {
        val item = RecordingItem(
            id = 1L,
            title = "Morning note",
            metadata = "opus 12s",
            stopReason = null,
            durationSec = 12L,
            captureSource = "bluetooth_mic",
            deviceClass = "heycyan",
            startedAt = 1_700_000_000_000L,
        )
        assertEquals(1L, item.id)
        assertEquals("Morning note", item.title)
        assertEquals(12L, item.durationSec)
        assertNull(item.stopReason)
    }

    @Test
    fun syncedMediaItemCapturesVideoFlag() {
        val image = SyncedMediaItem(
            id = 10L,
            displayName = "photo.jpg",
            contentUriString = "content://media/photo.jpg",
            isVideo = false,
        )
        val video = SyncedMediaItem(
            id = 20L,
            displayName = "clip.mp4",
            contentUriString = "content://media/clip.mp4",
            isVideo = true,
        )
        assertEquals(false, image.isVideo)
        assertEquals(true, video.isVideo)
    }
}
