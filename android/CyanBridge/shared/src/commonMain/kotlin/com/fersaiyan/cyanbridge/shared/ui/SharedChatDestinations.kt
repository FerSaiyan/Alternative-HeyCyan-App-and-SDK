package com.fersaiyan.cyanbridge.shared.ui

import com.fersaiyan.cyanbridge.shared.chat.ChatImageMarkup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.input.TextFieldValue
import com.fersaiyan.cyanbridge.shared.ai.ChatMessage as AiChatMessage
import com.fersaiyan.cyanbridge.shared.chat.ChatAppearanceMenuAction
import com.fersaiyan.cyanbridge.shared.chat.ChatAttachmentsUiState
import com.fersaiyan.cyanbridge.shared.chat.ChatComposerUiState
import com.fersaiyan.cyanbridge.shared.chat.ChatMessage
import com.fersaiyan.cyanbridge.shared.chat.ChatThread
import com.fersaiyan.cyanbridge.shared.chat.ChatThreadSummary
import com.fersaiyan.cyanbridge.shared.chat.ChatThreadUiState
import com.fersaiyan.cyanbridge.shared.generated.resources.Res
import com.fersaiyan.cyanbridge.shared.generated.resources.action_new_chat
import com.fersaiyan.cyanbridge.shared.generated.resources.chat_request_failed
import com.fersaiyan.cyanbridge.shared.generated.resources.notes_edit_title
import com.fersaiyan.cyanbridge.shared.generated.resources.notes_new_title
import com.fersaiyan.cyanbridge.shared.generated.resources.notes_source_app
import com.fersaiyan.cyanbridge.shared.generated.resources.notes_source_meeting
import com.fersaiyan.cyanbridge.shared.navigation.AppDestination
import com.fersaiyan.cyanbridge.shared.notes.NoteSource
import com.fersaiyan.cyanbridge.shared.notes.NoteSummary
import com.fersaiyan.cyanbridge.shared.persistence.ChatEntity
import com.fersaiyan.cyanbridge.shared.persistence.ChatMessageEntity
import com.fersaiyan.cyanbridge.shared.persistence.NoteEntity
import com.fersaiyan.cyanbridge.shared.platform.CyanBridgeServices
import com.fersaiyan.cyanbridge.shared.platform.PlatformFilePaths
import com.fersaiyan.cyanbridge.shared.platform.SharedChatHooks
import com.fersaiyan.cyanbridge.shared.platform.SharedMediaHooks
import com.fersaiyan.cyanbridge.shared.platform.createPlatformPreferences
import com.fersaiyan.cyanbridge.shared.ui.chat.ChatAppearanceMenuDialog
import com.fersaiyan.cyanbridge.shared.ui.chat.ChatThreadScreen
import com.fersaiyan.cyanbridge.shared.ui.chat.NotesChatsScreen
import com.fersaiyan.cyanbridge.shared.ui.chat.NotesChatsTab
import com.fersaiyan.cyanbridge.shared.ui.notes.MarkdownNoteEditorScreen
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.stringResource

private const val CHAT_APPEARANCE_PREFS = "cyanbridge_chat_appearance"
private const val NOTE_TAGS_PREFS = "cyanbridge_note_tags"
private const val WALLPAPER_FILE = "chat_wallpaper.jpg"

// Preset bubble colors cycled by the appearance menu (ARGB).
private val USER_BUBBLE_COLORS = listOf(0xFF1E6A7A, 0xFF3A5BA0, 0xFF6B4FA0, 0xFF2E7D32, 0xFFB3261E, 0xFF8B5000)
    .map { it.toInt() }
private val ASSISTANT_BUBBLE_COLORS = listOf(0xFFE3EEF0, 0xFFE8E0F0, 0xFFF3E9DC, 0xFFE2F0E3, 0xFFF0E0E0, 0xFFEDEDED)
    .map { it.toInt() }

/** Chats and notes for hosts that render shared destinations (iOS). */
@OptIn(ExperimentalResourceApi::class)
@Composable
internal fun SharedChatsDestination(onDestinationSelected: (AppDestination) -> Unit) {
    val scope = rememberCoroutineScope()
    val newChatTitle = stringResource(Res.string.action_new_chat)
    val formatTimestamp = sharedTimestampFormatter()
    var threads by remember { mutableStateOf<List<ChatThreadSummary>>(emptyList()) }
    var notes by remember { mutableStateOf<List<NoteEntity>>(emptyList()) }
    var pendingDelete by remember { mutableStateOf<ChatThreadSummary?>(null) }
    var selectedThreadId by remember {
        mutableStateOf(SharedChatHooks.initialThreadId.also { SharedChatHooks.initialThreadId = null })
    }
    var selectedTab by remember { mutableStateOf(NotesChatsTab.CHATS) }
    var editingNote by remember { mutableStateOf<NoteEntity?>(null) }
    var showAppearance by remember { mutableStateOf(false) }

    fun refreshChats() {
        scope.launch {
            if (CyanBridgeServices.isInitialized()) {
                threads = CyanBridgeServices.chatRepository.getAllChats()
                    .map { ChatThreadSummary(it.id, it.title, it.updatedAt) }
                    .sortedByDescending { it.updatedAtEpochMillis }
            }
        }
    }

    fun refreshNotes() {
        scope.launch {
            if (CyanBridgeServices.isInitialized()) {
                notes = CyanBridgeServices.notesRepository.getAllNotes().sortedByDescending { it.updatedAt }
            }
        }
    }

    LaunchedEffect(Unit) {
        refreshChats()
        refreshNotes()
    }

    editingNote?.let { note ->
        SharedNoteEditorDestination(
            note = note,
            isNew = notes.none { it.id == note.id },
            onClose = {
                editingNote = null
                refreshNotes()
            },
        )
        return
    }

    val selectedThread = threads.firstOrNull { it.id == selectedThreadId }
    if (selectedThread != null) {
        SharedChatThreadDestination(
            threadSummary = selectedThread,
            onBack = {
                selectedThreadId = null
                refreshChats()
            },
            onDestinationSelected = onDestinationSelected,
        )
        return
    }

    NotesChatsScreen(
        selectedTab = selectedTab,
        onTabSelected = { selectedTab = it },
        threads = threads,
        pendingDelete = pendingDelete,
        notes = notes.map { it.toSummary() },
        formatTimestamp = formatTimestamp,
        onOpenThread = { selectedThreadId = it.id },
        onRequestDelete = { pendingDelete = it },
        onConfirmDelete = {
            val thread = pendingDelete
            pendingDelete = null
            if (thread != null) {
                scope.launch {
                    if (CyanBridgeServices.isInitialized()) {
                        CyanBridgeServices.chatRepository.deleteChat(thread.id)
                        refreshChats()
                    }
                }
            }
        },
        onDismissDelete = { pendingDelete = null },
        onNewChat = {
            val now = sharedNowMillis()
            val id = "ios-$now"
            scope.launch {
                if (CyanBridgeServices.isInitialized()) {
                    CyanBridgeServices.chatRepository.insertChat(
                        ChatEntity(id = id, title = newChatTitle, createdAt = now, updatedAt = now),
                    )
                    refreshChats()
                    selectedThreadId = id
                }
            }
        },
        onOpenNote = { summary -> editingNote = notes.firstOrNull { it.summaryId() == summary.id } },
        onNewNote = {
            val now = sharedNowMillis()
            editingNote = NoteEntity(
                id = "note-$now",
                title = "",
                content = "",
                createdAt = now,
                updatedAt = now,
                source = "manual",
            )
        },
        onChatAppearance = { showAppearance = true },
        // Obsidian sync relies on Android storage access; iOS has no equivalent yet.
        onOpenNotesSettings = {},
        onDestinationSelected = onDestinationSelected,
        showNavigationBar = false,
    )

    if (showAppearance) {
        ChatAppearanceMenuDialog(
            modelOptionLabel = null,
            onDismissRequest = { showAppearance = false },
            onAction = { action ->
                showAppearance = false
                scope.launch { applyChatAppearanceAction(action) }
            },
        )
    }
}

@OptIn(ExperimentalResourceApi::class)
@Composable
private fun SharedChatThreadDestination(
    threadSummary: ChatThreadSummary,
    onBack: () -> Unit,
    onDestinationSelected: (AppDestination) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var messages by remember(threadSummary.id) { mutableStateOf<List<ChatMessage>>(emptyList()) }
    var composerText by remember(threadSummary.id) { mutableStateOf("") }
    var isThinking by remember(threadSummary.id) { mutableStateOf(false) }
    var statusText by remember(threadSummary.id) { mutableStateOf<String?>(null) }
    var pendingImage by remember(threadSummary.id) { mutableStateOf<ByteArray?>(null) }
    var isRecording by remember(threadSummary.id) { mutableStateOf(false) }
    var showAppearance by remember { mutableStateOf(false) }
    var appearance by remember { mutableStateOf(ChatAppearance.load()) }
    var wallpaper by remember { mutableStateOf<ImageBitmap?>(null) }
    val chatRequestFailed = stringResource(Res.string.chat_request_failed)

    fun reloadMessages() {
        scope.launch {
            if (CyanBridgeServices.isInitialized()) {
                messages = CyanBridgeServices.chatRepository.getMessages(threadSummary.id)
                    .map(ChatMessageEntity::toSharedMessage)
            }
        }
    }

    suspend fun reloadWallpaper() {
        wallpaper = if (appearance.hasWallpaper) {
            SharedMediaHooks.loadImage?.invoke(PlatformFilePaths.dataDirectory() + "/" + WALLPAPER_FILE)
        } else {
            null
        }
    }

    LaunchedEffect(threadSummary.id) {
        reloadMessages()
        reloadWallpaper()
    }

    fun send() {
        val text = composerText.trim()
        val image = pendingImage
        if ((text.isEmpty() && image == null) || isThinking) return
        composerText = ""
        pendingImage = null
        scope.launch {
            if (!CyanBridgeServices.isInitialized()) return@launch
            val now = sharedNowMillis()
            isThinking = true
            statusText = null
            val prompt = text.ifEmpty { sharedDefaultImageQuestion() }
            // Keep the photo next to the question so the bubble can show it.
            val imageFile = image?.let { bytes ->
                "chat-image-$now.jpg".takeIf { SharedMediaHooks.saveDocument?.invoke(it, bytes) != null }
            }
            CyanBridgeServices.chatRepository.insertMessage(
                ChatMessageEntity(
                    id = "user-$now",
                    chatId = threadSummary.id,
                    role = "user",
                    content = when {
                        imageFile != null -> ChatImageMarkup.attach(imageFile, "📷 $prompt")
                        image != null -> "📷 $prompt"
                        else -> text
                    },
                    timestamp = now,
                ),
            )
            reloadMessages()
            runCatching {
                if (image != null) {
                    CyanBridgeServices.imageAiService.analyzeImage(image, prompt)
                } else {
                    val history = CyanBridgeServices.chatRepository.getMessages(threadSummary.id)
                        .map { AiChatMessage(it.role, ChatImageMarkup.stripImage(it.content)) }
                    CyanBridgeServices.chatAiService.chat(history).message.content
                }
            }.onSuccess { reply ->
                CyanBridgeServices.chatRepository.insertMessage(
                    ChatMessageEntity(
                        id = "assistant-${sharedNowMillis()}",
                        chatId = threadSummary.id,
                        role = "assistant",
                        content = reply,
                        timestamp = sharedNowMillis(),
                    ),
                )
            }.onFailure { error ->
                statusText = error.message ?: chatRequestFailed
            }
            reloadMessages()
            isThinking = false
        }
    }

    SystemBackHandler(onBack = onBack)
    ChatThreadScreen(
        state = ChatThreadUiState(
            thread = ChatThread(
                id = threadSummary.id,
                title = threadSummary.title,
                createdAt = 0L,
                updatedAt = threadSummary.updatedAtEpochMillis,
            ),
            messages = messages,
            composerText = composerText,
            isGenerating = isThinking,
            statusText = statusText,
        ),
        messages = messages,
        composer = ChatComposerUiState(isMediaEnabled = SharedChatHooks.pickImage != null),
        attachments = ChatAttachmentsUiState(
            label = when {
                isRecording -> "Recording… tap the microphone again to stop"
                pendingImage != null -> "1 image attached"
                else -> null
            },
            isRecording = isRecording,
        ),
        modelBadge = if (CyanBridgeServices.isInitialized()) {
            CyanBridgeServices.aiModelRegistry.getDefaultModelId()
        } else {
            null
        },
        dailySummaryProgress = null,
        dailyReviewQueueStatus = statusText,
        userBubbleColor = appearance.userBubbleColor,
        assistantBubbleColor = appearance.assistantBubbleColor,
        wallpaper = wallpaper,
        isThinking = isThinking,
        onOpenChatList = onBack,
        onChatAppearance = { showAppearance = true },
        onComposerTextChanged = { composerText = it },
        onPrimaryAction = ::send,
        onAttachImage = {
            val pick = SharedChatHooks.pickImage ?: return@ChatThreadScreen
            scope.launch { pick()?.let { pendingImage = it } }
        },
        onRecordAudio = {
            scope.launch {
                if (!isRecording) {
                    isRecording = SharedChatHooks.startAudioRecording?.invoke() == true
                    if (!isRecording) statusText = "Microphone access is needed to record audio"
                } else {
                    isRecording = false
                    val audio = SharedChatHooks.stopAudioRecording?.invoke() ?: return@launch
                    statusText = "Transcribing…"
                    runCatching {
                        CyanBridgeServices.voiceAiService.transcribe(audio, SharedChatHooks.audioMimeType)
                    }.onSuccess { transcript ->
                        statusText = null
                        composerText = listOf(composerText.trim(), transcript.trim()).filter { it.isNotEmpty() }.joinToString(" ")
                    }.onFailure { error ->
                        statusText = error.message ?: chatRequestFailed
                    }
                }
            }
        },
        onClearAttachments = { pendingImage = null },
        onDestinationSelected = { destination ->
            if (destination == AppDestination.CHATS) onBack() else onDestinationSelected(destination)
        },
        showNavigationBar = false,
    )

    if (showAppearance) {
        ChatAppearanceMenuDialog(
            modelOptionLabel = null,
            onDismissRequest = { showAppearance = false },
            onAction = { action ->
                showAppearance = false
                scope.launch {
                    applyChatAppearanceAction(action)
                    appearance = ChatAppearance.load()
                    reloadWallpaper()
                }
            },
        )
    }
}

@OptIn(ExperimentalResourceApi::class)
@Composable
private fun SharedNoteEditorDestination(
    note: NoteEntity,
    isNew: Boolean,
    onClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val tagsPreferences = remember { createPlatformPreferences(NOTE_TAGS_PREFS) }
    var title by remember(note.id) { mutableStateOf(note.title) }
    var tags by remember(note.id) { mutableStateOf(tagsPreferences.getString(note.id, "")) }
    var body by remember(note.id) { mutableStateOf(TextFieldValue(note.content)) }
    var isSaving by remember { mutableStateOf(false) }

    fun exportText(): String = listOfNotNull(
        title.takeIf { it.isNotBlank() }?.let { "# $it" },
        body.text,
    ).joinToString("\n\n")

    SystemBackHandler(onBack = onClose)
    MarkdownNoteEditorScreen(
        screenTitle = stringResource(if (isNew) Res.string.notes_new_title else Res.string.notes_edit_title),
        title = title,
        tags = tags,
        body = body,
        sourceLabel = stringResource(
            if (note.source == "meeting") Res.string.notes_source_meeting else Res.string.notes_source_app,
        ),
        isSaving = isSaving,
        onTitleChange = { title = it },
        onTagsChange = { tags = it },
        onBodyChange = { body = it },
        onSave = {
            isSaving = true
            scope.launch {
                val updated = note.copy(
                    title = title.trim(),
                    content = body.text,
                    updatedAt = sharedNowMillis(),
                )
                if (isNew) {
                    CyanBridgeServices.notesRepository.insertNote(updated)
                } else {
                    CyanBridgeServices.notesRepository.updateNote(updated)
                }
                tagsPreferences.putString(note.id, tags.trim())
                isSaving = false
                onClose()
            }
        },
        onCopy = { SharedChatHooks.copyText?.invoke(exportText()) },
        onShare = { SharedChatHooks.shareText?.invoke(exportText()) },
        onBack = onClose,
    )
}

private fun NoteEntity.summaryId(): Long = id.hashCode().toLong()

private fun NoteEntity.toSummary(): NoteSummary = NoteSummary(
    id = summaryId(),
    title = title.ifBlank { content.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(60).orEmpty() },
    summary = content.take(160),
    createdAt = updatedAt,
    source = if (source == "meeting") NoteSource.MEETING else NoteSource.APP,
)

/** Chat appearance persisted for the iOS host (Android stores its own in ChatThreadActivity). */
private data class ChatAppearance(
    val userBubbleColor: Int?,
    val assistantBubbleColor: Int?,
    val hasWallpaper: Boolean,
) {
    companion object {
        private val preferences by lazy { createPlatformPreferences(CHAT_APPEARANCE_PREFS) }

        fun load() = ChatAppearance(
            userBubbleColor = preferences.getInt("user_bubble", -1).takeIf { it != -1 }?.let(USER_BUBBLE_COLORS::getOrNull),
            assistantBubbleColor = preferences.getInt("assistant_bubble", -1).takeIf { it != -1 }
                ?.let(ASSISTANT_BUBBLE_COLORS::getOrNull),
            hasWallpaper = preferences.getBoolean("wallpaper", false),
        )

        fun cycle(key: String, size: Int) {
            preferences.putInt(key, (preferences.getInt(key, -1) + 1) % size)
        }

        fun setWallpaper(enabled: Boolean) = preferences.putBoolean("wallpaper", enabled)

        fun reset() = preferences.clear()
    }
}

private suspend fun applyChatAppearanceAction(action: ChatAppearanceMenuAction) {
    when (action) {
        ChatAppearanceMenuAction.CHANGE_USER_BUBBLE_COLOR -> ChatAppearance.cycle("user_bubble", USER_BUBBLE_COLORS.size)
        ChatAppearanceMenuAction.CHANGE_ASSISTANT_BUBBLE_COLOR ->
            ChatAppearance.cycle("assistant_bubble", ASSISTANT_BUBBLE_COLORS.size)
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
