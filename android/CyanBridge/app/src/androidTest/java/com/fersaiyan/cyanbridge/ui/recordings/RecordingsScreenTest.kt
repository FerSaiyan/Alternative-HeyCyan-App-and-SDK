package com.fersaiyan.cyanbridge.ui.recordings

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.fersaiyan.cyanbridge.shared.recordings.MeetingRecordingUiState
import com.fersaiyan.cyanbridge.shared.recordings.RecordingItem
import com.fersaiyan.cyanbridge.shared.recordings.SyncedMediaItem
import com.fersaiyan.cyanbridge.shared.ui.recordings.RecordingsScreen
import com.fersaiyan.cyanbridge.ui.theme.CyanBridgeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class RecordingsScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val noopTimestamp: (Long) -> String = { it.toString() }
    private val noopThumbnail: suspend (String) -> ImageBitmap? = { null }

    @Test
    fun rendersCaptureActionsAndRoutesCallbacks() {
        val session = RecordingItem(
            id = 7L,
            title = "Meeting capture",
            metadata = "60s · PHONE_MIC · HEY_CYAN",
            stopReason = null,
            durationSec = 60L,
            captureSource = "PHONE_MIC",
            deviceClass = "HEY_CYAN",
            startedAt = 1_700_000_000_000L,
        )
        var playedSessionId: Long? = null
        var transcribedSessionId: Long? = null
        var openedMedia = 0

        composeRule.setContent {
            CyanBridgeTheme {
                RecordingsScreen(
                    sessions = listOf(session),
                    isLoading = false,
                    recentSyncedMedia = emptyList(),
                    playingSessionId = null,
                    transcribingSessionId = null,
                    meetingRecording = MeetingRecordingUiState(),
                    transcriptionProgress = null,
                    transcriptDialog = null,
                    formatTimestamp = noopTimestamp,
                    loadThumbnail = noopThumbnail,
                    onOpenSyncedMedia = { openedMedia += 1 },
                    onOpenSyncedMediaItem = {},
                    onPlay = { playedSessionId = it.id },
                    onTranscribe = { transcribedSessionId = it.id },
                    onViewTranscript = {},
                    onStopMeetingCapture = {},
                    onDismissTranscript = {},
                    onDestinationSelected = {},
                )
            }
        }

        composeRule.onNodeWithText("Meeting captures").assertTextContains("Meeting captures")
        composeRule.onNodeWithContentDescription("Play recording").performClick()
        composeRule.onNodeWithText("Transcribe").performClick()
        composeRule.onNodeWithText("Open synced media").performClick()

        composeRule.runOnIdle {
            assertEquals(7L, playedSessionId)
            assertEquals(7L, transcribedSessionId)
            assertEquals(1, openedMedia)
        }
    }

    @Test
    fun showsFourRecentPhotosInOneFullWidthRow() {
        val recentMedia = List(4) { index ->
            SyncedMediaItem(
                id = index.toLong(),
                displayName = "photo_$index.jpg",
                contentUriString = "content://cyanbridge/media/$index",
                isVideo = false,
            )
        }
        var openedId: Long? = null

        composeRule.setContent {
            CyanBridgeTheme {
                RecordingsScreen(
                    sessions = emptyList(),
                    isLoading = false,
                    recentSyncedMedia = recentMedia,
                    playingSessionId = null,
                    transcribingSessionId = null,
                    meetingRecording = MeetingRecordingUiState(),
                    transcriptionProgress = null,
                    transcriptDialog = null,
                    formatTimestamp = noopTimestamp,
                    loadThumbnail = noopThumbnail,
                    onOpenSyncedMedia = {},
                    onOpenSyncedMediaItem = { openedId = it.id },
                    onPlay = {},
                    onTranscribe = {},
                    onViewTranscript = {},
                    onStopMeetingCapture = {},
                    onDismissTranscript = {},
                    onDestinationSelected = {},
                )
            }
        }

        composeRule.onNodeWithTag("recent_synced_media").assertExists()
        composeRule.onNodeWithTag("recent_synced_media_3").performClick()

        composeRule.runOnIdle {
            assertEquals(3L, openedId)
        }
    }
}
