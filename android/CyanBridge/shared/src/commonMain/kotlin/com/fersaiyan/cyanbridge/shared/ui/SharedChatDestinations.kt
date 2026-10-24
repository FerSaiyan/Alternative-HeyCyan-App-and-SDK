package com.fersaiyan.cyanbridge.shared.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.fersaiyan.cyanbridge.shared.chat.ChatAppearanceMenuAction
import com.fersaiyan.cyanbridge.shared.generated.resources.Res
import com.fersaiyan.cyanbridge.shared.generated.resources.notes_edit_title
import com.fersaiyan.cyanbridge.shared.generated.resources.notes_new_title
import com.fersaiyan.cyanbridge.shared.generated.resources.notes_source_app
import com.fersaiyan.cyanbridge.shared.generated.resources.notes_source_meeting
import com.fersaiyan.cyanbridge.shared.navigation.AppDestination
import com.fersaiyan.cyanbridge.shared.notes.NoteSource
import com.fersaiyan.cyanbridge.shared.persistence.NoteEntity
import com.fersaiyan.cyanbridge.shared.platform.CyanBridgeServices
import com.fersaiyan.cyanbridge.shared.platform.SharedChatHooks
import com.fersaiyan.cyanbridge.shared.platform.SharedMediaHooks
import com.fersaiyan.cyanbridge.shared.prefs.AppPrefs
import com.fersaiyan.cyanbridge.shared.prefs.Pref
import com.fersaiyan.cyanbridge.shared.ui.chat.ChatAppearanceMenuDialog
import com.fersaiyan.cyanbridge.shared.ui.chat.ChatListEffect
import com.fersaiyan.cyanbridge.shared.ui.chat.ChatListViewModel
import com.fersaiyan.cyanbridge.shared.ui.chat.ChatThreadArgs
import com.fersaiyan.cyanbridge.shared.ui.chat.ChatThreadEffect
import com.fersaiyan.cyanbridge.shared.ui.chat.ChatThreadScreen
import com.fersaiyan.cyanbridge.shared.ui.chat.ChatThreadViewModel
import com.fersaiyan.cyanbridge.shared.ui.chat.NotesChatsScreen
import com.fersaiyan.cyanbridge.shared.ui.notes.MarkdownNoteEditorScreen
import com.fersaiyan.cyanbridge.shared.ui.notes.NoteEditorEvent
import com.fersaiyan.cyanbridge.shared.ui.notes.NoteEditorViewModel
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.stringResource

// Preset bubble colors cycled by the appearance menu (ARGB).
private val USER_BUBBLE_COLORS = listOf(0xFF1E6A7A, 0xFF3A5BA0, 0xFF6B4FA0, 0xFF2E7D32, 0xFFB3261E, 0xFF8B5000)
    .map { it.toInt() }
private val ASSISTANT_BUBBLE_COLORS = listOf(0xFFE3EEF0, 0xFFE8E0F0, 0xFFF3E9DC, 0xFFE2F0E3, 0xFFF0E0E0, 0xFFEDEDED)
    .map { it.toInt() }

/** The chat a destination shows; a null [id] is a new chat, created with its first message. */
private data class OpenChat(val id: String?)

/** Chats and notes for hosts that render shared destinations (iOS). */
@Composable
internal fun SharedChatsDestination(onDestinationSelected: (AppDestination) -> Unit) {
    val viewModel = rememberScreenViewModel(Unit) { ChatListViewModel(SharedChatListSource) }
    var openChat by remember {
        mutableStateOf(SharedChatHooks.initialThreadId?.let(::OpenChat).also { SharedChatHooks.initialThreadId = null })
    }
    var editingNote by remember { mutableStateOf<Pair<NoteEntity, Boolean>?>(null) }
    val scope = rememberCoroutineScope()

    DisposableEffect(viewModel) {
        viewModel.onStart()
        viewModel.refresh()
        onDispose { viewModel.onStop() }
    }
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            // There is one kind of chat here and no local models to install.
            if (effect == ChatListEffect.ChooseNewChat) openChat = OpenChat(null)
        }
    }

    editingNote?.let { (note, isNew) ->
        SharedNoteEditorDestination(note = note, isNew = isNew, onClose = { editingNote = null })
        return
    }

    openChat?.let { chat ->
        SharedChatThreadDestination(
            chat = chat,
            onBack = {
                openChat = null
                viewModel.refresh()
            },
            onDestinationSelected = onDestinationSelected,
        )
        return
    }

    NotesChatsScreen(
        selectedTab = viewModel.selectedTab,
        onTabSelected = { viewModel.selectedTab = it },
        threads = viewModel.threads,
        pendingDelete = viewModel.pendingDelete,
        notes = viewModel.notes,
        formatTimestamp = sharedTimestampFormatter(),
        onOpenThread = { openChat = OpenChat(it.id) },
        onRequestDelete = { viewModel.pendingDelete = it },
        onConfirmDelete = viewModel::confirmDelete,
        onDismissDelete = { viewModel.pendingDelete = null },
        onNewChat = viewModel::newChat,
        onOpenNote = { summary ->
            scope.launch {
                CyanBridgeServices.notesRepository.getAllNotes().firstOrNull { it.summaryId() == summary.id }
                    ?.let { editingNote = it to false }
            }
        },
        onNewNote = {
            val now = sharedNowMillis()
            editingNote = NoteEntity(id = "note-$now", title = "", content = "", createdAt = now, updatedAt = now, source = "manual") to true
        },
        onChatAppearance = { viewModel.appearanceMenuVisible = true },
        // Obsidian sync relies on Android storage access; iOS has no equivalent yet.
        onOpenNotesSettings = {},
        onDestinationSelected = onDestinationSelected,
        showNavigationBar = false,
    )

    if (viewModel.appearanceMenuVisible) {
        ChatAppearanceMenuDialog(
            modelOptionLabel = null,
            onDismissRequest = { viewModel.appearanceMenuVisible = false },
            onAction = { action ->
                viewModel.appearanceMenuVisible = false
                scope.launch { applyChatAppearanceAction(action) }
            },
        )
    }
}

@Composable
private fun SharedChatThreadDestination(
    chat: OpenChat,
    onBack: () -> Unit,
    onDestinationSelected: (AppDestination) -> Unit,
) {
    val viewModel = rememberScreenViewModel(chat) { ChatThreadViewModel(SharedChatStore, SharedChatBackend, SharedChatRecorder) }
    val scope = rememberCoroutineScope()
    // Notices (toasts on Android) show in the status line until the next message is sent.
    var notice by remember(chat) { mutableStateOf<String?>(null) }

    LaunchedEffect(viewModel) {
        viewModel.openOnce(ChatThreadArgs(chatId = chat.id))
        viewModel.effects.collect { effect ->
            if (effect is ChatThreadEffect.Message) notice = effect.message.text
        }
    }

    SystemBackHandler(onBack = onBack)
    ChatThreadScreen(
        state = viewModel.state,
        messages = viewModel.visibleMessages,
        composer = viewModel.composer,
        attachments = viewModel.attachments,
        modelBadge = viewModel.modelBadge,
        dailySummaryProgress = null,
        dailyReviewQueueStatus = viewModel.dailyReviewQueueStatus ?: notice,
        userBubbleColor = viewModel.userBubbleColor,
        assistantBubbleColor = viewModel.assistantBubbleColor,
        wallpaper = viewModel.wallpaper,
        isThinking = viewModel.isThinking,
        onOpenChatList = onBack,
        onChatAppearance = { viewModel.appearanceMenuVisible = true },
        onComposerTextChanged = viewModel::updateComposerText,
        onPrimaryAction = {
            notice = null
            viewModel.onPrimaryAction()
        },
        onAttachImage = {
            val pick = SharedChatHooks.pickImage ?: return@ChatThreadScreen
            scope.launch { pick()?.let { viewModel.addImageAttachment(attachPickedImage(it)) } }
        },
        onRecordAudio = viewModel::onRecordAudio,
        onClearAttachments = viewModel::clearAttachments,
        onDestinationSelected = { destination ->
            if (destination == AppDestination.CHATS) onBack() else onDestinationSelected(destination)
        },
        showNavigationBar = false,
    )

    if (viewModel.appearanceMenuVisible) {
        ChatAppearanceMenuDialog(
            modelOptionLabel = null,
            onDismissRequest = { viewModel.appearanceMenuVisible = false },
            onAction = { action ->
                viewModel.appearanceMenuVisible = false
                scope.launch {
                    applyChatAppearanceAction(action)
                    viewModel.refreshAppearance()
                }
            },
        )
    }
}

@OptIn(ExperimentalResourceApi::class)
@Composable
private fun SharedNoteEditorDestination(note: NoteEntity, isNew: Boolean, onClose: () -> Unit) {
    val viewModel = rememberScreenViewModel(note.id) { NoteEditorViewModel(SharedNoteStore(note, isNew)) }
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            if (event == NoteEditorEvent.Saved || event == NoteEditorEvent.Close) onClose()
        }
    }

    fun exportText(): String = listOfNotNull(
        viewModel.title.takeIf { it.isNotBlank() }?.let { "# $it" },
        viewModel.body.text,
    ).joinToString("\n\n")

    SystemBackHandler(onBack = onClose)
    MarkdownNoteEditorScreen(
        screenTitle = stringResource(if (viewModel.isNew) Res.string.notes_new_title else Res.string.notes_edit_title),
        title = viewModel.title,
        tags = viewModel.tags,
        body = viewModel.body,
        sourceLabel = stringResource(
            if (viewModel.source == NoteSource.MEETING) Res.string.notes_source_meeting else Res.string.notes_source_app,
        ),
        isSaving = viewModel.isSaving,
        onTitleChange = { viewModel.title = it },
        onTagsChange = { viewModel.tags = it },
        onBodyChange = { viewModel.body = it },
        onSave = viewModel::save,
        onCopy = { SharedChatHooks.copyText?.invoke(exportText()) },
        onShare = { SharedChatHooks.shareText?.invoke(exportText()) },
        onBack = onClose,
    )
}

/** Chat appearance persisted for the iOS host (Android keeps its own in ChatAppearancePrefs). */
internal data class ChatAppearance(
    val userBubbleColor: Int?,
    val assistantBubbleColor: Int?,
    val hasWallpaper: Boolean,
) {
    companion object {
        private val prefs = AppPrefs.SharedChatAppearance

        fun load() = ChatAppearance(
            userBubbleColor = prefs.userBubble.get().takeIf { it != -1 }?.let(USER_BUBBLE_COLORS::getOrNull),
            assistantBubbleColor = prefs.assistantBubble.get().takeIf { it != -1 }
                ?.let(ASSISTANT_BUBBLE_COLORS::getOrNull),
            hasWallpaper = prefs.wallpaper.get(),
        )

        fun cycle(pref: Pref<Int>, size: Int) {
            pref.set((pref.get() + 1) % size)
        }

        fun setWallpaper(enabled: Boolean) = prefs.wallpaper.set(enabled)

        fun reset() = prefs.store().clear()
    }
}

private suspend fun applyChatAppearanceAction(action: ChatAppearanceMenuAction) {
    when (action) {
        ChatAppearanceMenuAction.CHANGE_USER_BUBBLE_COLOR -> ChatAppearance.cycle(AppPrefs.SharedChatAppearance.userBubble, USER_BUBBLE_COLORS.size)
        ChatAppearanceMenuAction.CHANGE_ASSISTANT_BUBBLE_COLOR ->
            ChatAppearance.cycle(AppPrefs.SharedChatAppearance.assistantBubble, ASSISTANT_BUBBLE_COLORS.size)
        ChatAppearanceMenuAction.CHOOSE_WALLPAPER -> {
            val image = SharedChatHooks.pickImage?.invoke() ?: return
            if (SharedMediaHooks.saveDocument?.invoke(WALLPAPER_FILE, image) != null) ChatAppearance.setWallpaper(true)
        }
        ChatAppearanceMenuAction.REMOVE_WALLPAPER -> ChatAppearance.setWallpaper(false)
        ChatAppearanceMenuAction.RESET_APPEARANCE -> ChatAppearance.reset()
        // Model choice lives in Settings ▸ AI provider on iOS.
        ChatAppearanceMenuAction.CHANGE_MODEL -> Unit
    }
}
