package com.fersaiyan.cyanbridge.data.repository

import androidx.room.withTransaction
import com.fersaiyan.cyanbridge.data.local.AppDatabase
import com.fersaiyan.cyanbridge.data.local.entity.CaptureSession
import com.fersaiyan.cyanbridge.data.local.entity.Chat
import com.fersaiyan.cyanbridge.data.local.entity.Message
import com.fersaiyan.cyanbridge.data.local.entity.Note
import com.fersaiyan.cyanbridge.data.local.entity.TranscriptionRecord
import kotlinx.coroutines.flow.Flow

/**
 * Thin data-access facade over Room DAOs.
 *
 * This repository intentionally avoids business rules; feature modules compose behavior on top
 * of these CRUD primitives.
 */
class CyanBridgeRepository(private val db: AppDatabase) {

    // Chat operations
    fun getAllChats(): Flow<List<Chat>> = db.chatDao().getAllChats()

    suspend fun getAllChatsOnce(): List<Chat> = db.chatDao().getAllChatsOnce()

    suspend fun getChatById(id: String): Chat? = db.chatDao().getChatById(id)

    suspend fun insertChat(chat: Chat) = db.chatDao().insertChat(chat)

    suspend fun deleteChat(chat: Chat) = db.chatDao().deleteChat(chat)

    suspend fun deleteChatById(chatId: String) = db.chatDao().deleteChatById(chatId)

    suspend fun deleteAllChats() = db.chatDao().deleteAllChats()

    // Message operations
    fun getMessagesForChat(chatId: String): Flow<List<Message>> = db.messageDao().getMessagesForChat(chatId)

    suspend fun getMessagesForChatOnce(chatId: String): List<Message> = db.messageDao().getMessagesForChatOnce(chatId)

    suspend fun insertMessage(message: Message) = db.messageDao().insertMessage(message)

    suspend fun deleteAllMessages() = db.messageDao().deleteAllMessages()

    suspend fun deleteMessagesForChat(chatId: String) = db.messageDao().deleteMessagesByChatId(chatId)

    // Note operations
    fun getAllNotes(): Flow<List<Note>> = db.noteDao().getAllNotes()

    suspend fun getNoteById(id: Long): Note? = db.noteDao().getNoteById(id)

    suspend fun insertNote(note: Note) = db.noteDao().insertNote(note)

    suspend fun deleteNote(note: Note) = db.noteDao().deleteNote(note)

    suspend fun updateNote(note: Note) = db.noteDao().updateNote(note)

    // Meeting capture sessions
    fun getAllCaptureSessions(): Flow<List<CaptureSession>> = db.captureSessionDao().getAllSessions()

    suspend fun insertCaptureSession(session: CaptureSession): Long = db.captureSessionDao().insert(session)

    suspend fun getCaptureSessionByAudioPath(audioPath: String): CaptureSession? =
        db.captureSessionDao().getByAudioPath(audioPath)

    suspend fun deleteCaptureSession(captureSessionId: Long): Boolean = db.withTransaction {
        db.transcriptionDao().deleteByCaptureSessionId(captureSessionId)
        db.captureTranscriptDao().deleteForSession(captureSessionId)
        db.captureSessionDao().deleteById(captureSessionId) > 0
    }

    // Chapter 6: Transcriptions
    suspend fun getTranscriptionByCaptureSessionId(captureSessionId: Long): TranscriptionRecord? = db.transcriptionDao().getByCaptureSessionId(captureSessionId)

    suspend fun upsertTranscription(record: TranscriptionRecord) = db.transcriptionDao().upsert(record)

    // Helpers
    suspend fun getLatestCaptureSession(): CaptureSession? = db.captureSessionDao().getLatestSession()
}
