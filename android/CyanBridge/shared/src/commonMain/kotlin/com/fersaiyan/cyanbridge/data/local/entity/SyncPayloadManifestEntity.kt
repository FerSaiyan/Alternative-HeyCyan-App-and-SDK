package com.fersaiyan.cyanbridge.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "sync_payload_manifest",
    indices = [
        Index(value = ["memoryRef"]),
        Index(value = ["createdAt"]),
    ]
)
data class SyncPayloadManifestEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val memoryRef: String,
    val schemaVersion: Int,
    val payloadJson: String,
    val checksumSha256: String,
    val createdAt: Long,
)
