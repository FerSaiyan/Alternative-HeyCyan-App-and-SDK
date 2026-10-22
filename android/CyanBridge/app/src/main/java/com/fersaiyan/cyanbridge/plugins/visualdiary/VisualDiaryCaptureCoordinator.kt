package com.fersaiyan.cyanbridge.plugins.visualdiary

import android.content.Context
import com.fersaiyan.cyanbridge.media.autocapture.AutoLoopVisualNoteGenerator

/**
 * Owns one-shot glasses capture and the handoff into CyanBridge's visual-note pipeline.
 *
 * This intentionally stays inside CyanBridge: glasses protocols, camera capture and vision
 * inference are product/hardware integrations, not Android UI automation responsibilities.
 */
object VisualDiaryCaptureCoordinator {
    data class Result(
        val success: Boolean,
        val detail: String,
    )

    suspend fun prepare(context: Context): Result {
        val appContext = context.applicationContext
        VisualDiaryPreferences.clearLastError(appContext)
        return Result(true, "ready:native_glasses")
    }

    suspend fun captureOnce(
        context: Context,
        captureIndex: Int,
    ): Result {
        val appContext = context.applicationContext

        AutoLoopVisualNoteGenerator.enqueueStandalone(
            context = appContext,
            loopIndex = captureIndex,
            promptOverride = VisualDiaryPreferences.getCustomPrompt(appContext),
        )
        VisualDiaryPreferences.clearLastError(appContext)
        return Result(true, "capture_queued:$captureIndex")
    }
}
