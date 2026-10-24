package com.fersaiyan.cyanbridge.localagent

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import com.fersaiyan.cyanbridge.agent.LocalAgentPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileOutputStream

data class LocalAgentScreenshotResult(
    val bitmap: Bitmap? = null,
    val error: String? = null,
)

/** Creates an ephemeral, bounded screenshot file only for an explicitly enabled planning request. */
object LocalAgentScreenshotCapture {

    sealed interface Capture {
        data class Available(val file: File) : Capture
        data class Unavailable(val reason: String) : Capture
    }

    suspend fun captureForPlanning(
        context: Context,
        observation: LocalAgentObservation,
    ): Capture {
        LocalAgentDeviceState.availability(context).takeIf { it != LocalAgentDeviceState.Availability.READY }?.let {
            return Capture.Unavailable("${it.statusText}; using text-only planning.")
        }
        if (!LocalAgentPrefs.isScreenshotPlanningEnabled(context)) {
            return Capture.Unavailable("Screenshot planning is off; using text-only planning.")
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return Capture.Unavailable("Screenshots require Android 11 or newer; using text-only planning.")
        }
        if (!LocalAgentAccessibilityBridge.isConnected()) {
            return Capture.Unavailable("Accessibility is unavailable; using text-only planning.")
        }

        val observedPackage = observation.packageName?.trim()?.lowercase().orEmpty()
        val beforePackage = LocalAgentAccessibilityBridge.activeWindowPackageName()
            ?.trim()
            ?.lowercase()
            .orEmpty()
        if (beforePackage.isBlank() || beforePackage != observedPackage) {
            return Capture.Unavailable("Current app is unknown; using text-only planning.")
        }
        LocalAgentSafetyPolicy.blockedReason(context, beforePackage)?.let {
            return Capture.Unavailable("Screenshot blocked by CyanBridge privacy settings.")
        }

        val screenshot = withTimeoutOrNull(CAPTURE_TIMEOUT_MS) {
            LocalAgentAccessibilityBridge.captureScreenshot()
        } ?: LocalAgentScreenshotResult(error = "screenshot_timeout")
        val bitmap = screenshot.bitmap ?: return Capture.Unavailable(
            "Screenshot unavailable (${screenshot.error ?: "capture_failed"}); using text-only planning.",
        )

        LocalAgentDeviceState.availability(context)
            .takeIf { it != LocalAgentDeviceState.Availability.READY }
            ?.let { availability ->
                bitmap.recycle()
                return Capture.Unavailable("${availability.statusText}; using text-only planning.")
            }

        // Do not use a frame if the foreground package changed during capture. This avoids
        // passing a transition frame from a blacklisted app to any planner.
        val afterPackage = LocalAgentAccessibilityBridge.activeWindowPackageName()
            ?.trim()
            ?.lowercase()
            .orEmpty()
        if (afterPackage != beforePackage || LocalAgentSafetyPolicy.blockedReason(context, afterPackage) != null) {
            bitmap.recycle()
            return Capture.Unavailable("Screen changed during capture; using text-only planning.")
        }

        return withContext(Dispatchers.IO) {
            runCatching { Capture.Available(writeBoundedJpeg(context, bitmap)) }
                .getOrElse {
                    Capture.Unavailable("Screenshot could not be prepared; using text-only planning.")
                }
        }
    }

    fun delete(capture: Capture.Available?) {
        capture?.file?.delete()
    }

    private fun writeBoundedJpeg(context: Context, source: Bitmap): File {
        val maxDimension = maxOf(source.width, source.height).coerceAtLeast(1)
        val scale = (MAX_DIMENSION_PX.toFloat() / maxDimension).coerceAtMost(1f)
        val output = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                source,
                (source.width * scale).toInt().coerceAtLeast(1),
                (source.height * scale).toInt().coerceAtLeast(1),
                true,
            )
        } else {
            source
        }

        var file: File? = null
        try {
            val directory = File(context.cacheDir, CACHE_DIRECTORY)
            check(directory.exists() || directory.mkdirs()) { "Unable to create screenshot cache directory" }
            val outputFile = File.createTempFile("planning_", ".jpg", directory)
            file = outputFile
            val written = FileOutputStream(outputFile).use {
                output.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it)
            }
            check(written && outputFile.length() > 0L) { "Unable to encode screenshot" }
            return outputFile
        } catch (e: Exception) {
            file?.delete()
            throw e
        } finally {
            if (output !== source) output.recycle()
            source.recycle()
        }
    }

    private const val CAPTURE_TIMEOUT_MS = 5_000L
    private const val MAX_DIMENSION_PX = 1_280
    private const val JPEG_QUALITY = 80
    private const val CACHE_DIRECTORY = "local_agent_planning"
}
