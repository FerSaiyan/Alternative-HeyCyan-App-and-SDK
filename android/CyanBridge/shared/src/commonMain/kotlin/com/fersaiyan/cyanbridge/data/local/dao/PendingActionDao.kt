package com.fersaiyan.cyanbridge.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.fersaiyan.cyanbridge.data.local.entity.PendingAction
import kotlinx.coroutines.flow.Flow

@Dao
interface PendingActionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(action: PendingAction): Long

    @Update
    suspend fun update(action: PendingAction)

    @Query("SELECT * FROM pending_actions WHERE status = :status ORDER BY ts ASC")
    fun getActionsByStatusFlow(status: String): Flow<List<PendingAction>>

    @Query("SELECT * FROM pending_actions WHERE status = :status ORDER BY ts ASC")
    suspend fun getActionsByStatus(status: String): List<PendingAction>

    @Query("SELECT * FROM pending_actions ORDER BY ts DESC LIMIT 100")
    fun getRecentActionsFlow(): Flow<List<PendingAction>>

    @Query("SELECT * FROM pending_actions WHERE id = :id")
    suspend fun getActionById(id: Long): PendingAction?

    @Query("DELETE FROM pending_actions")
    suspend fun clearAll()
}
