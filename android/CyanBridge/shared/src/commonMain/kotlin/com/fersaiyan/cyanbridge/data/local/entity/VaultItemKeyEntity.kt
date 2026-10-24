package com.fersaiyan.cyanbridge.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "vault_item_keys",
    indices = [
        Index(value = ["keyRef"], unique = true),
    ]
)
data class VaultItemKeyEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val keyRef: String,
    val wrappingVersion: Int,
    val wrappedKeyNonce: ByteArray,
    val wrappedKeyCiphertext: ByteArray,
    val createdAt: Long,
    val rotatedAt: Long,
)
