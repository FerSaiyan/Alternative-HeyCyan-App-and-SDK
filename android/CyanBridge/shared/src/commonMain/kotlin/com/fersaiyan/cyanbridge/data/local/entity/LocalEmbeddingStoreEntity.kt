package com.fersaiyan.cyanbridge.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "local_embedding_store")
data class LocalEmbeddingStoreEntity(
    @PrimaryKey
    val memoryRef: String,
    val embeddingJson: String,
    val tagsJson: String,
    val modelVersion: String,
    val updatedAt: Long,
)
