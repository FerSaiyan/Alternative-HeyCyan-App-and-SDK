package com.fersaiyan.cyanbridge.privacy

import android.content.Context
import android.util.Log
import com.fersaiyan.cyanbridge.audio.MeetingCapturePrefs
import com.fersaiyan.cyanbridge.audio.MeetingCaptureService
import com.fersaiyan.cyanbridge.chat.ChatStore
import com.fersaiyan.cyanbridge.memoryvault.MemoryVaultService
import com.fersaiyan.cyanbridge.memoryvault.VaultLockStateManager
import com.fersaiyan.cyanbridge.ui.MyApplication
import java.io.File

/**
 * Chapter 8: Clear local data (best-effort).
 *
 * Deletes:
 * - Room DB tables (chats/messages/notes/capture sessions)
 * - in-memory chat store (Chapter 1 stub)
 * - audio recordings under external files/recordings
 * - relevant SharedPreferences (privacy + meeting capture)
 */
object LocalDataWiper {
    private const val TAG = "LocalDataWiper"

    data class Result(
        val dbCleared: Boolean,
        val recordingsDeleted: Boolean,
        val prefsCleared: Boolean,
    )

    fun wipe(context: Context): Result {
        // If a recording is running, request stop first.
        runCatching { MeetingCaptureService.stop(context) }

        val dbCleared = runCatching {
            MyApplication.database.clearAllTables()
        }.onFailure {
            Log.e(TAG, "Failed to clear database tables", it)
        }.isSuccess

        runCatching { ChatStore.clearAll() }

        val recordingsDeleted = runCatching {
            val dir = File(context.getExternalFilesDir(null), "recordings")
            if (dir.exists()) dir.deleteRecursively() else true
        }.onFailure {
            Log.e(TAG, "Failed to delete recordings", it)
        }.getOrDefault(false)

        val prefsCleared = runCatching {
            // Meeting capture state
            MeetingCapturePrefs.clear(context)
            // Privacy settings
            PrivacyPrefs.clear(context)
        }.onFailure {
            Log.e(TAG, "Failed to clear preferences", it)
        }.isSuccess

        runCatching {
            val memRoot = File(context.filesDir, "local_agent_memory")
            if (memRoot.exists()) memRoot.deleteRecursively()
            MemoryVaultService.resetAllVaultTablesBlocking()
            VaultLockStateManager.clearAll(context)
        }

        return Result(dbCleared = dbCleared, recordingsDeleted = recordingsDeleted, prefsCleared = prefsCleared)
    }
}
