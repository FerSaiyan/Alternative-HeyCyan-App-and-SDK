package com.fersaiyan.cyanbridge.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "transcriptions")
data class TranscriptionRecord(
    /** One transcription record per capture session. */
    @PrimaryKey
    val captureSessionId: Long,

    val status: String,
    val provider: String,
    val language: String?,

    val createdAt: Long,
    val updatedAt: Long,

    val progressPercent: Int,
    val error: String?,

    /** Only stored when privacy toggle is enabled. */
    val transcriptText: String?,
)
