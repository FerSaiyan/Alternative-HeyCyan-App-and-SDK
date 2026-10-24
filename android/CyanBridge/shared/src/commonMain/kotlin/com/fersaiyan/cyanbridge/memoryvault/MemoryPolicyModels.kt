package com.fersaiyan.cyanbridge.memoryvault

import com.fersaiyan.cyanbridge.shared.settings.MemorySourceType

enum class MemorySensitivityLevel {
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL,
}

enum class MemorySyncEligibility {
    LOCAL_ONLY,
    ENCRYPTED_SYNC_ALLOWED,
    CLOUD_INDEX_ALLOWED,
}

data class MemoryPolicyMetadata(
    val memoryRef: String,
    val sourceType: MemorySourceType,
    val sensitivityLevel: MemorySensitivityLevel,
    val syncEligibility: MemorySyncEligibility,
    val retentionPolicy: String?,
    val derivedFromIds: List<String>,
    val provenance: String?,
    val containsPotentialSecrets: Boolean,
    val requiresExplicitConsentForCloud: Boolean,
    val sourceTimestampMs: Long,
)
