package com.fersaiyan.cyanbridge.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.fersaiyan.cyanbridge.data.local.entity.LocalEmbeddingStoreEntity
import com.fersaiyan.cyanbridge.data.local.entity.LocalSearchIndexStateEntity
import com.fersaiyan.cyanbridge.data.local.entity.MemoryModePreferenceEntity
import com.fersaiyan.cyanbridge.data.local.entity.MemoryPolicyMetadataEntity
import com.fersaiyan.cyanbridge.data.local.entity.MigrationStateEntity
import com.fersaiyan.cyanbridge.data.local.entity.SyncPayloadManifestEntity
import com.fersaiyan.cyanbridge.data.local.entity.SyncPreparationQueueEntity
import com.fersaiyan.cyanbridge.data.local.entity.VaultItemEntity
import com.fersaiyan.cyanbridge.data.local.entity.VaultItemKeyEntity
import com.fersaiyan.cyanbridge.data.local.entity.VaultLockStateEntity

@Dao
interface MemoryVaultDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertVaultItem(item: VaultItemEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertVaultItemKey(item: VaultItemKeyEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPolicy(item: MemoryPolicyMetadataEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEmbedding(item: LocalEmbeddingStoreEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertIndexState(item: LocalSearchIndexStateEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertModePreference(item: MemoryModePreferenceEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLockState(item: VaultLockStateEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMigrationState(item: MigrationStateEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPayloadManifest(item: SyncPayloadManifestEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSyncQueue(item: SyncPreparationQueueEntity): Long

    @Query("SELECT * FROM vault_items WHERE memoryRef = :memoryRef LIMIT 1")
    suspend fun getVaultItem(memoryRef: String): VaultItemEntity?

    @Query("SELECT * FROM vault_item_keys WHERE keyRef = :keyRef LIMIT 1")
    suspend fun getVaultKey(keyRef: String): VaultItemKeyEntity?

    @Query("SELECT * FROM memory_policy_metadata WHERE memoryRef = :memoryRef LIMIT 1")
    suspend fun getPolicy(memoryRef: String): MemoryPolicyMetadataEntity?

    @Query("SELECT * FROM local_embedding_store WHERE memoryRef = :memoryRef LIMIT 1")
    suspend fun getEmbedding(memoryRef: String): LocalEmbeddingStoreEntity?

    @Query("SELECT * FROM memory_mode_preferences WHERE id = 1 LIMIT 1")
    suspend fun getModePreference(): MemoryModePreferenceEntity?

    @Query("SELECT * FROM vault_lock_state WHERE id = 1 LIMIT 1")
    suspend fun getLockState(): VaultLockStateEntity?

    @Query("SELECT * FROM migration_state WHERE migrationKey = :migrationKey LIMIT 1")
    suspend fun getMigrationState(migrationKey: String): MigrationStateEntity?

    @Query("SELECT memoryRef FROM memory_policy_metadata WHERE sourceType = :sourceType AND sourceTimestampMs < :beforeTs")
    suspend fun listExpiredMemoryRefs(sourceType: String, beforeTs: Long): List<String>

    @Query("SELECT memoryRef FROM vault_items WHERE memoryRef LIKE :prefixLike ORDER BY updatedAt DESC")
    suspend fun listMemoryRefsByPrefix(prefixLike: String): List<String>

    @Query("SELECT updatedAt FROM vault_items WHERE memoryRef = :memoryRef LIMIT 1")
    suspend fun getUpdatedAt(memoryRef: String): Long?

    @Query("DELETE FROM vault_items WHERE memoryRef = :memoryRef")
    suspend fun deleteVaultItem(memoryRef: String): Int

    @Query("DELETE FROM vault_item_keys WHERE keyRef = :keyRef")
    suspend fun deleteVaultKey(keyRef: String): Int

    @Query("DELETE FROM memory_policy_metadata WHERE memoryRef = :memoryRef")
    suspend fun deletePolicy(memoryRef: String): Int

    @Query("DELETE FROM local_embedding_store WHERE memoryRef = :memoryRef")
    suspend fun deleteEmbedding(memoryRef: String): Int

    @Query("UPDATE sync_preparation_queue SET status = :newStatus, updatedAt = :updatedAt, lastError = :reason WHERE memoryRef = :memoryRef AND status IN ('queued', 'pending_backend')")
    suspend fun cancelQueueForMemoryRef(memoryRef: String, newStatus: String, reason: String?, updatedAt: Long): Int

    @Query("UPDATE sync_preparation_queue SET status = :newStatus, updatedAt = :updatedAt, lastError = :reason WHERE status IN ('queued', 'pending_backend')")
    suspend fun cancelAllQueued(newStatus: String, reason: String?, updatedAt: Long): Int

    @Query("SELECT * FROM sync_preparation_queue ORDER BY createdAt ASC")
    suspend fun listSyncQueue(): List<SyncPreparationQueueEntity>

    @Query("SELECT * FROM sync_payload_manifest ORDER BY createdAt ASC")
    suspend fun listPayloadManifests(): List<SyncPayloadManifestEntity>

    @Query("SELECT * FROM vault_items")
    suspend fun listVaultItems(): List<VaultItemEntity>

    @Query("SELECT * FROM vault_item_keys")
    suspend fun listVaultKeys(): List<VaultItemKeyEntity>

    @Query("SELECT * FROM memory_policy_metadata")
    suspend fun listPolicies(): List<MemoryPolicyMetadataEntity>

    @Query("SELECT * FROM local_embedding_store")
    suspend fun listEmbeddings(): List<LocalEmbeddingStoreEntity>

    @Query("SELECT * FROM local_search_index_state")
    suspend fun listIndexState(): List<LocalSearchIndexStateEntity>

    @Query("SELECT * FROM migration_state")
    suspend fun listMigrationState(): List<MigrationStateEntity>

    @Query("DELETE FROM vault_items")
    suspend fun deleteAllVaultItems()

    @Query("DELETE FROM vault_item_keys")
    suspend fun deleteAllVaultKeys()

    @Query("DELETE FROM memory_policy_metadata")
    suspend fun deleteAllPolicies()

    @Query("DELETE FROM sync_preparation_queue")
    suspend fun deleteAllSyncQueue()

    @Query("DELETE FROM sync_payload_manifest")
    suspend fun deleteAllSyncPayloads()

    @Query("DELETE FROM local_embedding_store")
    suspend fun deleteAllEmbeddings()

    @Query("DELETE FROM local_search_index_state")
    suspend fun deleteAllIndexState()

    @Query("DELETE FROM memory_mode_preferences")
    suspend fun deleteAllModePreferences()

    @Query("DELETE FROM vault_lock_state")
    suspend fun deleteAllLockState()

    @Query("DELETE FROM migration_state")
    suspend fun deleteAllMigrationState()
}
