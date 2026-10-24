package com.fersaiyan.cyanbridge.data.local.dao

import androidx.room.*
import com.fersaiyan.cyanbridge.data.local.entity.Chat
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatDao {
    @Query("SELECT * FROM chats ORDER BY updatedAt DESC")
    fun getAllChats(): Flow<List<Chat>>

    @Query("SELECT * FROM chats ORDER BY updatedAt DESC")
    suspend fun getAllChatsOnce(): List<Chat>

    @Query("SELECT * FROM chats WHERE id = :id")
    suspend fun getChatById(id: String): Chat?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChat(chat: Chat)

    @Delete
    suspend fun deleteChat(chat: Chat)

    @Query("DELETE FROM chats WHERE id = :id")
    suspend fun deleteChatById(id: String)

    @Query("DELETE FROM chats")
    suspend fun deleteAllChats()
}
