package com.fersaiyan.cyanbridge.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "capture_sessions")
data class CaptureSession(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val startedAt: Long,
    val endedAt: Long,
    val durationSec: Long,
    val deviceClass: String,
    val captureSource: String,
    val audioPath: String,
    val timerDurationSec: Long?,
    val stopReason: String?,
    val error: String?,
)
