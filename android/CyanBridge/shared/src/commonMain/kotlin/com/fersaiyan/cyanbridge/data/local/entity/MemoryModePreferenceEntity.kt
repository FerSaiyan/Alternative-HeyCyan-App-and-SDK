package com.fersaiyan.cyanbridge.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "memory_mode_preferences")
data class MemoryModePreferenceEntity(
    @PrimaryKey
    val id: Int = 1,
    val selectedMode: String,
    val screenOcrRetentionDays: Int,
    val screenOcrCaptureEnabled: Boolean,
    val explicitFactsSyncEnabled: Boolean,
    val dailyFactsSyncEnabled: Boolean,
    val screenOcrSyncEnabled: Boolean,
    val derivedSummariesSyncEnabled: Boolean,
    val updatedAt: Long,
)
