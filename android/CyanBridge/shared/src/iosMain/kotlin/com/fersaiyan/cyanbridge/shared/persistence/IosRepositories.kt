package com.fersaiyan.cyanbridge.shared.persistence

import com.fersaiyan.cyanbridge.shared.platform.PlatformFilePaths
import com.fersaiyan.cyanbridge.shared.platform.PlatformLogger
import com.fersaiyan.cyanbridge.shared.platform.createPlatformPreferences
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.Serializable

/**
 * iOS implementation of persistence repositories using PlatformPreferences
 * (NSUserDefaults) for simple storage.
 *
 * For production, this should use SQLDelight or a proper SQLite wrapper.
 */

@Serializable
private data class ChatJson(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val messageCount: Int = 0,
)

@Serializable
private data class MessageJson(
    val id: String,
    val chatId: String,
    val role: String,
    val content: String,
    val timestamp: Long,
    val metadata: String? = null,
)

class IosChatRepository : ChatRepository {
    private val prefs = createPlatformPreferences("cyanbridge_chats")
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun getAllChats(): List<ChatEntity> {
        val data = prefs.getString("chats_json", "[]")
        return try {
            json.decodeFromString<List<ChatJson>>(data).map { it.toEntity() }
        } catch (e: Exception) {
            PlatformLogger.e(TAG, "Failed to parse chats", e)
            emptyList()
        }
    }

    override suspend fun getChat(id: String): ChatEntity? =
        getAllChats().find { it.id == id }

    override suspend fun insertChat(chat: ChatEntity) {
        val chats = getAllChats().toMutableList()
        chats.removeAll { it.id == chat.id }
        chats.add(chat)
        saveChats(chats)
    }

    override suspend fun updateChat(chat: ChatEntity) = insertChat(chat)

    override suspend fun deleteChat(id: String) {
        val chats = getAllChats().toMutableList()
        chats.removeAll { it.id == id }
        saveChats(chats)
        prefs.putString("messages_$id", "[]")
    }

    override suspend fun getMessages(chatId: String): List<ChatMessageEntity> {
        val data = prefs.getString("messages_$chatId", "[]")
        return try {
            json.decodeFromString<List<MessageJson>>(data)
                .map { it.toEntity() }
                .sortedWith(compareBy<ChatMessageEntity> { it.timestamp }.thenBy { it.id })
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun insertMessage(message: ChatMessageEntity) {
        val messages = getMessages(message.chatId).toMutableList()
        messages.removeAll { it.id == message.id }
        messages.add(message)
        prefs.putString("messages_${message.chatId}", json.encodeToString(messages.map { it.toJson() }))
        updateChatMetadata(message.chatId, messages.size, message.timestamp)
    }

    override suspend fun deleteMessage(id: String) {
        // Find and remove from the appropriate chat
        val allChats = getAllChats()
        for (chat in allChats) {
            val messages = getMessages(chat.id).toMutableList()
            if (messages.removeAll { it.id == id }) {
                prefs.putString("messages_${chat.id}", json.encodeToString(messages.map { it.toJson() }))
                updateChatMetadata(chat.id, messages.size, null)
                break
            }
        }
    }

    override suspend fun deleteMessagesForChat(chatId: String) {
        prefs.putString("messages_$chatId", "[]")
        updateChatMetadata(chatId, 0, null)
    }

    private suspend fun updateChatMetadata(
        chatId: String,
        messageCount: Int,
        messageTimestamp: Long?,
    ) {
        val chats = getAllChats()
        val chat = chats.firstOrNull { it.id == chatId } ?: return
        val updatedAt = messageTimestamp?.let { maxOf(chat.updatedAt, it) } ?: chat.updatedAt
        saveChats(chats.map { current ->
            if (current.id == chatId) {
                current.copy(messageCount = messageCount, updatedAt = updatedAt)
            } else {
                current
            }
        })
    }

    private fun saveChats(chats: List<ChatEntity>) {
        prefs.putString("chats_json", json.encodeToString(chats.map { it.toJson() }))
    }

    private fun ChatJson.toEntity() = ChatEntity(id, title, createdAt, updatedAt, messageCount)
    private fun ChatEntity.toJson() = ChatJson(id, title, createdAt, updatedAt, messageCount)
    private fun MessageJson.toEntity() = ChatMessageEntity(id, chatId, role, content, timestamp, metadata)
    private fun ChatMessageEntity.toJson() = MessageJson(id, chatId, role, content, timestamp, metadata)

    companion object {
        private const val TAG = "IosChatRepo"
    }
}

class IosNotesRepository : NotesRepository {
    private val prefs = createPlatformPreferences("cyanbridge_notes")
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class NoteJson(
        val id: String,
        val title: String,
        val content: String,
        val createdAt: Long,
        val updatedAt: Long,
        val source: String? = null,
    )

    override suspend fun getAllNotes(): List<NoteEntity> {
        val data = prefs.getString("notes_json", "[]")
        return try {
            json.decodeFromString<List<NoteJson>>(data).map {
                NoteEntity(it.id, it.title, it.content, it.createdAt, it.updatedAt, it.source)
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun getNote(id: String): NoteEntity? =
        getAllNotes().find { it.id == id }

    override suspend fun insertNote(note: NoteEntity) {
        val notes = getAllNotes().toMutableList()
        notes.removeAll { it.id == note.id }
        notes.add(note)
        prefs.putString("notes_json", json.encodeToString(notes.map {
            NoteJson(it.id, it.title, it.content, it.createdAt, it.updatedAt, it.source)
        }))
    }

    override suspend fun updateNote(note: NoteEntity) = insertNote(note)

    override suspend fun deleteNote(id: String) {
        val notes = getAllNotes().toMutableList()
        notes.removeAll { it.id == id }
        prefs.putString("notes_json", json.encodeToString(notes.map {
            NoteJson(it.id, it.title, it.content, it.createdAt, it.updatedAt, it.source)
        }))
    }

    override suspend fun searchNotes(query: String): List<NoteEntity> {
        val lower = query.lowercase()
        return getAllNotes().filter {
            it.title.lowercase().contains(lower) || it.content.lowercase().contains(lower)
        }
    }
}

// Device Profile Repository, persisted as a JSON list.

class IosDeviceProfileRepository : DeviceProfileRepository {
    private val prefs = createPlatformPreferences("cyanbridge_devices")
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class DeviceProfileJson(
        val macAddress: String,
        val advertisedName: String?,
        val detectedClass: String,
        val selectedClass: String,
        val userOverridden: Boolean,
        val lastConnectedAt: Long,
    )

    override suspend fun getAll(): List<DeviceProfileEntity> {
        val data = prefs.getString("device_profiles_json", "[]")
        return try {
            json.decodeFromString<List<DeviceProfileJson>>(data).map { it.toEntity() }
        } catch (e: Exception) {
            PlatformLogger.e(TAG, "Failed to parse device profiles", e)
            emptyList()
        }
    }

    override suspend fun get(macAddress: String): DeviceProfileEntity? =
        getAll().find { it.macAddress == macAddress }

    override suspend fun upsert(profile: DeviceProfileEntity) {
        val profiles = getAll().toMutableList()
        profiles.removeAll { it.macAddress == profile.macAddress }
        profiles.add(profile)
        prefs.putString("device_profiles_json", json.encodeToString(profiles.map { it.toJson() }))
    }

    override suspend fun delete(macAddress: String) {
        val profiles = getAll().toMutableList()
        profiles.removeAll { it.macAddress == macAddress }
        prefs.putString("device_profiles_json", json.encodeToString(profiles.map { it.toJson() }))
    }

    private fun DeviceProfileJson.toEntity() = DeviceProfileEntity(
        macAddress, advertisedName, detectedClass, selectedClass, userOverridden, lastConnectedAt,
    )
    private fun DeviceProfileEntity.toJson() = DeviceProfileJson(
        macAddress, advertisedName, detectedClass, selectedClass, userOverridden, lastConnectedAt,
    )

    companion object {
        private const val TAG = "IosDeviceProfileRepo"
    }
}

// Memory Vault Repository. ByteArray embeddings use signed Int lists for
// Kotlin/Native-safe serialization.

class IosMemoryVaultRepository : MemoryVaultRepository {
    private val prefs = createPlatformPreferences("cyanbridge_memory")
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class MemoryVaultItemJson(
        val id: String,
        val content: String,
        val sourceType: String,
        val createdAt: Long,
        val updatedAt: Long,
        /** ByteArray stored as list of signed ints (value in -128..127) */
        val embedding: List<Int>? = null,
    )

    override suspend fun getAll(): List<MemoryVaultItemEntity> {
        val data = prefs.getString("memory_items_json", "[]")
        return try {
            json.decodeFromString<List<MemoryVaultItemJson>>(data).map { it.toEntity() }
        } catch (e: Exception) {
            PlatformLogger.e(TAG, "Failed to parse memory items", e)
            emptyList()
        }
    }

    override suspend fun get(id: String): MemoryVaultItemEntity? =
        getAll().find { it.id == id }

    override suspend fun insert(item: MemoryVaultItemEntity) {
        val items = getAll().toMutableList()
        items.removeAll { it.id == item.id }
        items.add(item)
        saveAll(items)
    }

    override suspend fun update(item: MemoryVaultItemEntity) = insert(item)

    override suspend fun delete(id: String) {
        val items = getAll().toMutableList()
        items.removeAll { it.id == id }
        saveAll(items)
    }

    override suspend fun search(query: String, limit: Int): List<MemoryVaultItemEntity> {
        val lower = query.lowercase()
        return getAll().filter {
            it.content.lowercase().contains(lower) || it.sourceType.lowercase().contains(lower)
        }.take(limit)
    }

    private fun saveAll(items: List<MemoryVaultItemEntity>) {
        prefs.putString("memory_items_json", json.encodeToString(items.map { it.toJson() }))
    }

    private fun MemoryVaultItemJson.toEntity(): MemoryVaultItemEntity {
        val embeddingBytes: ByteArray? = embedding?.let { list ->
            ByteArray(list.size) { list[it].toByte() }
        }
        return MemoryVaultItemEntity(id, content, sourceType, createdAt, updatedAt, embeddingBytes)
    }

    private fun MemoryVaultItemEntity.toJson(): MemoryVaultItemJson {
        val embeddingList: List<Int>? = embedding?.let { bytes ->
            bytes.map { it.toInt() }
        }
        return MemoryVaultItemJson(id, content, sourceType, createdAt, updatedAt, embeddingList)
    }

    companion object {
        private const val TAG = "IosMemoryVaultRepo"
    }
}

// Media Record Repository, including filename lookup and delete-all.

class IosMediaRecordRepository : MediaRecordRepository {
    private val prefs = createPlatformPreferences("cyanbridge_media")
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class MediaRecordJson(
        val id: String,
        val filename: String,
        val mimeType: String,
        val filePath: String,
        val downloadedAt: Long,
        val fileSize: Long,
        val source: String = "glasses",
    )

    override suspend fun getAll(): List<MediaRecordEntity> {
        val data = prefs.getString("media_records_json", "[]")
        return try {
            json.decodeFromString<List<MediaRecordJson>>(data).map { it.toEntity() }
        } catch (e: Exception) {
            PlatformLogger.e(TAG, "Failed to parse media records", e)
            emptyList()
        }
    }

    override suspend fun get(id: String): MediaRecordEntity? =
        getAll().find { it.id == id }

    override suspend fun getByFilename(filename: String): MediaRecordEntity? =
        getAll().find { it.filename == filename }

    override suspend fun insert(record: MediaRecordEntity) {
        val records = getAll().toMutableList()
        records.removeAll { it.id == record.id }
        records.add(record)
        prefs.putString("media_records_json", json.encodeToString(records.map { it.toJson() }))
    }

    override suspend fun delete(id: String) {
        val records = getAll().toMutableList()
        records.removeAll { it.id == id }
        prefs.putString("media_records_json", json.encodeToString(records.map { it.toJson() }))
    }

    override suspend fun deleteAll() {
        prefs.putString("media_records_json", "[]")
    }

    private fun MediaRecordJson.toEntity() = MediaRecordEntity(
        id, filename, mimeType, currentDocumentsPath(filePath), downloadedAt, fileSize, source,
    )

    /**
     * iOS moves the app container (new UUID) on reinstall and update, so an
     * absolute path saved earlier goes stale. Re-root it under today's Documents.
     */
    private fun currentDocumentsPath(storedPath: String): String {
        val marker = "/Documents/"
        val index = storedPath.indexOf(marker)
        if (index < 0) return storedPath
        return PlatformFilePaths.dataDirectory() + "/" + storedPath.substring(index + marker.length)
    }
    private fun MediaRecordEntity.toJson() = MediaRecordJson(
        id, filename, mimeType, filePath, downloadedAt, fileSize, source,
    )

    companion object {
        private const val TAG = "IosMediaRecordRepo"
    }
}
