package com.fersaiyan.cyanbridge.memoryvault

import android.content.Context
import com.fersaiyan.cyanbridge.shared.settings.MemoryPrivacyMode
import com.fersaiyan.cyanbridge.shared.settings.MemorySourceType
import com.fersaiyan.cyanbridge.data.local.entity.MemoryPolicyMetadataEntity
import com.fersaiyan.cyanbridge.ui.MyApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.Locale

object MemoryPolicyService {
    private val secretRegexes = listOf(
        Regex("(?i)password\\s*[:=]"),
        Regex("(?i)api[_-]?key\\s*[:=]"),
        Regex("(?i)token\\s*[:=]"),
        Regex("(?i)secret\\s*[:=]"),
        Regex("\\b\\d{6,}\\b"),
    )

    fun classifyForMemoryRef(
        context: Context,
        memoryRef: String,
        text: String,
        sourceTimestampMs: Long = System.currentTimeMillis(),
        derivedFromIds: List<String> = emptyList(),
        provenance: String? = null,
    ): MemoryPolicyMetadata {
        val source = inferSourceType(memoryRef)
        val baseSensitivity = when (source) {
            MemorySourceType.SCREEN_OCR -> MemorySensitivityLevel.HIGH
            MemorySourceType.DERIVED_SUMMARY -> MemorySensitivityLevel.MEDIUM
            MemorySourceType.EXPLICIT_USER_FACT -> MemorySensitivityLevel.MEDIUM
            MemorySourceType.AUTO_DAILY_FACT -> MemorySensitivityLevel.MEDIUM
            MemorySourceType.IMPORTED_TEXT -> MemorySensitivityLevel.MEDIUM
            MemorySourceType.SYSTEM_NOTE -> MemorySensitivityLevel.LOW
        }

        var syncEligibility = when (source) {
            MemorySourceType.SCREEN_OCR -> MemorySyncEligibility.LOCAL_ONLY
            MemorySourceType.DERIVED_SUMMARY -> MemorySyncEligibility.ENCRYPTED_SYNC_ALLOWED
            MemorySourceType.EXPLICIT_USER_FACT -> MemorySyncEligibility.ENCRYPTED_SYNC_ALLOWED
            MemorySourceType.AUTO_DAILY_FACT -> MemorySyncEligibility.ENCRYPTED_SYNC_ALLOWED
            MemorySourceType.IMPORTED_TEXT -> MemorySyncEligibility.ENCRYPTED_SYNC_ALLOWED
            MemorySourceType.SYSTEM_NOTE -> MemorySyncEligibility.LOCAL_ONLY
        }

        val containsSecrets = containsPotentialSecrets(text)
        var sensitivity = if (containsSecrets) {
            maxOf(baseSensitivity, MemorySensitivityLevel.HIGH)
        } else {
            baseSensitivity
        }

        if (!MemoryModeManager.isSourceSyncEnabled(context, source)) {
            syncEligibility = MemorySyncEligibility.LOCAL_ONLY
        }

        val inherited = mergeFromDerivedSources(derivedFromIds)
        if (inherited != null) {
            sensitivity = maxOf(sensitivity, inherited.sensitivityLevel)
            syncEligibility = minOf(syncEligibility, inherited.syncEligibility)
        }

        if (containsSecrets) {
            syncEligibility = MemorySyncEligibility.LOCAL_ONLY
        }

        val retentionPolicy = when (source) {
            MemorySourceType.SCREEN_OCR -> "days:${MemoryModeManager.getScreenOcrRetentionDays(context)}"
            else -> null
        }

        return MemoryPolicyMetadata(
            memoryRef = memoryRef,
            sourceType = source,
            sensitivityLevel = sensitivity,
            syncEligibility = syncEligibility,
            retentionPolicy = retentionPolicy,
            derivedFromIds = derivedFromIds,
            provenance = provenance,
            containsPotentialSecrets = containsSecrets,
            requiresExplicitConsentForCloud = source == MemorySourceType.SCREEN_OCR || containsSecrets,
            sourceTimestampMs = sourceTimestampMs,
        )
    }

    fun containsPotentialSecrets(text: String): Boolean {
        val sample = text.lowercase(Locale.US).take(25_000)
        return secretRegexes.any { it.containsMatchIn(sample) }
    }

    fun inferSourceType(memoryRef: String): MemorySourceType {
        val ref = memoryRef.lowercase(Locale.US)
        return when {
            ref.contains("screen_captures/") || ref.startsWith("memory_chunk:") -> MemorySourceType.SCREEN_OCR
            ref.contains("daily_summaries/") || ref.contains("daily_bullets/") -> MemorySourceType.DERIVED_SUMMARY
            ref.contains("user_facts") -> MemorySourceType.EXPLICIT_USER_FACT
            ref.contains("daily_facts") -> MemorySourceType.AUTO_DAILY_FACT
            ref.contains("import") -> MemorySourceType.IMPORTED_TEXT
            else -> MemorySourceType.SYSTEM_NOTE
        }
    }

    fun isEligibleForRetrieval(mode: MemoryPrivacyMode, policy: MemoryPolicyMetadata): Boolean {
        return when (mode) {
            MemoryPrivacyMode.PRIVATE_LOCAL -> true
            MemoryPrivacyMode.ENCRYPTED_SYNC -> true
            MemoryPrivacyMode.FAST_CLOUD_MEMORY,
            MemoryPrivacyMode.CONFIDENTIAL_CLOUD_BETA,
            -> policy.syncEligibility == MemorySyncEligibility.CLOUD_INDEX_ALLOWED
        }
    }

    suspend fun upsertPolicy(policy: MemoryPolicyMetadata) = withContext(Dispatchers.IO) {
        MyApplication.database.memoryVaultDao().upsertPolicy(policy.toEntity())
    }

    fun upsertPolicyBlocking(policy: MemoryPolicyMetadata) {
        runCatching {
            runBlocking(Dispatchers.IO) { upsertPolicy(policy) }
        }
    }

    suspend fun getPolicy(memoryRef: String): MemoryPolicyMetadata? = withContext(Dispatchers.IO) {
        MyApplication.database.memoryVaultDao().getPolicy(memoryRef)?.toModel()
    }

    fun getPolicyBlocking(memoryRef: String): MemoryPolicyMetadata? {
        return runCatching {
            runBlocking(Dispatchers.IO) { getPolicy(memoryRef) }
        }.getOrNull()
    }

    fun isMemoryRefSearchEligible(context: Context, memoryRef: String): Boolean {
        val mode = MemoryModeManager.getSelectedMode(context)
        val policy = getPolicyBlocking(memoryRef)
            ?: classifyForMemoryRef(context, memoryRef, text = "")
        return isEligibleForRetrieval(mode, policy)
    }

    fun shouldQueueForEncryptedSync(context: Context, policy: MemoryPolicyMetadata): Boolean {
        if (policy.syncEligibility == MemorySyncEligibility.LOCAL_ONLY) return false
        if (policy.requiresExplicitConsentForCloud) return false
        return MemoryModeManager.getSelectedMode(context) == MemoryPrivacyMode.ENCRYPTED_SYNC
    }

    private fun mergeFromDerivedSources(derivedFromIds: List<String>): MemoryPolicyMetadata? {
        if (derivedFromIds.isEmpty()) return null
        val items = runCatching {
            runBlocking(Dispatchers.IO) {
                derivedFromIds.mapNotNull { id ->
                    MyApplication.database.memoryVaultDao().getPolicy(id)?.toModel()
                }
            }
        }.getOrDefault(emptyList())
        if (items.isEmpty()) return null

        val inherited = inheritRestrictions(items) ?: return null

        return MemoryPolicyMetadata(
            memoryRef = "derived",
            sourceType = MemorySourceType.DERIVED_SUMMARY,
            sensitivityLevel = inherited.first,
            syncEligibility = inherited.second,
            retentionPolicy = null,
            derivedFromIds = derivedFromIds,
            provenance = null,
            containsPotentialSecrets = items.any { it.containsPotentialSecrets },
            requiresExplicitConsentForCloud = items.any { it.requiresExplicitConsentForCloud },
            sourceTimestampMs = System.currentTimeMillis(),
        )
    }

    fun inheritRestrictions(items: List<MemoryPolicyMetadata>): Pair<MemorySensitivityLevel, MemorySyncEligibility>? {
        if (items.isEmpty()) return null
        val sensitivity = items.maxOf { it.sensitivityLevel }
        val sync = items.minOf { it.syncEligibility }
        return sensitivity to sync
    }

    private fun MemoryPolicyMetadata.toEntity(): MemoryPolicyMetadataEntity {
        val now = System.currentTimeMillis()
        return MemoryPolicyMetadataEntity(
            memoryRef = memoryRef,
            sourceType = sourceType.name.lowercase(Locale.US),
            sensitivityLevel = sensitivityLevel.name.lowercase(Locale.US),
            syncEligibility = syncEligibility.name.lowercase(Locale.US),
            retentionPolicy = retentionPolicy,
            derivedFromIdsCsv = derivedFromIds.joinToString(","),
            provenance = provenance,
            containsPotentialSecrets = containsPotentialSecrets,
            requiresExplicitConsentForCloud = requiresExplicitConsentForCloud,
            sourceTimestampMs = sourceTimestampMs,
            createdAt = now,
            updatedAt = now,
        )
    }

    private fun MemoryPolicyMetadataEntity.toModel(): MemoryPolicyMetadata {
        return MemoryPolicyMetadata(
            memoryRef = memoryRef,
            sourceType = enumValueOf(sourceType.uppercase(Locale.US)),
            sensitivityLevel = enumValueOf(sensitivityLevel.uppercase(Locale.US)),
            syncEligibility = enumValueOf(syncEligibility.uppercase(Locale.US)),
            retentionPolicy = retentionPolicy,
            derivedFromIds = derivedFromIdsCsv
                ?.split(',')
                ?.map { it.trim() }
                ?.filter { it.isNotBlank() }
                .orEmpty(),
            provenance = provenance,
            containsPotentialSecrets = containsPotentialSecrets,
            requiresExplicitConsentForCloud = requiresExplicitConsentForCloud,
            sourceTimestampMs = sourceTimestampMs,
        )
    }
}
