package com.fersaiyan.cyanbridge.shared.ui.chat

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fersaiyan.cyanbridge.shared.chat.ChatAttachmentsUiState
import com.fersaiyan.cyanbridge.shared.chat.ChatComposerPrimaryAction
import com.fersaiyan.cyanbridge.shared.chat.ChatComposerUiState
import com.fersaiyan.cyanbridge.shared.chat.DailySummaryProgressUiState
import com.fersaiyan.cyanbridge.shared.chat.ChatMessage
import com.fersaiyan.cyanbridge.shared.chat.ChatRole
import com.fersaiyan.cyanbridge.shared.chat.ChatThreadUiState
import com.fersaiyan.cyanbridge.shared.icons.AppIcon
import com.fersaiyan.cyanbridge.shared.navigation.AppDestination
import com.fersaiyan.cyanbridge.shared.icons.imageVector
import com.fersaiyan.cyanbridge.shared.navigation.icon
import com.fersaiyan.cyanbridge.shared.generated.resources.Res
import com.fersaiyan.cyanbridge.shared.generated.resources.action_new_chat
import com.fersaiyan.cyanbridge.shared.generated.resources.chat_appearance
import com.fersaiyan.cyanbridge.shared.generated.resources.chat_attach_image
import com.fersaiyan.cyanbridge.shared.generated.resources.chat_clear_attachments
import com.fersaiyan.cyanbridge.shared.generated.resources.chat_configure_local_model
import com.fersaiyan.cyanbridge.shared.generated.resources.chat_empty_body
import com.fersaiyan.cyanbridge.shared.generated.resources.chat_empty_title
import com.fersaiyan.cyanbridge.shared.generated.resources.chat_local_model_required
import com.fersaiyan.cyanbridge.shared.generated.resources.chat_list
import com.fersaiyan.cyanbridge.shared.generated.resources.chat_message
import com.fersaiyan.cyanbridge.shared.generated.resources.notes_and_chats
import com.fersaiyan.cyanbridge.shared.generated.resources.chat_record_audio
import com.fersaiyan.cyanbridge.shared.generated.resources.chat_stop_audio_recording
import com.fersaiyan.cyanbridge.shared.generated.resources.chat_stop_generation
import com.fersaiyan.cyanbridge.shared.generated.resources.chat_thinking
import com.fersaiyan.cyanbridge.shared.generated.resources.action_send
import com.fersaiyan.cyanbridge.shared.ui.localizedDestinationLabel
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.stringResource
import kotlinx.coroutines.flow.collect

/**
 * Shared CMP chat surface. The host supplies platform callbacks
 * for inference, storage, permissions, and media so this layout owns only UI.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalResourceApi::class)
@Composable
fun ChatThreadScreen(
    state: ChatThreadUiState,
    messages: List<ChatMessage>,
    composer: ChatComposerUiState,
    attachments: ChatAttachmentsUiState,
    modelBadge: String?,
    dailySummaryProgress: DailySummaryProgressUiState?,
    dailyReviewQueueStatus: String?,
    userBubbleColor: Int?,
    assistantBubbleColor: Int?,
    wallpaper: ImageBitmap?,
    isThinking: Boolean,
    onOpenChatList: () -> Unit,
    onChatAppearance: () -> Unit,
    onComposerTextChanged: (String) -> Unit,
    onPrimaryAction: () -> Unit,
    onAttachImage: () -> Unit,
    onRecordAudio: () -> Unit,
    onClearAttachments: () -> Unit,
    onDestinationSelected: (AppDestination) -> Unit,
) {
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = state.thread?.title ?: stringResource(Res.string.action_new_chat),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    TextButton(onClick = onOpenChatList) {
                        Icon(
                            imageVector = AppIcon.Back.imageVector(),
                            contentDescription = stringResource(Res.string.chat_list),
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.size(4.dp))
                        Text(stringResource(Res.string.notes_and_chats))
                    }
                },
                actions = {
                    IconButton(onClick = onChatAppearance) {
                        Icon(
                            imageVector = AppIcon.More.imageVector(),
                             contentDescription = stringResource(Res.string.chat_appearance),
                        )
                    }
                },
            )
        },
        bottomBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("chat_bottom_controls"),
            ) {
                ChatComposer(
                    state = state,
                    composer = composer,
                    attachments = attachments,
                    onComposerTextChanged = onComposerTextChanged,
                    onPrimaryAction = onPrimaryAction,
                    onAttachImage = onAttachImage,
                    onRecordAudio = onRecordAudio,
                    onClearAttachments = onClearAttachments,
                    modifier = Modifier.imePadding(),
                )
                if (!imeVisible) {
                    ChatNavigationBar(onDestinationSelected = onDestinationSelected)
                }
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding),
        ) {
            ChatThreadStatus(
                modelBadge = modelBadge,
                dailySummaryProgress = dailySummaryProgress,
                dailyReviewQueueStatus = dailyReviewQueueStatus,
            )
            ChatTimeline(
                messages = messages,
                wallpaper = wallpaper,
                userBubbleColor = userBubbleColor,
                assistantBubbleColor = assistantBubbleColor,
                isThinking = isThinking,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun ChatThreadStatus(
    modelBadge: String?,
    dailySummaryProgress: DailySummaryProgressUiState?,
    dailyReviewQueueStatus: String?,
) {
    if (modelBadge == null && dailySummaryProgress == null && dailyReviewQueueStatus == null) return

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        modelBadge?.let { label ->
            StatusSurface(label)
        }
        dailySummaryProgress?.let { progress ->
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                shape = MaterialTheme.shapes.medium,
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(progress.label, style = MaterialTheme.typography.labelMedium)
                    LinearProgressIndicator(
                        progress = { progress.progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        dailyReviewQueueStatus?.let { label ->
            StatusSurface(label)
        }
    }
}

@Composable
private fun StatusSurface(label: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shape = MaterialTheme.shapes.medium,
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
private fun ChatTimeline(
    messages: List<ChatMessage>,
    wallpaper: ImageBitmap?,
    userBubbleColor: Int?,
    assistantBubbleColor: Int?,
    isThinking: Boolean,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    var isNearBottom by remember { mutableStateOf(true) }
    var completedInitialScroll by remember { mutableStateOf(false) }
    val finalItemIndex = messages.lastIndex + if (isThinking) 1 else 0

    LaunchedEffect(listState, messages.size, isThinking) {
        snapshotFlow {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            lastVisible >= finalItemIndex - 1
        }.collect { isNearBottom = it }
    }
    LaunchedEffect(messages.size, isThinking, messages.lastOrNull()?.content) {
        if (finalItemIndex >= 0 && (!completedInitialScroll || isThinking || isNearBottom)) {
            listState.scrollToItem(finalItemIndex)
            completedInitialScroll = true
        }
    }

    Box(modifier = modifier.fillMaxWidth()) {
        wallpaper?.let { image ->
            Image(
                bitmap = image,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(0.22f),
                contentScale = ContentScale.Crop,
            )
        }
        if (messages.isEmpty() && !isThinking) {
            EmptyConversation(modifier = Modifier.fillMaxSize())
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("chat_messages"),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    top = 12.dp,
                    end = 16.dp,
                    bottom = 12.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(messages, key = { it.id }) { message ->
                    ChatMessageBubble(
                        message = message,
                        userBubbleColor = userBubbleColor,
                        assistantBubbleColor = assistantBubbleColor,
                    )
                }
                if (isThinking) {
                    item(key = "thinking") {
                        ThinkingIndicator()
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyConversation(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = AppIcon.Chat.imageVector(),
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(16.dp))
         Text(stringResource(Res.string.chat_empty_title), style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
             text = stringResource(Res.string.chat_empty_body),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun ChatMessageBubble(
    message: ChatMessage,
    userBubbleColor: Int?,
    assistantBubbleColor: Int?,
) {
    val isUser = message.role == ChatRole.USER
    val background = when {
        isUser && userBubbleColor != null -> Color(userBubbleColor)
        !isUser && assistantBubbleColor != null -> Color(assistantBubbleColor)
        isUser -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = if (background.luminance() > 0.6f) Color.Black else Color.White
    val shape = if (isUser) {
        RoundedCornerShape(topStart = 20.dp, topEnd = 4.dp, bottomStart = 20.dp, bottomEnd = 20.dp)
    } else {
        RoundedCornerShape(topStart = 4.dp, topEnd = 20.dp, bottomStart = 20.dp, bottomEnd = 20.dp)
    }

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val maxBubbleWidth = maxWidth * 0.82f
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
        ) {
            Surface(
                modifier = Modifier.widthIn(max = maxBubbleWidth),
                color = background,
                contentColor = contentColor,
                shape = shape,
            ) {
                ChatRichText(
                    markdown = message.content,
                    color = contentColor,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        }
    }
}

@Composable
private fun ThinkingIndicator() {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shape = MaterialTheme.shapes.large,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
            )
             Text(stringResource(Res.string.chat_thinking), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun ChatComposer(
    state: ChatThreadUiState,
    composer: ChatComposerUiState,
    attachments: ChatAttachmentsUiState,
    onComposerTextChanged: (String) -> Unit,
    onPrimaryAction: () -> Unit,
    onAttachImage: () -> Unit,
    onRecordAudio: () -> Unit,
    onClearAttachments: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val inputLabel = if (composer.primaryAction == ChatComposerPrimaryAction.CONFIGURE_LOCAL_MODEL) {
         stringResource(Res.string.chat_local_model_required)
    } else {
         stringResource(Res.string.chat_message)
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        tonalElevation = 3.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
                .testTag("chat_composer"),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            attachments.label?.let { label ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = label,
                        modifier = Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelMedium,
                    )
                    IconButton(onClick = onClearAttachments) {
                        Icon(
                            imageVector = AppIcon.Close.imageVector(),
                             contentDescription = stringResource(Res.string.chat_clear_attachments),
                        )
                    }
                }
            }
            OutlinedTextField(
                value = state.composerText,
                onValueChange = onComposerTextChanged,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("chat_input"),
                enabled = composer.isTextInputEnabled,
                label = { Text(inputLabel) },
                placeholder = if (composer.hint == "Message") {
                    null
                } else {
                    {
                        Text(
                            text = composer.hint,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                minLines = 1,
                maxLines = 4,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                IconButton(
                    onClick = onAttachImage,
                    enabled = composer.isMediaEnabled,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        imageVector = AppIcon.Attachment.imageVector(),
                 contentDescription = stringResource(Res.string.chat_attach_image),
                    )
                }
                FilledIconButton(
                    onClick = onRecordAudio,
                    enabled = composer.isMediaEnabled,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        imageVector = if (attachments.isRecording) {
                            AppIcon.Stop.imageVector()
                        } else {
                            AppIcon.Microphone.imageVector()
                        },
                        contentDescription = if (attachments.isRecording) {
                             stringResource(Res.string.chat_stop_audio_recording)
                        } else {
                            stringResource(Res.string.chat_record_audio)
                        },
                    )
                }
                FilledIconButton(
                    onClick = onPrimaryAction,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        imageVector = when (composer.primaryAction) {
                            ChatComposerPrimaryAction.SEND -> AppIcon.Send.imageVector()
                            ChatComposerPrimaryAction.STOP_GENERATION -> AppIcon.Stop.imageVector()
                            ChatComposerPrimaryAction.CONFIGURE_LOCAL_MODEL -> AppIcon.Model.imageVector()
                        },
                        contentDescription = when (composer.primaryAction) {
                             ChatComposerPrimaryAction.SEND -> stringResource(Res.string.action_send)
                             ChatComposerPrimaryAction.STOP_GENERATION -> stringResource(Res.string.chat_stop_generation)
                             ChatComposerPrimaryAction.CONFIGURE_LOCAL_MODEL -> stringResource(Res.string.chat_configure_local_model)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ChatNavigationBar(onDestinationSelected: (AppDestination) -> Unit) {
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
}
