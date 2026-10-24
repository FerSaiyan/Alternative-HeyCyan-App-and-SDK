package com.fersaiyan.cyanbridge.ui

import android.content.Context
import com.fersaiyan.cyanbridge.shared.prefs.MemoryPrefs
import com.fersaiyan.cyanbridge.shared.prefs.clear
import java.io.File

object LocalDataCleaner {

    data class Result(
        val deletedFiles: Int,
        val deletedDirs: Int,
        val errors: Int,
    )

    /**
     * Clears app-local data that may include cached AI artifacts and stored transcripts.
     *
     * Intentionally does NOT delete user media saved to public MediaStore locations
     * (e.g., DCIM/CyanBridge).
     */
    fun clearLocalData(context: Context): Result {
        val targets = mutableListOf<Pair<File, Boolean>>()

        // Internal caches (clear contents, keep directory).
        targets += context.cacheDir to true
        context.externalCacheDir?.let { targets += it to true }

        // App-private storage where we may store transcripts/AI artifacts.
        targets += File(context.filesDir, "transcripts") to false
        targets += File(context.filesDir, "ai") to false
        targets += File(context.filesDir, "privacy") to false

        // App-scoped external storage (safe to clear; not the public gallery).
        context.getExternalFilesDir(null)?.let { root ->
            targets += File(root, "transcripts") to false
            targets += File(root, "ai") to false
            targets += File(root, "DCIM") to false
        }

        var deletedFiles = 0
        var deletedDirs = 0
        var errors = 0

        for ((t, keepDir) in targets.distinctBy { it.first.absolutePath }) {
            val r = if (keepDir) {
                deleteChildren(t)
            } else {
                deleteRecursively(t)
            }
            deletedFiles += r.deletedFiles
            deletedDirs += r.deletedDirs
            errors += r.errors
        }

        // Best-effort: clear any legacy transcript pref file if present.
        try {
            MemoryPrefs.LegacyTranscripts.clear(context)
        } catch (_: Throwable) {
            // ignore
        }

        return Result(deletedFiles = deletedFiles, deletedDirs = deletedDirs, errors = errors)
    }

    private fun deleteChildren(dir: File): Result {
        if (!dir.exists() || !dir.isDirectory) return Result(0, 0, 0)

        var deletedFiles = 0
        var deletedDirs = 0
        var errors = 0

        val children = dir.listFiles() ?: return Result(0, 0, 0)
        for (c in children) {
            val r = deleteRecursively(c)
            deletedFiles += r.deletedFiles
            deletedDirs += r.deletedDirs
            errors += r.errors
        }
        return Result(deletedFiles, deletedDirs, errors)
    }

    private fun deleteRecursively(file: File): Result {
        if (!file.exists()) return Result(0, 0, 0)

        var deletedFiles = 0
        var deletedDirs = 0
        var errors = 0

        if (file.isDirectory) {
            val children = file.listFiles()
            if (children != null) {
                for (c in children) {
                    val r = deleteRecursively(c)
                    deletedFiles += r.deletedFiles
                    deletedDirs += r.deletedDirs
                    errors += r.errors
                }
            }
            if (!file.delete()) {
                errors += 1
            } else {
                deletedDirs += 1
            }
        } else {
            if (!file.delete()) {
                errors += 1
            } else {
                deletedFiles += 1
            }
        }

        return Result(deletedFiles, deletedDirs, errors)
    }
}
