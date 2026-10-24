package com.fersaiyan.cyanbridge.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "vault_items",
    indices = [
        Index(value = ["memoryRef"], unique = true),
        Index(value = ["updatedAt"]),
    ]
)
data class VaultItemEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val memoryRef: String,
    val keyRef: String,
    val cryptoVersion: Int,
    val nonce: ByteArray,
    val ciphertext: ByteArray,
    val aad: String?,
    val createdAt: Long,
    val updatedAt: Long,
)
