package com.fersaiyan.cyanbridge.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "memory_policy_metadata",
    indices = [
        Index(value = ["sourceType"]),
        Index(value = ["syncEligibility"]),
        Index(value = ["sourceTimestampMs"]),
    ]
)
data class MemoryPolicyMetadataEntity(
    @PrimaryKey
    val memoryRef: String,
    val sourceType: String,
    val sensitivityLevel: String,
    val syncEligibility: String,
    val retentionPolicy: String?,
    val derivedFromIdsCsv: String?,
    val provenance: String?,
    val containsPotentialSecrets: Boolean,
    val requiresExplicitConsentForCloud: Boolean,
    val sourceTimestampMs: Long,
    val createdAt: Long,
    val updatedAt: Long,
)
