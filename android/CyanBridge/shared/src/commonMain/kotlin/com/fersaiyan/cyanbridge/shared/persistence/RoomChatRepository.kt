package com.fersaiyan.cyanbridge.shared.persistence

import com.fersaiyan.cyanbridge.data.local.AppDatabase
import com.fersaiyan.cyanbridge.data.local.entity.Chat
import com.fersaiyan.cyanbridge.data.local.entity.Message
import com.fersaiyan.cyanbridge.data.local.inTransaction
import com.fersaiyan.cyanbridge.shared.platform.PlatformLogger
import com.fersaiyan.cyanbridge.shared.platform.PlatformPreferences
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * [ChatRepository] over the shared Room database (`chats` and `messages`, the tables the
 * Android app uses). [ChatEntity.messageCount] is counted from `messages`;
 * [ChatMessageEntity.metadata] has no column and is not stored (no caller sets it).
 */
class RoomChatRepository(private val db: AppDatabase) : ChatRepository {

    override suspend fun getAllChats(): List<ChatEntity> {
        val counts = db.messageDao().getMessageCounts().associate { it.chatId to it.count }
        return db.chatDao().getAllChatsOnce().map { it.toEntity(counts[it.id] ?: 0) }
    }

    override suspend fun getChat(id: String): ChatEntity? =
        db.chatDao().getChatById(id)?.let { it.toEntity(db.messageDao().countForChat(id)) }

    override suspend fun insertChat(chat: ChatEntity) {
        db.chatDao().insertChat(Chat(chat.id, chat.title, chat.createdAt, chat.updatedAt))
    }

    override suspend fun updateChat(chat: ChatEntity) = insertChat(chat)

    override suspend fun deleteChat(id: String) {
        db.inTransaction {
            db.messageDao().deleteMessagesByChatId(id)
            db.chatDao().deleteChatById(id)
        }
    }

    override suspend fun getMessages(chatId: String): List<ChatMessageEntity> =
        db.messageDao().getMessagesForChatOnce(chatId)
            .sortedWith(compareBy<Message> { it.createdAt }.thenBy { it.id })
            .map { ChatMessageEntity(it.id, it.chatId, it.role, it.content, it.createdAt) }

    /** Stores the message and moves the chat's `updatedAt` forward to it, like the old store. */
    override suspend fun insertMessage(message: ChatMessageEntity) {
        db.inTransaction {
            db.messageDao().insertMessage(
                Message(message.id, message.chatId, message.role, message.content, message.timestamp),
            )
            val chat = db.chatDao().getChatById(message.chatId)
            if (chat != null && message.timestamp > chat.updatedAt) {
                db.chatDao().insertChat(chat.copy(updatedAt = message.timestamp))
            }
        }
    }

    override suspend fun deleteMessage(id: String) {
        db.messageDao().deleteMessageById(id)
    }

    override suspend fun deleteMessagesForChat(chatId: String) {
        db.messageDao().deleteMessagesByChatId(chatId)
    }

    private fun Chat.toEntity(messageCount: Int) = ChatEntity(id, title, createdAt, updatedAt, messageCount)
}

/**
 * Moves chats saved by an older build in [legacy] into [target] once, on first use.
 *
 * Nothing in [legacy] is overwritten: it stays as a backup, and only deletions are mirrored
 * to it so a deleted chat can't survive there (or come back with a later import). Rows already
 * in [target] are kept as they are. The done flag lives in [legacyPrefs], the same store as the
 * legacy chats, so clearing that store resets both together.
 */
class LegacyImportingChatRepository(
    private val target: ChatRepository,
    private val legacy: ChatRepository,
    private val legacyPrefs: PlatformPreferences,
) : ChatRepository {
    private var importChecked = false

    private suspend fun ensureImported() {
        if (importChecked) return
        importLock.withLock {
            if (importChecked) return
            if (!legacyPrefs.getBoolean(IMPORT_DONE_KEY, false)) {
                runCatching { importLegacyChats() }
                    .onSuccess { legacyPrefs.putBoolean(IMPORT_DONE_KEY, true) }
                    // Retried on the next launch; the legacy copy is untouched.
                    .onFailure { PlatformLogger.e(TAG, "Legacy chat import failed", it) }
            }
            importChecked = true
        }
    }

    private suspend fun importLegacyChats() {
        val legacyChats = legacy.getAllChats()
        legacyChats.forEach { chat ->
            if (target.getChat(chat.id) == null) target.insertChat(chat)
            val existingIds = target.getMessages(chat.id).mapTo(HashSet()) { it.id }
            legacy.getMessages(chat.id)
                .filter { it.id !in existingIds }
                .forEach { target.insertMessage(it) }
        }
        PlatformLogger.d(TAG, "Imported ${legacyChats.size} legacy chats")
    }

    override suspend fun getAllChats(): List<ChatEntity> {
        ensureImported()
        return target.getAllChats()
    }

    override suspend fun getChat(id: String): ChatEntity? {
        ensureImported()
        return target.getChat(id)
    }

    override suspend fun insertChat(chat: ChatEntity) {
        ensureImported()
        target.insertChat(chat)
    }

    override suspend fun updateChat(chat: ChatEntity) {
        ensureImported()
        target.updateChat(chat)
    }

    override suspend fun deleteChat(id: String) {
        ensureImported()
        target.deleteChat(id)
        legacy.deleteChat(id)
    }

    override suspend fun getMessages(chatId: String): List<ChatMessageEntity> {
        ensureImported()
        return target.getMessages(chatId)
    }

    override suspend fun insertMessage(message: ChatMessageEntity) {
        ensureImported()
        target.insertMessage(message)
    }

    override suspend fun deleteMessage(id: String) {
        ensureImported()
        target.deleteMessage(id)
        legacy.deleteMessage(id)
    }

    override suspend fun deleteMessagesForChat(chatId: String) {
        ensureImported()
        target.deleteMessagesForChat(chatId)
        legacy.deleteMessagesForChat(chatId)
    }

    companion object {
        private const val TAG = "LegacyChatImport"
        const val IMPORT_DONE_KEY = "shared_db_import_done"

        /** Shared by every instance, so two controllers can't import the same chats at once. */
        private val importLock = Mutex()
    }
}
