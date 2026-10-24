package com.fersaiyan.cyanbridge.shared.ui

import androidx.compose.ui.graphics.ImageBitmap
import com.fersaiyan.cyanbridge.shared.ai.ChatMessage as AiChatMessage
import com.fersaiyan.cyanbridge.shared.chat.ChatImageMarkup
import com.fersaiyan.cyanbridge.shared.chat.ChatMessage
import com.fersaiyan.cyanbridge.shared.chat.ChatModelInfo
import com.fersaiyan.cyanbridge.shared.chat.ChatProviderKind
import com.fersaiyan.cyanbridge.shared.chat.ChatRepository
import com.fersaiyan.cyanbridge.shared.chat.ChatRole
import com.fersaiyan.cyanbridge.shared.chat.ChatThread
import com.fersaiyan.cyanbridge.shared.chat.ChatThreadSummary
import com.fersaiyan.cyanbridge.shared.notes.NoteSource
import com.fersaiyan.cyanbridge.shared.notes.NoteSummary
import com.fersaiyan.cyanbridge.shared.persistence.ChatEntity
import com.fersaiyan.cyanbridge.shared.persistence.ChatMessageEntity
import com.fersaiyan.cyanbridge.shared.persistence.NoteEntity
import com.fersaiyan.cyanbridge.shared.platform.CyanBridgeServices
import com.fersaiyan.cyanbridge.shared.platform.PlatformFilePaths
import com.fersaiyan.cyanbridge.shared.platform.SharedChatHooks
import com.fersaiyan.cyanbridge.shared.platform.SharedMediaHooks
import com.fersaiyan.cyanbridge.shared.prefs.AppPrefs
import com.fersaiyan.cyanbridge.shared.ui.chat.ChatListSource
import com.fersaiyan.cyanbridge.shared.ui.chat.ChatReplyRequest
import com.fersaiyan.cyanbridge.shared.ui.chat.ChatReplyStream
import com.fersaiyan.cyanbridge.shared.ui.chat.ChatThreadBackend
import com.fersaiyan.cyanbridge.shared.ui.chat.ChatVoiceRecorder
import com.fersaiyan.cyanbridge.shared.ui.notes.NoteDraft
import com.fersaiyan.cyanbridge.shared.ui.notes.NoteEditorStore
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

// Chat and notes services for hosts that render the shared destinations (iOS), over CyanBridgeServices.

internal const val WALLPAPER_FILE = "chat_wallpaper.jpg"
private const val NEW_CHAT_TITLE = "New chat"

/** The chat tables through [CyanBridgeServices.chatRepository], with the Android chat store's rules. */
internal object SharedChatStore : ChatRepository {
    private val chats get() = CyanBridgeServices.chatRepository

    private fun ChatEntity.toThread() = ChatThread(id, title, createdAt, updatedAt)

    override suspend fun listThreads(): List<ChatThread> =
        chats.getAllChats().sortedByDescending { it.updatedAt }.map { it.toThread() }

    override suspend fun listNonEmptyThreads(): List<ChatThread> =
        chats.getAllChats().filter { it.messageCount > 0 }.sortedByDescending { it.updatedAt }.map { it.toThread() }

    override suspend fun getThread(chatId: String): ChatThread? = chats.getChat(chatId)?.toThread()

    override suspend fun createThread(title: String?, nowMs: Long): ChatThread {
        val chat = ChatEntity(id = "ios-$nowMs", title = title?.takeIf { it.isNotBlank() } ?: NEW_CHAT_TITLE, createdAt = nowMs, updatedAt = nowMs)
        chats.insertChat(chat)
        return chat.toThread()
    }

    override suspend fun listMessages(chatId: String): List<ChatMessage> = chats.getMessages(chatId).map { it.toSharedMessage() }

    override suspend fun addMessage(chatId: String, role: ChatRole, content: String, nowMs: Long): ChatMessage {
        require(content.isNotBlank()) { "message content cannot be blank" }
        val chat = chats.getChat(chatId) ?: error("Unknown chatId=$chatId")
        val roleName = if (role == ChatRole.USER) "user" else "assistant"
        val message = ChatMessageEntity("$roleName-$nowMs", chatId, roleName, content, nowMs)
        chats.insertMessage(message)
        if (chat.title == NEW_CHAT_TITLE && role == ChatRole.USER) {
            chats.updateChat(chat.copy(title = content.trim().take(32).ifBlank { NEW_CHAT_TITLE }, updatedAt = nowMs))
        }
        return message.toSharedMessage()
    }

    override suspend fun updateThreadTitle(chatId: String, title: String, nowMs: Long): Boolean {
        val normalized = title.trim()
        val chat = chats.getChat(chatId) ?: return false
        if (normalized.isBlank() || chat.title == normalized) return false
        chats.updateChat(chat.copy(title = normalized, updatedAt = maxOf(chat.updatedAt, nowMs)))
        return true
    }

    override suspend fun deleteThread(chatId: String) = chats.deleteChat(chatId)

    override suspend fun clearAll() = chats.getAllChats().forEach { chats.deleteChat(it.id) }
}

private fun sharedModelInfo() = ChatModelInfo(
    ChatProviderKind.OTHER,
    if (CyanBridgeServices.isInitialized()) CyanBridgeServices.aiModelRegistry.getDefaultModelId() else null,
)

internal object SharedChatListSource : ChatListSource {
    /** Bumped when a note is saved so the list reloads. */
    val notesRevision = MutableStateFlow(0)

    override suspend fun threads(): List<ChatThreadSummary> = if (CyanBridgeServices.isInitialized()) {
        SharedChatStore.listNonEmptyThreads().map { ChatThreadSummary(it.id, it.title, it.updatedAt) }
    } else {
        emptyList()
    }

    override fun appNotes() = notesRevision.map {
        if (CyanBridgeServices.isInitialized()) CyanBridgeServices.notesRepository.getAllNotes().map { it.toSummary() } else emptyList()
    }

    override suspend fun deleteThread(id: String) = SharedChatStore.deleteThread(id)

    override fun modelInfo() = sharedModelInfo()
}

internal fun NoteEntity.summaryId(): Long = id.hashCode().toLong()

private fun NoteEntity.toSummary(): NoteSummary = NoteSummary(
    id = summaryId(),
    title = title.ifBlank { content.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(60).orEmpty() },
    summary = content.take(160),
    createdAt = updatedAt,
    source = if (source == "meeting") NoteSource.MEETING else NoteSource.APP,
)

/** Picked photos and voice notes waiting to be sent, by attachment name. */
private object PendingAttachments {
    val bytes = mutableMapOf<String, ByteArray>()

    /** Photos written next to the chats, which the bubbles can show. */
    val savedPhotos = mutableSetOf<String>()
}

/** Keeps a picked photo for the next message and returns its attachment name. */
internal fun attachPickedImage(image: ByteArray): String {
    val name = "chat-image-${sharedNowMillis()}.jpg"
    PendingAttachments.bytes[name] = image
    if (SharedMediaHooks.saveDocument?.invoke(name, image) != null) PendingAttachments.savedPhotos += name
    return name
}

/** Replies through the relay services: photos go to the image service, voice notes are transcribed first. */
internal object SharedChatBackend : ChatThreadBackend {
    override fun modelInfo() = sharedModelInfo()

    override fun bubbleColors(): Pair<Int?, Int?> = ChatAppearance.load().let { it.userBubbleColor to it.assistantBubbleColor }

    override suspend fun wallpaper(): ImageBitmap? = if (ChatAppearance.load().hasWallpaper) {
        SharedMediaHooks.loadImage?.invoke(PlatformFilePaths.dataDirectory() + "/" + WALLPAPER_FILE)
    } else {
        null
    }

    override val mediaPrompt: String get() = sharedDefaultImageQuestion()

    override fun formatUserMessage(text: String, imagePaths: List<String>, audioPath: String?): String {
        val photo = imagePaths.firstOrNull()
        val base = when {
            photo == null -> text
            photo in PendingAttachments.savedPhotos -> ChatImageMarkup.attach(photo, "📷 $text")
            else -> "📷 $text"
        }
        return if (audioPath != null) "$base\n[Attached voice note]" else base
    }

    override suspend fun reply(request: ChatReplyRequest, stream: ChatReplyStream?): String {
        val image = request.imagePaths.firstOrNull()?.let { PendingAttachments.bytes.remove(it) }
        request.imagePaths.forEach { PendingAttachments.bytes.remove(it) }
        val transcript = request.audioPath?.let { PendingAttachments.bytes.remove(it) }
            ?.let { CyanBridgeServices.voiceAiService.transcribe(it, SharedChatHooks.audioMimeType).trim() }
        val prompt = listOfNotNull(request.prompt, transcript?.takeIf { it.isNotEmpty() }).joinToString("\n\n")
        if (image != null) return CyanBridgeServices.imageAiService.analyzeImage(image, prompt)
        val history = CyanBridgeServices.chatRepository.getMessages(request.chatId)
            .map { AiChatMessage(it.role, ChatImageMarkup.stripImage(it.content)) }
        val messages = if (transcript == null) history else history.dropLast(1) + AiChatMessage("user", prompt)
        return CyanBridgeServices.chatAiService.chat(messages).message.content
    }

    override suspend fun textReply(prompt: String): String =
        CyanBridgeServices.chatAiService.chat(listOf(AiChatMessage("user", prompt))).message.content

    // Replies are not streamed, so there is nothing to stop.
    override suspend fun cancelGeneration() = Unit
}

/** Voice notes through the host's microphone hooks. */
internal object SharedChatRecorder : ChatVoiceRecorder {
    // The host asks for microphone access when recording starts.
    override fun hasPermission() = true

    override suspend fun start(): Boolean = SharedChatHooks.startAudioRecording?.invoke() == true

    override suspend fun stop(keep: Boolean): String? {
        val audio = SharedChatHooks.stopAudioRecording?.invoke() ?: return null
        if (!keep) return null
        return "voice-${sharedNowMillis()}".also { PendingAttachments.bytes[it] = audio }
    }

    override fun release() {
        MainScope().launch { SharedChatHooks.stopAudioRecording?.invoke() }
    }
}

/** A note in [CyanBridgeServices.notesRepository]; its tags are kept in preferences. */
internal class SharedNoteStore(private val note: NoteEntity, override val isNew: Boolean) : NoteEditorStore {
    override suspend fun load() = NoteDraft(
        title = note.title,
        tags = AppPrefs.NoteTags.tags[note.id].get(),
        body = note.content,
        source = if (note.source == "meeting") NoteSource.MEETING else NoteSource.APP,
    )

    override suspend fun save(draft: NoteDraft): NoteDraft {
        val notes = CyanBridgeServices.notesRepository
        val updated = note.copy(title = draft.title.trim(), content = draft.body, updatedAt = sharedNowMillis())
        if (notes.getNote(note.id) == null) notes.insertNote(updated) else notes.updateNote(updated)
        AppPrefs.NoteTags.tags[note.id].set(draft.tags.trim())
        SharedChatListSource.notesRevision.value++
        return draft
    }
}
