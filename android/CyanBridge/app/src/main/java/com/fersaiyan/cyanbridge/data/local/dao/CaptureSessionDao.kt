package com.fersaiyan.cyanbridge.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.fersaiyan.cyanbridge.data.local.entity.CaptureSession
import kotlinx.coroutines.flow.Flow

@Dao
interface CaptureSessionDao {
    @Query("SELECT * FROM capture_sessions ORDER BY startedAt DESC")
    fun getAllSessions(): Flow<List<CaptureSession>>

    @Query("SELECT * FROM capture_sessions ORDER BY startedAt DESC LIMIT 1")
    suspend fun getLatestSession(): CaptureSession?

    @Query("SELECT * FROM capture_sessions WHERE audioPath = :audioPath LIMIT 1")
    suspend fun getByAudioPath(audioPath: String): CaptureSession?

    @Query("DELETE FROM capture_sessions WHERE id = :sessionId")
    suspend fun deleteById(sessionId: Long): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(session: CaptureSession): Long
}
