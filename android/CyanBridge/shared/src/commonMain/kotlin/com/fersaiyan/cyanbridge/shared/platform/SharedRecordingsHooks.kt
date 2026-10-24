package com.fersaiyan.cyanbridge.shared.platform

import com.fersaiyan.cyanbridge.shared.recordings.MeetingRecordingUiState
import com.fersaiyan.cyanbridge.shared.recordings.RecordingItem
import kotlinx.coroutines.flow.StateFlow

/**
 * Meeting recordings provider for the shared Media destination. The iOS host
 * installs it; Android keeps RecordingsListActivity and leaves it unset.
 */
interface SharedRecordingsProvider {
    val meetingState: StateFlow<MeetingRecordingUiState>
    val playingId: StateFlow<Long?>

    suspend fun recordings(): List<RecordingItem>
    fun togglePlayback(id: Long)
    fun stopMeetingCapture()

    /** Transcribes, stores the transcript and a meeting-summary note; returns the transcript. */
    suspend fun transcribe(id: Long, onProgress: (String) -> Unit): String
    suspend fun transcript(id: Long): String?
    suspend fun delete(ids: List<Long>): Set<Long>
}

object SharedRecordingsHooks {
    var provider: SharedRecordingsProvider? = null
}
