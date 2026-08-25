package com.fersaiyan.cyanbridge.plugins.visualdiary

import android.content.Context
import android.util.Log
import com.fersaiyan.cyanbridge.devices.DeviceProfileStore
import com.fersaiyan.cyanbridge.devices.metarayban.MetaRaybanManager
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
        if (!DeviceProfileStore.isMetaSelected(appContext)) {
            VisualDiaryPreferences.clearLastError(appContext)
            return Result(true, "ready:native_glasses")
        }

        val manager = MetaRaybanManager.getInstance(appContext)
        if (!manager.isInitialized.value) manager.initialize()
        if (!manager.awaitCameraReady()) {
            val detail = manager.lastError.value
                ?: "Register and connect a Meta camera before using Visual Diary"
            Log.e(TAG, "Meta Visual Diary unavailable: $detail\n${manager.diagnosticsSnapshot()}")
            VisualDiaryPreferences.setLastError(appContext, "Meta camera unavailable: $detail")
            return Result(false, "meta_camera_unavailable:$detail")
        }

        VisualDiaryPreferences.clearLastError(appContext)
        return Result(true, "ready:meta")
    }

    suspend fun captureOnce(
        context: Context,
        captureIndex: Int,
    ): Result {
        val appContext = context.applicationContext

        if (DeviceProfileStore.isMetaSelected(appContext)) {
            val ready = prepare(appContext)
            if (!ready.success) return ready

            val manager = MetaRaybanManager.getInstance(appContext)
            val file = runCatching {
                val photo = manager.capturePhotoOnce()
                manager.savePhotoForProcessing(photo, "META_VISUAL_NOTE_$captureIndex")
            }.onFailure {
                val detail = manager.lastError.value ?: it.message ?: "camera unavailable"
                Log.e(TAG, "Meta DAT photo capture failed: $detail\n${manager.diagnosticsSnapshot()}", it)
                VisualDiaryPreferences.setLastError(appContext, "Meta capture failed: $detail")
            }.getOrNull()

            if (file == null) {
                val detail = manager.lastError.value ?: "Meta camera unavailable"
                if (manager.lastError.value.isNullOrBlank()) {
                    VisualDiaryPreferences.setLastError(appContext, detail)
                }
                return Result(false, "meta_capture_failed:$detail")
            }

            AutoLoopVisualNoteGenerator.enqueueCapturedPhoto(
                context = appContext,
                loopIndex = captureIndex,
                image = file,
                promptOverride = VisualDiaryPreferences.getCustomPrompt(appContext),
            )
            VisualDiaryPreferences.clearLastError(appContext)
            return Result(true, "capture_queued:meta:$captureIndex")
        }

        AutoLoopVisualNoteGenerator.enqueueStandalone(
            context = appContext,
            loopIndex = captureIndex,
            promptOverride = VisualDiaryPreferences.getCustomPrompt(appContext),
        )
        VisualDiaryPreferences.clearLastError(appContext)
        return Result(true, "capture_queued:native:$captureIndex")
    }

    private const val TAG = "VisualDiaryCapture"
}
