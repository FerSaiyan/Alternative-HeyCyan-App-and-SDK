package com.fersaiyan.cyanbridge.shared.ui.recordings

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Image as ImageIcon
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.fersaiyan.cyanbridge.shared.recordings.MeetingRecordingUiState
import com.fersaiyan.cyanbridge.shared.recordings.RecordingItem
import com.fersaiyan.cyanbridge.shared.recordings.SyncedMediaItem
import com.fersaiyan.cyanbridge.shared.recordings.TranscriptDialogUiState
import com.fersaiyan.cyanbridge.shared.recordings.TranscriptionProgressUiState
import com.fersaiyan.cyanbridge.shared.navigation.AppDestination
import com.fersaiyan.cyanbridge.shared.navigation.icon
import com.fersaiyan.cyanbridge.shared.icons.imageVector
import com.fersaiyan.cyanbridge.shared.generated.resources.*
import com.fersaiyan.cyanbridge.shared.ui.localizedDestinationLabel
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3Api::class, ExperimentalResourceApi::class)
@Composable
fun RecordingsScreen(
    sessions: List<RecordingItem>,
    isLoading: Boolean,
    recentSyncedMedia: List<SyncedMediaItem>,
    playingSessionId: Long?,
    transcribingSessionId: Long?,
    meetingRecording: MeetingRecordingUiState,
    transcriptionProgress: TranscriptionProgressUiState?,
    transcriptDialog: TranscriptDialogUiState?,
    formatTimestamp: (Long) -> String,
    loadThumbnail: suspend (String) -> ImageBitmap?,
    onOpenSyncedMedia: () -> Unit,
    onOpenSyncedMediaItem: (SyncedMediaItem) -> Unit,
    onPlay: (RecordingItem) -> Unit,
    onTranscribe: (RecordingItem) -> Unit,
    onViewTranscript: (RecordingItem) -> Unit,
    onStopMeetingCapture: () -> Unit,
    onDismissTranscript: () -> Unit,
    onDestinationSelected: (AppDestination) -> Unit = {},
) {
    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                 title = { Text(stringResource(Res.string.recordings_title)) },
                actions = {
                    IconButton(onClick = onOpenSyncedMedia) {
                        Icon(
                            imageVector = Icons.Outlined.ImageIcon,
                             contentDescription = stringResource(Res.string.recordings_open_synced_media),
                        )
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                AppDestination.entries.forEach { destination ->
                    NavigationBarItem(
                        selected = destination == AppDestination.MEDIA,
                        onClick = { onDestinationSelected(destination) },
                        icon = {
                            Icon(
                                imageVector = destination.icon.imageVector(),
                                contentDescription = null,
                            )
                        },
                         label = { Text(localizedDestinationLabel(destination)) },
                    )
                }
            }
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding),
            contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (meetingRecording.isRecording) {
                item {
                    MeetingRecordingBanner(
                        sourceLabel = meetingRecording.sourceLabel,
                        onStop = onStopMeetingCapture,
                    )
                }
            }
            if (recentSyncedMedia.isNotEmpty()) {
                item {
                    Text(
                         text = stringResource(Res.string.recordings_recent_synced_photos),
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("recent_synced_media"),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        recentSyncedMedia.take(4).forEach { item ->
                            var thumbnail by remember(item.contentUriString) {
                                mutableStateOf<ImageBitmap?>(null)
                            }
                            LaunchedEffect(item.contentUriString) {
                                thumbnail = loadThumbnail(item.contentUriString)
                            }
                            SyncedMediaPreview(
                                item = item,
                                thumbnail = thumbnail,
                                modifier = Modifier.weight(1f),
                                onClick = { onOpenSyncedMediaItem(item) },
                            )
                        }
                    }
                }
            }
            item {
                OutlinedButton(
                    onClick = onOpenSyncedMedia,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.ImageIcon,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                     Text(stringResource(Res.string.recordings_open_synced_media))
                }
            }
            if (isLoading) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 40.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }
            } else if (sessions.isEmpty()) {
                item {
                    EmptyRecordingsState()
                }
            } else {
                item {
                    Text(
                         text = stringResource(Res.string.recordings_meeting_captures),
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
                items(sessions, key = { it.id }) { session ->
                    RecordingCard(
                        title = session.title,
                        metadata = session.metadata,
                        stopReason = session.stopReason,
                        isPlaying = playingSessionId == session.id,
                        isTranscribing = transcribingSessionId == session.id,
                        onPlay = { onPlay(session) },
                        onTranscribe = { onTranscribe(session) },
                        onViewTranscript = { onViewTranscript(session) },
                    )
                }
            }
        }
    }

    transcriptionProgress?.let { progress ->
        TranscriptionProgressDialog(progress)
    }
    transcriptDialog?.let { transcript ->
        AlertDialog(
            onDismissRequest = onDismissTranscript,
            title = { Text(transcript.title) },
            text = { Text(transcript.text) },
            confirmButton = {
                TextButton(onClick = onDismissTranscript) {
                     Text(stringResource(Res.string.recordings_close))
                }
            },
        )
    }
}

@Composable
private fun MeetingRecordingBanner(
    sourceLabel: String?,
    onStop: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 10.dp, end = 8.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                 Text(stringResource(Res.string.settings_recording_active), style = MaterialTheme.typography.titleSmall)
                Text(
                     text = sourceLabel ?: stringResource(Res.string.settings_detecting_audio_source),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            TextButton(onClick = onStop) {
                Icon(
                    imageVector = Icons.Outlined.Stop,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(4.dp))
                 Text(stringResource(Res.string.action_stop))
            }
        }
    }
}

@Composable
private fun SyncedMediaPreview(
    item: SyncedMediaItem,
    thumbnail: ImageBitmap?,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Card(
        modifier = modifier
            .aspectRatio(1f)
            .testTag("recent_synced_media_${item.id}")
            .clickable(onClick = onClick),
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (thumbnail != null) {
                Image(
                    bitmap = thumbnail,
                    contentDescription = item.displayName,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Icon(
                    imageVector = Icons.Outlined.ImageIcon,
                    contentDescription = item.displayName,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun EmptyRecordingsState() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
             Text(stringResource(Res.string.recordings_no_recordings), style = MaterialTheme.typography.titleMedium)
            Text(
                 text = stringResource(Res.string.recordings_no_recordings_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RecordingCard(
    title: String,
    metadata: String,
    stopReason: String?,
    isPlaying: Boolean,
    isTranscribing: Boolean,
    onPlay: () -> Unit,
    onTranscribe: () -> Unit,
    onViewTranscript: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onPlay) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                         contentDescription = stringResource(
                             if (isPlaying) Res.string.recordings_stop_playback else Res.string.recordings_play_recording,
                         ),
                    )
                }
                Spacer(Modifier.width(4.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = metadata,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    stopReason?.takeIf { it.isNotBlank() }?.let { reason ->
                        Text(
                             text = stringResource(Res.string.recordings_stopped_reason, reason),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(
                    onClick = onTranscribe,
                    enabled = !isTranscribing,
                ) {
                    if (isTranscribing) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                         Text(stringResource(Res.string.recordings_transcribing))
                    } else {
                         Text(stringResource(Res.string.recordings_transcribe))
                    }
                }
                OutlinedButton(
                    onClick = onViewTranscript,
                    enabled = !isTranscribing,
                ) {
                     Text(stringResource(Res.string.recordings_view_transcript))
                }
            }
        }
    }
}

@Composable
private fun TranscriptionProgressDialog(state: TranscriptionProgressUiState) {
    val progress = state.progress
    Dialog(onDismissRequest = {}) {
        Card {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(state.title, style = MaterialTheme.typography.titleLarge)
                Text(
                    state.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (progress == null) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(
                        progress = { progress / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}
