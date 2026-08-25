package com.fersaiyan.cyanbridge.shared.recordings

data class RecordingItem(
    val id: Long,
    val title: String,
    val metadata: String,
    val stopReason: String?,
    val durationSec: Long,
    val captureSource: String,
    val deviceClass: String,
    val startedAt: Long,
)

data class SyncedMediaItem(
    val id: Long,
    val displayName: String,
    val contentUriString: String,
    val isVideo: Boolean,
)

data class MeetingRecordingUiState(
    val isRecording: Boolean = false,
    val sourceLabel: String? = null,
)

data class TranscriptionProgressUiState(
    val title: String,
    val message: String,
    val progress: Int? = null,
)

data class TranscriptDialogUiState(
    val title: String,
    val text: String,
)
