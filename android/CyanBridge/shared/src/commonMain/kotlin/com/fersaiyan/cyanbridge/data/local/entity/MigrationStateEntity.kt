package com.fersaiyan.cyanbridge.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "migration_state")
data class MigrationStateEntity(
    @PrimaryKey
    val migrationKey: String,
    val status: String,
    val lastProcessedRef: String?,
    val updatedAt: Long,
)
