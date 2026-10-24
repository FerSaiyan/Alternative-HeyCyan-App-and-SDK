package com.fersaiyan.cyanbridge.plugins.walkingaid.vision

import android.graphics.Bitmap
import android.graphics.RectF

enum class AcceleratorType {
    NPU_COMPILED,
    NPU_ON_DEVICE,
    GPU_DELEGATE,
    XNNPACK_CPU,
}

data class AcceleratorInfo(
    val type: AcceleratorType,
    val delegatedOperators: Int,
    val totalOperators: Int,
    val details: String,
)

data class VisionFrame(
    val bitmap: Bitmap,
    /** Conservative frame timestamp used for freshness checks. */
    val timestampMs: Long = System.currentTimeMillis(),
    val captureCommandAtMs: Long = timestampMs,
    val estimatedExposureAtMs: Long = timestampMs,
    val receivedAtMs: Long = timestampMs,
    val captureIndex: Int = 0,
    val sourcePath: String? = null,
)

enum class HazardMotionState {
    NEW,
    PERSISTENT,
    APPROACHING,
    RECEDING,
    CLEARED,
}

data class DetectedObject(
    val label: String,
    val confidence: Float,
    val boundingBox: RectF, // Normalized coordinates [0..1] (left, top, right, bottom)
    val position: String,   // "left", "center", "right"
    val relativeDepth: Float? = null,
    val approaching: Boolean = false,
    val trackId: Long? = null,
    val motionState: HazardMotionState = HazardMotionState.NEW,
    val timeToCollisionSeconds: Float? = null,
)

data class DetectionResult(
    val objects: List<DetectedObject>,
    val clearedTrackIds: List<Long> = emptyList(),
    val acquisitionTimeMs: Long = 0L,
    val preprocessTimeMs: Long = 0L,
    val inferenceTimeMs: Long = 0L,
    val postprocessTimeMs: Long = 0L,
    val isError: Boolean = false,
    val errorMessage: String? = null,
) {
    val totalTimeMs: Long
        get() = acquisitionTimeMs + preprocessTimeMs + inferenceTimeMs + postprocessTimeMs
}

data class DepthResult(
    val relativeDepthSummary: String,
    val groundDiscontinuityDetected: Boolean,
    val closestRegion: String, // "left", "center", "right"
    val inferenceTimeMs: Long = 0L,
)

interface VisionBackend {
    suspend fun detect(frame: VisionFrame): DetectionResult
    suspend fun estimateDepth(frame: VisionFrame): DepthResult?
    fun acceleratorInfo(): AcceleratorInfo
    fun close()
}
