package com.fersaiyan.cyanbridge.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "vault_lock_state")
data class VaultLockStateEntity(
    @PrimaryKey
    val id: Int = 1,
    val isLocked: Boolean,
    val requiresPassphrase: Boolean,
    val lockedAt: Long,
    val lastUnlockedAt: Long,
)
