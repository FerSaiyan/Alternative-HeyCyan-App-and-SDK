package com.fersaiyan.cyanbridge.plugins.walkingaid

import android.content.Context
import com.fersaiyan.cyanbridge.agent.ProSubscriptionPrefs
import java.io.File

typealias WalkingAidReadinessResult = com.fersaiyan.cyanbridge.shared.plugins.walkingaid.WalkingAidReadinessResult

object WalkingAidReadinessChecker {

    fun isValidModelFile(file: File?): Boolean {
        return file != null && file.exists() && file.length() >= 1024L
    }

    fun checkReadiness(context: Context): WalkingAidReadinessResult {
        val details = mutableListOf<String>()
        val isProActive = ProSubscriptionPrefs.isActiveLocally(context)
        val exploreMode = WalkingAidPreferences.getMode(context) == WalkingAidMode.EXPLORE
        var requiresPro = false

        // 1. Local object detection is always the non-blocking safety path. Cloud vision, when
        // selected, only enriches scene history and therefore does not replace the local model.
        val yoloType = WalkingAidPreferences.getYoloModelType(context)
        val candidates = if (yoloType == WalkingAidPreferences.MODEL_TYPE_YOLO_WORLD) {
            listOf(
                File(context.filesDir, "yolo_world.tflite"),
                File(context.filesDir, "yolo_world_v2_s.tflite"),
                File(context.filesDir, "yoloworld.tflite"),
                File(context.getExternalFilesDir(null), "yolo_world.tflite"),
                File(context.getExternalFilesDir(null), "yolo_world_v2_s.tflite"),
                File(context.getExternalFilesDir(null), "yoloworld.tflite")
            )
        } else {
            listOf(
                File(context.filesDir, "yolo11n_float16.tflite"),
                File(context.filesDir, "yolov11n.tflite"),
                File(context.getExternalFilesDir(null), "yolo11n_float16.tflite"),
                File(context.getExternalFilesDir(null), "yolov11n.tflite")
            )
        }
        val localYoloReady = candidates.any(::isValidModelFile)
        if (!localYoloReady) {
            val expected = if (yoloType == WalkingAidPreferences.MODEL_TYPE_YOLO_WORLD) "yolo_world.tflite" else "yolo11n_float16.tflite"
            if (candidates.any { it.exists() }) {
                details.add("❌ Walking Aid YOLO model is corrupted or incomplete ($expected). Download it in this screen.")
            } else {
                details.add("❌ Walking Aid YOLO model is missing ($expected). Download it in this screen.")
            }
        }
        val cloudDescriptionSelected = !exploreMode && WalkingAidPreferences.getImageDescriptionSource(context) == "cloud"
        val cloudDescriptionReady = !cloudDescriptionSelected || isProActive
        if (!cloudDescriptionReady) {
            requiresPro = true
            details.add("🔒 Optional cloud scene descriptions require active Pro Subscription")
        }
        val yoloReady = localYoloReady && cloudDescriptionReady

        // 2. Relative Depth Readiness Check
        val depthEnabled = !exploreMode && WalkingAidPreferences.isDepthEnabled(context)
        val depthSource = WalkingAidPreferences.getDepthSource(context)
        val depthReady = if (!depthEnabled) {
            true
        } else if (depthSource == "cloud") {
            if (!isProActive) {
                requiresPro = true
                details.add("🔒 Cloud Relative Depth requires active Pro Subscription")
                false
            } else {
                true
            }
        } else {
            val file = File(context.filesDir, "depth_anything_v2_small.tflite")
            val extFile = File(context.getExternalFilesDir(null), "depth_anything_v2_small.tflite")
            val depthAnything3 = File(context.filesDir, "depth_anything_3_small.tflite")
            val externalDepthAnything3 = File(context.getExternalFilesDir(null), "depth_anything_3_small.tflite")
            val hasDepth = isValidModelFile(file) || isValidModelFile(extFile) ||
                isValidModelFile(depthAnything3) || isValidModelFile(externalDepthAnything3)
            if (!hasDepth) {
                if (file.exists() || extFile.exists() || depthAnything3.exists() || externalDepthAnything3.exists()) {
                    details.add("❌ Walking Aid depth model is corrupted or incomplete (depth_anything_3_small.tflite). Download it in this screen.")
                } else {
                    details.add("❌ Walking Aid depth model is missing (depth_anything_3_small.tflite). Download it in this screen.")
                }
            }
            hasDepth
        }

        // Automatic warnings no longer depend on an LLM. Keep this compatibility field true so
        // stale state-model preferences cannot block or misrepresent Walking Aid readiness.
        val llmReady = true
        val allReady = yoloReady && depthReady
        return WalkingAidReadinessResult(
            isReady = allReady,
            yoloReady = yoloReady,
            depthReady = depthReady,
            llmReady = llmReady,
            requiresProForCloud = requiresPro,
            missingDetails = details,
        )
    }
}
