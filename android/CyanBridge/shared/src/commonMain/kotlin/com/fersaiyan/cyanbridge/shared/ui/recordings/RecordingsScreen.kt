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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Image as ImageIcon
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
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
    onDeleteItems: suspend (List<RecordingItem>) -> Set<Long> = { emptySet() },
    onDismissTranscript: () -> Unit,
    onDestinationSelected: (AppDestination) -> Unit = {},
) {
    var selectionMode by remember { mutableStateOf(false) }
    var selectedSessionIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var pendingDelete by remember { mutableStateOf<List<RecordingItem>?>(null) }
    var isDeleting by remember { mutableStateOf(false) }
    var deletionFeedback by remember { mutableStateOf<RecordingDeletionFeedback?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    val selectableSessionIds = sessions
        .filter { it.id != transcribingSessionId }
        .map { it.id }
        .toSet()
    val selectedSessions = sessions.filter { it.id in selectedSessionIds }
    val allSelectableSessionsSelected =
        selectableSessionIds.isNotEmpty() && selectedSessionIds.containsAll(selectableSessionIds)

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    if (selectionMode) {
                        Text(stringResource(Res.string.recordings_selected, selectedSessions.size))
                    } else {
                        Text(stringResource(Res.string.recordings_title))
                    }
                },
                navigationIcon = {
                    if (selectionMode) {
                        IconButton(
                            onClick = {
                                selectionMode = false
                                selectedSessionIds = emptySet()
                            },
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                                contentDescription = stringResource(Res.string.recordings_cancel_selection),
                            )
                        }
                    }
                },
                actions = {
                    if (selectionMode) {
                        IconButton(
                            onClick = {
                                selectedSessionIds = if (allSelectableSessionsSelected) {
                                    emptySet()
                                } else {
                                    selectableSessionIds
                                }
                            },
                            enabled = !isDeleting,
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Check,
                                contentDescription = stringResource(
                                    if (allSelectableSessionsSelected) {
                                        Res.string.recordings_deselect_all
                                    } else {
                                        Res.string.recordings_select_all
                                    },
                                ),
                            )
                        }
                        IconButton(
                            onClick = { pendingDelete = selectedSessions },
                            enabled = selectedSessions.isNotEmpty() && !isDeleting,
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Delete,
                                contentDescription = stringResource(Res.string.recordings_delete_selected),
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    } else {
                        IconButton(onClick = onOpenSyncedMedia) {
                            Icon(
                                imageVector = Icons.Outlined.ImageIcon,
                                contentDescription = stringResource(Res.string.recordings_open_synced_media),
                            )
                        }
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                tonalElevation = 0.dp,
            ) {
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
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            selectedTextColor = MaterialTheme.colorScheme.onSurface,
                            indicatorColor = MaterialTheme.colorScheme.secondaryContainer,
                        ),
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
                        style = MaterialTheme.typography.titleMedium,
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
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
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
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(Res.string.recordings_meeting_captures),
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        if (!selectionMode) {
                            TextButton(
                                onClick = { selectionMode = true },
                                modifier = Modifier.heightIn(min = 48.dp),
                            ) {
                                Text(stringResource(Res.string.recordings_select))
                            }
                        }
                    }
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
                        selectionMode = selectionMode,
                        isSelected = selectedSessionIds.contains(session.id),
                        isDeleting = isDeleting,
                        onToggleSelection = {
                            if (session.id != transcribingSessionId && !isDeleting) {
                                selectedSessionIds = if (selectedSessionIds.contains(session.id)) {
                                    selectedSessionIds - session.id
                                } else {
                                    selectedSessionIds + session.id
                                }
                            }
                        },
                        onDelete = { pendingDelete = listOf(session) },
                    )
                }
            }
        }
    }

    deletionFeedback?.let { feedback ->
        val message = when {
            feedback.deletedCount == feedback.requestedCount && feedback.requestedCount == 1 -> {
                stringResource(Res.string.recordings_deleted)
            }
            feedback.deletedCount == feedback.requestedCount -> {
                stringResource(Res.string.recordings_deleted_multiple, feedback.deletedCount)
            }
            feedback.deletedCount > 0 -> {
                stringResource(
                    Res.string.recordings_deleted_partial,
                    feedback.deletedCount,
                    feedback.requestedCount,
                )
            }
            else -> stringResource(Res.string.recordings_delete_failed)
        }
        LaunchedEffect(feedback) {
            snackbarHostState.showSnackbar(message)
            deletionFeedback = null
        }
    }

    pendingDelete?.let { items ->
        val multiple = items.size > 1
        AlertDialog(
            onDismissRequest = { if (!isDeleting) pendingDelete = null },
            icon = {
                Surface(
                    modifier = Modifier.size(56.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Outlined.Delete,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
            },
            title = {
                Text(
                    stringResource(
                        if (multiple) {
                            Res.string.recordings_delete_multiple_title
                        } else {
                            Res.string.recordings_delete_title
                        },
                    ),
                )
            },
            text = {
                Text(
                    stringResource(
                        if (multiple) {
                            Res.string.recordings_delete_multiple_message
                        } else {
                            Res.string.recordings_delete_message
                        },
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (isDeleting) return@TextButton
                        pendingDelete = null
                        isDeleting = true
                        coroutineScope.launch {
                            val requestedIds = items.map { it.id }.toSet()
                            val deletedIds = try {
                                onDeleteItems(items).intersect(requestedIds)
                            } catch (_: Throwable) {
                                emptySet()
                            }
                            selectedSessionIds -= deletedIds
                            if (selectedSessionIds.isEmpty()) {
                                selectionMode = false
                            }
                            isDeleting = false
                            deletionFeedback = RecordingDeletionFeedback(
                                deletedCount = deletedIds.size,
                                requestedCount = requestedIds.size,
                            )
                        }
                    },
                    enabled = !isDeleting,
                ) {
                    Text(stringResource(Res.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { pendingDelete = null },
                    enabled = !isDeleting,
                ) {
                    Text(stringResource(Res.string.action_cancel))
                }
            },
        )
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
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, top = 14.dp, end = 12.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                 Text(stringResource(Res.string.settings_recording_active), style = MaterialTheme.typography.titleSmall)
                Text(
                     text = sourceLabel ?: stringResource(Res.string.settings_detecting_audio_source),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            TextButton(
                onClick = onStop,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
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
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
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
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(
                modifier = Modifier.size(64.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Outlined.ImageIcon,
                        contentDescription = null,
                        modifier = Modifier.size(28.dp),
                    )
                }
            }
            Text(
                stringResource(Res.string.recordings_no_recordings),
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(Res.string.recordings_no_recordings_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
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
    selectionMode: Boolean,
    isSelected: Boolean,
    isDeleting: Boolean,
    onToggleSelection: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                enabled = selectionMode && !isDeleting && !isTranscribing,
                onClick = onToggleSelection,
            ),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            },
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (selectionMode) {
                    Checkbox(
                        checked = isSelected,
                        onCheckedChange = { onToggleSelection() },
                        enabled = !isTranscribing && !isDeleting,
                    )
                }
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
                Box {
                    IconButton(
                        onClick = { menuExpanded = true },
                        enabled = !isDeleting,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.MoreVert,
                            contentDescription = stringResource(Res.string.recordings_more_actions, title),
                        )
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(Res.string.recordings_delete_capture)) },
                            onClick = {
                                menuExpanded = false
                                onDelete()
                            },
                            enabled = !isTranscribing && !isDeleting,
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Outlined.Delete,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            },
                        )
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(
                    onClick = onTranscribe,
                    enabled = !isTranscribing,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    if (isTranscribing) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 3.dp)
                        Spacer(Modifier.width(8.dp))
                         Text(stringResource(Res.string.recordings_transcribing))
                    } else {
                         Text(stringResource(Res.string.recordings_transcribe))
                    }
                }
                OutlinedButton(
                    onClick = onViewTranscript,
                    enabled = !isTranscribing,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                     Text(stringResource(Res.string.recordings_view_transcript))
                }
            }
        }
    }
}

private data class RecordingDeletionFeedback(
    val deletedCount: Int,
    val requestedCount: Int,
)

@Composable
private fun TranscriptionProgressDialog(state: TranscriptionProgressUiState) {
    val progress = state.progress
    Dialog(onDismissRequest = {}) {
        Card(
            shape = MaterialTheme.shapes.extraLarge,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
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
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp),
                        strokeCap = StrokeCap.Round,
                    )
                } else {
                    LinearProgressIndicator(
                        progress = { progress / 100f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp),
                        strokeCap = StrokeCap.Round,
                    )
                }
            }
        }
    }
}
