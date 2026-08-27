package com.fersaiyan.cyanbridge.shared.ui.chat

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fersaiyan.cyanbridge.shared.chat.ChatThreadSummary
import com.fersaiyan.cyanbridge.shared.icons.AppIcon
import com.fersaiyan.cyanbridge.shared.icons.imageVector
import com.fersaiyan.cyanbridge.shared.navigation.AppDestination
import com.fersaiyan.cyanbridge.shared.navigation.icon
import com.fersaiyan.cyanbridge.shared.generated.resources.Res
import com.fersaiyan.cyanbridge.shared.generated.resources.action_cancel
import com.fersaiyan.cyanbridge.shared.generated.resources.action_delete
import com.fersaiyan.cyanbridge.shared.generated.resources.action_new_chat
import com.fersaiyan.cyanbridge.shared.generated.resources.chat_appearance
import com.fersaiyan.cyanbridge.shared.generated.resources.chat_delete_content_description
import com.fersaiyan.cyanbridge.shared.generated.resources.chat_delete_message
import com.fersaiyan.cyanbridge.shared.generated.resources.chat_delete_title
import com.fersaiyan.cyanbridge.shared.generated.resources.chat_no_chats
import com.fersaiyan.cyanbridge.shared.generated.resources.chat_recent_conversations
import com.fersaiyan.cyanbridge.shared.generated.resources.chat_start_with_add
import com.fersaiyan.cyanbridge.shared.generated.resources.notes_and_chats
import com.fersaiyan.cyanbridge.shared.ui.localizedDestinationLabel
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3Api::class, ExperimentalResourceApi::class)
@Composable
fun ChatListScreen(
    threads: List<ChatThreadSummary>,
    pendingDelete: ChatThreadSummary?,
    formatTimestamp: (Long) -> String,
    onOpenThread: (ChatThreadSummary) -> Unit,
    onRequestDelete: (ChatThreadSummary) -> Unit,
    onConfirmDelete: () -> Unit,
    onDismissDelete: () -> Unit,
    onNewChat: () -> Unit,
    onChatAppearance: () -> Unit,
    onDestinationSelected: (AppDestination) -> Unit,
) {
    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                 title = { Text(stringResource(Res.string.notes_and_chats)) },
                actions = {
                    PlatformKnowledgeIntegrationsTopBarAction()
                    IconButton(onClick = onChatAppearance) {
                        Icon(
                            imageVector = AppIcon.Appearance.imageVector(),
                             contentDescription = stringResource(Res.string.chat_appearance),
                        )
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
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
                    )
                }
            }
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onNewChat) {
                Icon(
                    imageVector = AppIcon.Add.imageVector(),
                     contentDescription = stringResource(Res.string.action_new_chat),
                )
            }
        },
    ) { innerPadding ->
        if (threads.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .consumeWindowInsets(innerPadding)
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    imageVector = AppIcon.Chat.imageVector(),
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                     text = stringResource(Res.string.chat_no_chats),
                    modifier = Modifier.padding(top = 16.dp),
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                     text = stringResource(Res.string.chat_start_with_add),
                    modifier = Modifier.padding(top = 8.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .consumeWindowInsets(innerPadding),
                contentPadding = PaddingValues(16.dp),
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
                    ThreadCard(
                        thread = thread,
                        formatTimestamp = formatTimestamp,
                        onOpen = { onOpenThread(thread) },
                        onDelete = { onRequestDelete(thread) },
                    )
                }
            }
        }
    }

    if (pendingDelete != null) {
        AlertDialog(
            onDismissRequest = onDismissDelete,
             title = { Text(stringResource(Res.string.chat_delete_title)) },
             text = { Text(stringResource(Res.string.chat_delete_message, pendingDelete.title)) },
            confirmButton = {
                 TextButton(onClick = onConfirmDelete) { Text(stringResource(Res.string.action_delete)) }
            },
            dismissButton = {
                 TextButton(onClick = onDismissDelete) { Text(stringResource(Res.string.action_cancel)) }
            },
        )
    }
}

@Composable
private fun ThreadCard(
    thread: ChatThreadSummary,
    formatTimestamp: (Long) -> String,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = thread.title,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.size(4.dp))
                Text(
                    text = formatTimestamp(thread.updatedAtEpochMillis),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = AppIcon.Delete.imageVector(),
                     contentDescription = stringResource(Res.string.chat_delete_content_description, thread.title),
                )
            }
        }
    }
}
