package com.fersaiyan.cyanbridge.shared.ui.chat

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fersaiyan.cyanbridge.shared.chat.ChatThreadSummary
import com.fersaiyan.cyanbridge.shared.generated.resources.Res
import com.fersaiyan.cyanbridge.shared.generated.resources.*
import com.fersaiyan.cyanbridge.shared.icons.AppIcon
import com.fersaiyan.cyanbridge.shared.icons.imageVector
import com.fersaiyan.cyanbridge.shared.navigation.AppDestination
import com.fersaiyan.cyanbridge.shared.navigation.icon
import com.fersaiyan.cyanbridge.shared.notes.NoteSummary
import com.fersaiyan.cyanbridge.shared.notes.NoteSource
import com.fersaiyan.cyanbridge.shared.ui.localizedDestinationLabel
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.stringResource

enum class NotesChatsTab {
    CHATS,
    NOTES,
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalResourceApi::class)
@Composable
fun NotesChatsScreen(
    selectedTab: NotesChatsTab,
    onTabSelected: (NotesChatsTab) -> Unit,
    threads: List<ChatThreadSummary>,
    pendingDelete: ChatThreadSummary?,
    notes: List<NoteSummary>,
    formatTimestamp: (Long) -> String,
    onOpenThread: (ChatThreadSummary) -> Unit,
    onRequestDelete: (ChatThreadSummary) -> Unit,
    onConfirmDelete: () -> Unit,
    onDismissDelete: () -> Unit,
    onNewChat: () -> Unit,
    onOpenNote: (NoteSummary) -> Unit,
    onNewNote: () -> Unit,
    onChatAppearance: () -> Unit,
    onOpenNotesSettings: () -> Unit,
    onDestinationSelected: (AppDestination) -> Unit,
) {
    val tabs = NotesChatsTab.entries
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.notes_and_chats)) },
                actions = {
                    IconButton(
                        onClick = if (selectedTab == NotesChatsTab.CHATS) onChatAppearance else onOpenNotesSettings,
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(
                            imageVector = if (selectedTab == NotesChatsTab.CHATS) {
                                AppIcon.Appearance.imageVector()
                            } else {
                                AppIcon.Settings.imageVector()
                            },
                            contentDescription = stringResource(
                                if (selectedTab == NotesChatsTab.CHATS) {
                                    Res.string.chat_appearance
                                } else {
                                    Res.string.notes_obsidian_settings
                                },
                            ),
                        )
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
                        selected = destination == AppDestination.CHATS,
                        onClick = { onDestinationSelected(destination) },
                        icon = {
                            Icon(
                                imageVector = destination.icon.imageVector(),
                                contentDescription = null,
                            )
                        },
                        label = { Text(localizedDestinationLabel(destination)) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            selectedTextColor = MaterialTheme.colorScheme.onSurface,
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                        ),
                    )
                }
            }
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = if (selectedTab == NotesChatsTab.CHATS) onNewChat else onNewNote,
                modifier = Modifier.testTag("notes_chats_primary_action"),
                shape = MaterialTheme.shapes.extraLarge,
            ) {
                Icon(
                    imageVector = AppIcon.Add.imageVector(),
                    contentDescription = stringResource(
                        if (selectedTab == NotesChatsTab.CHATS) {
                            Res.string.action_new_chat
                        } else {
                            Res.string.notes_create
                        },
                    ),
                )
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding),
        ) {
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                tabs.forEachIndexed { index, tab ->
                    SegmentedButton(
                        selected = selectedTab == tab,
                        onClick = { onTabSelected(tab) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = tabs.size),
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("notes_chats_tab_${tab.name.lowercase()}"),
                    ) {
                        Text(
                            stringResource(
                                if (tab == NotesChatsTab.CHATS) {
                                    Res.string.notes_chats_tab_chats
                                } else {
                                    Res.string.notes_chats_tab_notes
                                },
                            ),
                        )
                    }
                }
            }

            Box(modifier = Modifier.weight(1f)) {
                if (selectedTab == NotesChatsTab.CHATS) {
                    ChatsContent(
                        threads = threads,
                        formatTimestamp = formatTimestamp,
                        onOpenThread = onOpenThread,
                        onRequestDelete = onRequestDelete,
                    )
                } else {
                    NotesContent(
                        notes = notes,
                        formatTimestamp = formatTimestamp,
                        onOpenNote = onOpenNote,
                    )
                }
            }
        }
    }

    if (pendingDelete != null) {
        AlertDialog(
            onDismissRequest = onDismissDelete,
            icon = {
                DialogIcon(
                    icon = AppIcon.Delete,
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                )
            },
            title = { Text(stringResource(Res.string.chat_delete_title)) },
            text = { Text(stringResource(Res.string.chat_delete_message, pendingDelete.title)) },
            confirmButton = {
                TextButton(
                    onClick = onConfirmDelete,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text(stringResource(Res.string.action_delete)) }
            },
            dismissButton = {
                TextButton(
                    onClick = onDismissDelete,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text(stringResource(Res.string.action_cancel)) }
            },
        )
    }

}

@Composable
private fun ChatsContent(
    threads: List<ChatThreadSummary>,
    formatTimestamp: (Long) -> String,
    onOpenThread: (ChatThreadSummary) -> Unit,
    onRequestDelete: (ChatThreadSummary) -> Unit,
) {
    if (threads.isEmpty()) {
        EmptyState(
            icon = AppIcon.Chat,
            title = stringResource(Res.string.chat_no_chats),
            body = stringResource(Res.string.chat_start_with_add),
        )
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, top = 4.dp, end = 16.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text(
                text = stringResource(Res.string.chat_recent_conversations),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        items(threads, key = { it.id }) { thread ->
            UnifiedThreadCard(
                thread = thread,
                formatTimestamp = formatTimestamp,
                onOpen = { onOpenThread(thread) },
                onDelete = { onRequestDelete(thread) },
            )
        }
    }
}

@Composable
private fun NotesContent(
    notes: List<NoteSummary>,
    formatTimestamp: (Long) -> String,
    onOpenNote: (NoteSummary) -> Unit,
) {
    if (notes.isEmpty()) {
        EmptyState(
            icon = AppIcon.Notes,
            title = stringResource(Res.string.notes_chats_tab_notes),
            body = stringResource(Res.string.notes_no_notes_hint),
        )
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, top = 4.dp, end = 16.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(notes, key = { "${it.source}:${it.id}:${it.externalId.orEmpty()}" }) { note ->
            Card(
                onClick = { onOpenNote(note) },
                modifier = Modifier
                    .fillMaxWidth()
                    .animateContentSize(animationSpec = spring()),
                shape = MaterialTheme.shapes.extraLarge,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = note.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Surface(
                        modifier = Modifier.padding(top = 8.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        shape = MaterialTheme.shapes.large,
                    ) {
                        Text(
                            text = stringResource(
                                when (note.source) {
                                    NoteSource.APP -> Res.string.notes_source_app
                                    NoteSource.MEETING -> Res.string.notes_source_meeting
                                    NoteSource.OBSIDIAN -> Res.string.notes_source_obsidian
                                },
                            ),
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    if (note.summary.isNotBlank()) {
                        Text(
                            text = note.summary,
                            modifier = Modifier.padding(top = 6.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        text = formatTimestamp(note.createdAt),
                        modifier = Modifier.padding(top = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun UnifiedThreadCard(
    thread: ChatThreadSummary,
    formatTimestamp: (Long) -> String,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        onClick = onOpen,
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(animationSpec = spring()),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 72.dp)
                .padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = thread.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = formatTimestamp(thread.updatedAtEpochMillis),
                    modifier = Modifier.padding(top = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(48.dp),
            ) {
                Icon(
                    imageVector = AppIcon.Delete.imageVector(),
                    contentDescription = stringResource(Res.string.chat_delete_content_description, thread.title),
                )
            }
        }
    }
}

@Composable
private fun EmptyState(icon: AppIcon, title: String, body: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        DialogIcon(
            icon = icon,
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            size = 88.dp,
            iconSize = 40.dp,
        )
        Text(
            text = title,
            modifier = Modifier.padding(top = 20.dp),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        Text(
            text = body,
            modifier = Modifier.padding(top = 8.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun DialogIcon(
    icon: AppIcon,
    containerColor: androidx.compose.ui.graphics.Color,
    contentColor: androidx.compose.ui.graphics.Color,
    size: androidx.compose.ui.unit.Dp = 48.dp,
    iconSize: androidx.compose.ui.unit.Dp = 24.dp,
) {
    Surface(
        modifier = Modifier.size(size),
        shape = CircleShape,
        color = containerColor,
        contentColor = contentColor,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon.imageVector(),
                contentDescription = null,
                modifier = Modifier.size(iconSize),
            )
        }
    }
}
