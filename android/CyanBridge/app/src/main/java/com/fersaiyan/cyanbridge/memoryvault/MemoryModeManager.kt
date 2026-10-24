package com.fersaiyan.cyanbridge.memoryvault

import android.content.Context
import com.fersaiyan.cyanbridge.shared.prefs.MemoryPrefs.MemoryMode
import com.fersaiyan.cyanbridge.shared.prefs.get
import com.fersaiyan.cyanbridge.shared.prefs.set
import com.fersaiyan.cyanbridge.shared.settings.MemoryPrivacyMode
import com.fersaiyan.cyanbridge.shared.settings.MemorySourceType
import com.fersaiyan.cyanbridge.data.local.entity.MemoryModePreferenceEntity
import com.fersaiyan.cyanbridge.ui.MyApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

object MemoryModeManager {
    fun getSelectedMode(context: Context): MemoryPrivacyMode = MemoryMode.mode[context]

    fun setSelectedMode(context: Context, mode: MemoryPrivacyMode) {
        MemoryMode.mode[context] = mode
        persistSnapshotBestEffort(context)
    }

    fun getScreenOcrRetentionDays(context: Context): Int = MemoryMode.ocrRetentionDays[context]

    fun setScreenOcrRetentionDays(context: Context, days: Int) {
        MemoryMode.ocrRetentionDays[context] = days
        persistSnapshotBestEffort(context)
    }

    fun isScreenOcrCaptureEnabled(context: Context): Boolean = MemoryMode.ocrCaptureEnabled[context]

    fun setScreenOcrCaptureEnabled(context: Context, enabled: Boolean) {
        MemoryMode.ocrCaptureEnabled[context] = enabled
        persistSnapshotBestEffort(context)
    }

    fun isSourceSyncEnabled(context: Context, sourceType: MemorySourceType): Boolean =
        syncPref(sourceType)?.get(context) ?: (sourceType == MemorySourceType.IMPORTED_TEXT)

    fun setSourceSyncEnabled(context: Context, sourceType: MemorySourceType, enabled: Boolean) {
        val pref = syncPref(sourceType) ?: return
        pref[context] = enabled
        persistSnapshotBestEffort(context)
    }

    /** Imported text always syncs and system notes never do, so they have no switch. */
    private fun syncPref(sourceType: MemorySourceType) = when (sourceType) {
        MemorySourceType.EXPLICIT_USER_FACT -> MemoryMode.syncExplicit
        MemorySourceType.AUTO_DAILY_FACT -> MemoryMode.syncDaily
        MemorySourceType.SCREEN_OCR -> MemoryMode.syncOcr
        MemorySourceType.DERIVED_SUMMARY -> MemoryMode.syncDerived
        MemorySourceType.IMPORTED_TEXT -> null
        MemorySourceType.SYSTEM_NOTE -> null
    }

    fun isEncryptedSyncBackendConfigured(): Boolean = false

    fun isFastCloudBackendConfigured(): Boolean = false

    fun isConfidentialCloudBackendConfigured(): Boolean = false

    fun isModeBackendAvailable(mode: MemoryPrivacyMode): Boolean {
        return when (mode) {
            MemoryPrivacyMode.PRIVATE_LOCAL -> true
            MemoryPrivacyMode.ENCRYPTED_SYNC -> isEncryptedSyncBackendConfigured()
            MemoryPrivacyMode.FAST_CLOUD_MEMORY -> isFastCloudBackendConfigured()
            MemoryPrivacyMode.CONFIDENTIAL_CLOUD_BETA -> isConfidentialCloudBackendConfigured()
        }
    }

    fun modeAvailabilityText(mode: MemoryPrivacyMode): String {
        return when (mode) {
            MemoryPrivacyMode.PRIVATE_LOCAL -> "Fully available on this device."
            MemoryPrivacyMode.ENCRYPTED_SYNC -> "Prepared locally. Secure sync backend is not configured yet."
            MemoryPrivacyMode.FAST_CLOUD_MEMORY -> "Unavailable: requires future cloud memory backend."
            MemoryPrivacyMode.CONFIDENTIAL_CLOUD_BETA -> "Unavailable: requires future confidential cloud backend."
        }
    }

    fun persistSnapshotBestEffort(context: Context) {
        runCatching {
            runBlocking(Dispatchers.IO) {
                persistSnapshot(context)
            }
        }
    }

    suspend fun persistSnapshot(context: Context) = withContext(Dispatchers.IO) {
        val dao = MyApplication.database.memoryVaultDao()
        dao.upsertModePreference(
            MemoryModePreferenceEntity(
                selectedMode = getSelectedMode(context).name,
                screenOcrRetentionDays = getScreenOcrRetentionDays(context),
                screenOcrCaptureEnabled = isScreenOcrCaptureEnabled(context),
                explicitFactsSyncEnabled = isSourceSyncEnabled(context, MemorySourceType.EXPLICIT_USER_FACT),
                dailyFactsSyncEnabled = isSourceSyncEnabled(context, MemorySourceType.AUTO_DAILY_FACT),
                screenOcrSyncEnabled = isSourceSyncEnabled(context, MemorySourceType.SCREEN_OCR),
                derivedSummariesSyncEnabled = isSourceSyncEnabled(context, MemorySourceType.DERIVED_SUMMARY),
                updatedAt = System.currentTimeMillis(),
            )
        )
    }
}
