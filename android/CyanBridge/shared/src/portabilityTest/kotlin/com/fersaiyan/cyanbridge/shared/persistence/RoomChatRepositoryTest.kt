package com.fersaiyan.cyanbridge.shared.persistence

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.fersaiyan.cyanbridge.data.local.AppDatabase
import com.fersaiyan.cyanbridge.data.local.applyCyanBridgeSchema
import com.fersaiyan.cyanbridge.shared.platform.createPlatformPreferences
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RoomChatRepositoryTest {
    private val dir: File = Files.createTempDirectory("cyanbridge-chat-test").toFile()
    private val db: AppDatabase = Room.databaseBuilder<AppDatabase>(name = File(dir, "chat-db").path)
        .setDriver(BundledSQLiteDriver())
        .applyCyanBridgeSchema()
        .build()
    private val repository = RoomChatRepository(db)

    @AfterTest
    fun tearDown() {
        db.close()
        dir.deleteRecursively()
    }

    @Test
    fun messagesCountAndMoveChatForward() = runBlocking {
        repository.insertChat(ChatEntity("c1", "First", createdAt = 10, updatedAt = 10))
        repository.insertMessage(ChatMessageEntity("b", "c1", "user", "hi", timestamp = 20))
        repository.insertMessage(ChatMessageEntity("a", "c1", "assistant", "hello", timestamp = 20))
        repository.insertMessage(ChatMessageEntity("c", "c1", "user", "older", timestamp = 5))

        val chat = repository.getChat("c1")!!
        assertEquals(3, chat.messageCount)
        assertEquals(20, chat.updatedAt)
        assertEquals(listOf("c", "a", "b"), repository.getMessages("c1").map { it.id })
        assertEquals(3, repository.getAllChats().single().messageCount)
    }

    @Test
    fun deletesMessagesAndChats() = runBlocking {
        repository.insertChat(ChatEntity("c1", "First", 1, 1))
        repository.insertChat(ChatEntity("c2", "Second", 1, 1))
        repository.insertMessage(ChatMessageEntity("m1", "c1", "user", "x", 2))
        repository.insertMessage(ChatMessageEntity("m2", "c1", "user", "y", 3))
        repository.insertMessage(ChatMessageEntity("m3", "c2", "user", "z", 3))

        repository.deleteMessage("m1")
        assertEquals(listOf("m2"), repository.getMessages("c1").map { it.id })
        repository.deleteChat("c1")
        assertNull(repository.getChat("c1"))
        assertTrue(repository.getMessages("c1").isEmpty())
        assertEquals(listOf("m3"), repository.getMessages("c2").map { it.id })
    }

    @Test
    fun importsLegacyChatsOnceWithoutOverwritingAnything() = runBlocking {
        val prefs = createPlatformPreferences("legacy-import-test-${dir.name}")
        val legacy = InMemoryChatRepository().apply {
            insertChat(ChatEntity("old", "Old chat", 1, 9))
            insertMessage(ChatMessageEntity("old-1", "old", "user", "from legacy", 9))
            insertChat(ChatEntity("both", "Legacy title", 1, 1))
            insertMessage(ChatMessageEntity("both-1", "both", "user", "legacy text", 1))
        }
        repository.insertChat(ChatEntity("both", "Database title", 1, 5))
        repository.insertMessage(ChatMessageEntity("both-1", "both", "user", "database text", 5))

        val chats = LegacyImportingChatRepository(repository, legacy, prefs)
        assertEquals(setOf("old", "both"), chats.getAllChats().map { it.id }.toSet())
        assertEquals("from legacy", chats.getMessages("old").single().content)
        assertEquals("Database title", chats.getChat("both")?.title)
        assertEquals("database text", chats.getMessages("both").single().content)
        assertTrue(prefs.getBoolean(LegacyImportingChatRepository.IMPORT_DONE_KEY, false))
        assertEquals(2, legacy.getAllChats().size)

        chats.deleteChat("old")
        assertNull(legacy.getChat("old"))
        val afterRestart = LegacyImportingChatRepository(repository, legacy, prefs)
        assertEquals(listOf("both"), afterRestart.getAllChats().map { it.id })
    }

    private class InMemoryChatRepository : ChatRepository {
        private val chats = linkedMapOf<String, ChatEntity>()
        private val messages = mutableListOf<ChatMessageEntity>()

        override suspend fun getAllChats() = chats.values.toList()
        override suspend fun getChat(id: String) = chats[id]
        override suspend fun insertChat(chat: ChatEntity) { chats[chat.id] = chat }
        override suspend fun updateChat(chat: ChatEntity) = insertChat(chat)
        override suspend fun deleteChat(id: String) {
            chats.remove(id)
            messages.removeAll { it.chatId == id }
        }
        override suspend fun getMessages(chatId: String) = messages.filter { it.chatId == chatId }
        override suspend fun insertMessage(message: ChatMessageEntity) {
            messages.removeAll { it.id == message.id }
            messages += message
        }
        override suspend fun deleteMessage(id: String) { messages.removeAll { it.id == id } }
        override suspend fun deleteMessagesForChat(chatId: String) { messages.removeAll { it.chatId == chatId } }
    }
}
