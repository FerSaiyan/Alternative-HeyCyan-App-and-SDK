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
 * Chapter 8: "Clear local data" implementation.
 *
 * Deletes:
 * - Room tables (chats/messages/notes/capture sessions)
 * - in-memory ChatStore (Chapter 1 stub)
 * - meeting audio files under externalFilesDir/recordings
 * - relevant SharedPreferences (privacy + capture state)
 */
object LocalDataClearer {
    private const val TAG = "LocalDataClearer"

    data class Result(
        val deletedFiles: Int,
        val errors: List<String>,
    )

    fun clearAll(context: Context): Result {
        val appCtx = context.applicationContext
        val errors = mutableListOf<String>()

        // Stop any ongoing recording first.
        runCatching {
            val state = MeetingCapturePrefs.getState(appCtx)
            if (state.isRecording) {
                MeetingCaptureService.stop(appCtx)
            }
        }.onFailure { errors.add("stop_recording_failed: ${it.message}") }

        // Clear in-memory chat store (Chapter 1).
        runCatching { ChatStore.clearAll() }
            .onFailure { errors.add("chatstore_clear_failed: ${it.message}") }

        // Clear DB tables.
        runCatching {
            MyApplication.database.clearAllTables()
        }.onFailure { e ->
            Log.e(TAG, "Failed clearing Room tables", e)
            errors.add("db_clear_failed: ${e.message}")
        }

        // Delete meeting recordings.
        val deletedFiles = runCatching {
            deleteRecordings(appCtx)
        }.getOrElse { e ->
            Log.e(TAG, "Failed deleting recordings", e)
            errors.add("delete_recordings_failed: ${e.message}")
            0
        }

        runCatching {
            val memoryRoot = File(appCtx.filesDir, "local_agent_memory")
            if (memoryRoot.exists()) memoryRoot.deleteRecursively()
        }.onFailure { errors.add("delete_memory_files_failed: ${it.message}") }

        runCatching { com.fersaiyan.cyanbridge.ai.speaker.VoiceProfile.delete(appCtx) }
            .onFailure { errors.add("delete_voice_profile_failed: ${it.message}") }

        runCatching {
            MemoryVaultService.resetAllVaultTablesBlocking()
            VaultLockStateManager.clearAll(appCtx)
        }.onFailure { errors.add("vault_reset_failed: ${it.message}") }

        // Reset prefs.
        runCatching { PrivacyPrefs.clear(appCtx) }
            .onFailure { errors.add("privacy_prefs_clear_failed: ${it.message}") }

        runCatching { MeetingCapturePrefs.clear(appCtx) }
            .onFailure { errors.add("meeting_capture_prefs_clear_failed: ${it.message}") }

        return Result(deletedFiles = deletedFiles, errors = errors)
    }

    private fun deleteRecordings(context: Context): Int {
        val dir = File(context.getExternalFilesDir(null), "recordings")
        if (!dir.exists()) return 0

        var deleted = 0
        dir.listFiles()?.forEach { f ->
            if (f.isFile) {
                if (runCatching { f.delete() }.getOrDefault(false)) deleted++
            } else {
                // Best-effort: nuke nested directories too.
                if (runCatching { f.deleteRecursively() }.getOrDefault(false)) deleted++
            }
        }

        // If empty, remove directory.
        runCatching { dir.delete() }

        return deleted
    }
}
