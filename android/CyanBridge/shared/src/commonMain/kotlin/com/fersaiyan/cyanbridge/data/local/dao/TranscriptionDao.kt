package com.fersaiyan.cyanbridge.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.fersaiyan.cyanbridge.data.local.entity.TranscriptionRecord

@Dao
interface TranscriptionDao {

    @Query("SELECT * FROM transcriptions WHERE captureSessionId = :captureSessionId LIMIT 1")
    suspend fun getByCaptureSessionId(captureSessionId: Long): TranscriptionRecord?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(record: TranscriptionRecord)

    @Query("DELETE FROM transcriptions WHERE captureSessionId = :captureSessionId")
    suspend fun deleteByCaptureSessionId(captureSessionId: Long)
}
