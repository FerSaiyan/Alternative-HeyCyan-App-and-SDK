package com.fersaiyan.cyanbridge.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "sync_preparation_queue",
    indices = [
        Index(value = ["memoryRef"]),
        Index(value = ["status"]),
        Index(value = ["createdAt"]),
    ]
)
data class SyncPreparationQueueEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val memoryRef: String,
    val actionType: String,
    val status: String,
    val payloadManifestId: Long,
    val lastError: String?,
    val createdAt: Long,
    val updatedAt: Long,
)
