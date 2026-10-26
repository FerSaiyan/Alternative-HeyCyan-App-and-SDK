package com.fersaiyan.cyanbridge.localagent.tasker

import android.content.Context
import java.io.File

/**
 * CyanBridge's private copy of the optional Tasker screenshot for one planning step.
 * Tasker sends the image inside the observation payload; CyanBridge writes it to its own
 * cache, hands the path to the planner, and deletes it when the step is done.
 */
object TaskerScreenshotStore {
    private const val DIR_NAME = "localagent-screenshots"

    fun directory(context: Context): File = File(context.cacheDir, DIR_NAME)

    /** Writes [bytes] as the only screenshot on disk; earlier copies are removed first. */
    fun save(context: Context, bytes: ByteArray): File? = runCatching {
        val extension = TaskerAgentContract.imageExtension(bytes) ?: return null
        val dir = directory(context).apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        File(dir, "step-${System.currentTimeMillis()}.$extension").apply { writeBytes(bytes) }
    }.getOrNull()

    /**
     * Returns [path] only if it is a file directly inside the screenshot directory, within the
     * size limit, and starts with image magic bytes.
     */
    fun usablePath(context: Context, path: String?): String? {
        val file = path?.trim()?.takeIf { it.isNotEmpty() }?.let(::File) ?: return null
        val inside = runCatching {
            file.canonicalFile.parentFile == directory(context).canonicalFile
        }.getOrDefault(false)
        if (!inside || !file.isFile) return null
        if (file.length() !in 1..TaskerAgentContract.MAX_SCREENSHOT_BYTES.toLong()) return null
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null
        return file.path.takeIf { TaskerAgentContract.isSupportedImage(bytes) }
    }

    fun delete(context: Context, path: String?) {
        val file = path?.let(::File) ?: return
        val inside = runCatching {
            file.canonicalFile.parentFile == directory(context).canonicalFile
        }.getOrDefault(false)
        if (inside) file.delete()
    }
}
