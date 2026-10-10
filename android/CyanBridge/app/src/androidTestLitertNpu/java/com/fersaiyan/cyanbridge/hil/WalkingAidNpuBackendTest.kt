package com.fersaiyan.cyanbridge.hil

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fersaiyan.cyanbridge.localmodels.engine.LiteRtNpuSupport
import com.fersaiyan.cyanbridge.localmodels.settings.LocalComputeBackend
import com.fersaiyan.cyanbridge.plugins.walkingaid.WalkingAidPreferences
import com.fersaiyan.cyanbridge.plugins.walkingaid.vision.AcceleratorType
import com.fersaiyan.cyanbridge.plugins.walkingaid.vision.DetectedObject
import com.fersaiyan.cyanbridge.plugins.walkingaid.vision.LiteRtVisionBackend
import com.fersaiyan.cyanbridge.plugins.walkingaid.vision.VisionFrame
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * End-to-end Walking Aid YOLO11n on the Qualcomm NPU through LiteRtVisionBackend itself
 * (NPU_EXPERIMENTAL backend, `<detector>_Qualcomm_<SoC>.tflite` next to the plain model).
 * Detections on the WalkingAidNpuDetectorTest fixture must match the host QNN HTP emulation,
 * and the CPU backend's must match host LiteRT CPU (labels included).
 *
 * Model files are copied into the app's files directory only when absent, and only the files
 * this test created are deleted afterwards. Walking Aid settings are not changed: the test
 * skips when YOLO-World is the selected detector.
 *
 * Arguments: -e yolo_fixture, -e yolo_plain_model (yolo11n.tflite), -e yolo_npu_model (AOT).
 */
@RunWith(AndroidJUnit4::class)
class WalkingAidNpuBackendTest {
    private val args = InstrumentationRegistry.getArguments()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun yolo11n_backendRunsOnNpu_andMatchesEmulation() = runBlocking {
        val fixtureDir = args.getString("yolo_fixture").orEmpty()
        val plain = args.getString("yolo_plain_model").orEmpty()
        val aot = args.getString("yolo_npu_model").orEmpty()
        assumeTrue("pass -e yolo_fixture, -e yolo_plain_model and -e yolo_npu_model",
            fixtureDir.isNotBlank() && plain.isNotBlank() && aot.isNotBlank())
        val npu = LiteRtNpuSupport.status(context)
        assumeTrue(npu.reason, npu.available)
        assumeTrue("YOLO-World is the selected detector; not changing Walking Aid settings",
            WalkingAidPreferences.getYoloModelType(context) == WalkingAidPreferences.MODEL_TYPE_YOLO11)

        val created = mutableListOf<File>()
        fun install(source: String, name: String) {
            val target = File(context.filesDir, name)
            if (!target.exists()) {
                File(source).copyTo(target)
                created += target
            }
        }
        val npuBackend: LiteRtVisionBackend
        val cpuBackend: LiteRtVisionBackend
        try {
            install(plain, "yolo11n_float16.tflite")
            install(aot, "yolo11n_float16_Qualcomm_${npu.socModel}.tflite")
            npuBackend = LiteRtVisionBackend(context, LocalComputeBackend.NPU_EXPERIMENTAL)
            cpuBackend = LiteRtVisionBackend(context, LocalComputeBackend.CPU)
        } catch (error: Throwable) {
            created.forEach { it.delete() }
            throw error
        }
        try {
            val info = npuBackend.acceleratorInfo()
            println("YOLO_BACKEND npu accelerator=${info.type} details=${info.details}")
            assertEquals(AcceleratorType.NPU_ON_DEVICE, info.type)
            assertTrue(info.details, info.details.contains("CompiledModel"))

            val manifest = JSONObject(File(fixtureDir, "manifest.json").readText())
            val images = manifest.getJSONArray("images")
            val npuMatch = IntArray(3) // expected, actual, matched
            val cpuMatch = IntArray(3)
            val npuMs = mutableListOf<Long>()
            val cpuMs = mutableListOf<Long>()
            for (i in 0 until images.length()) {
                val row = images.getJSONObject(i)
                val bitmap = rgbBitmap(File(fixtureDir, row.getString("file")).readBytes())
                val frame = VisionFrame(bitmap = bitmap)
                val npuResult = npuBackend.detect(frame)
                val cpuResult = cpuBackend.detect(frame)
                assertFalse("NPU detector error: ${npuResult.errorMessage}", npuResult.isError)
                assertFalse("CPU detector error: ${cpuResult.errorMessage}", cpuResult.isError)
                npuMs += npuResult.inferenceTimeMs
                cpuMs += cpuResult.inferenceTimeMs
                count(npuResult.objects, row.getJSONArray("htp"), npuMatch)
                count(cpuResult.objects, row.getJSONArray("cpu"), cpuMatch)
                bitmap.recycle()
            }
            fun rates(m: IntArray) = m[2].toDouble() / m[0].coerceAtLeast(1) to m[2].toDouble() / m[1].coerceAtLeast(1)
            val (recall, precision) = rates(npuMatch)
            val (cpuRecall, cpuPrecision) = rates(cpuMatch)
            println("YOLO_BACKEND summary npu vsEmulated recall=$recall precision=$precision; cpu vsHostCpu recall=$cpuRecall " +
                "precision=$cpuPrecision; inferenceMs median npu=${npuMs.sorted()[npuMs.size / 2]} cpu=${cpuMs.sorted()[cpuMs.size / 2]}")
            assertTrue("backend NPU detections differ from HTP emulation (recall $recall, precision $precision)",
                recall >= 0.9 && precision >= 0.9)
            assertTrue("backend CPU detections differ from host CPU (recall $cpuRecall, precision $cpuPrecision)",
                cpuRecall >= 0.9 && cpuPrecision >= 0.9)
        } finally {
            npuBackend.close()
            cpuBackend.close()
            created.forEach { it.delete() }
        }
    }

    /** Adds expected, actual and matched (same label name, IoU >= 0.5) counts to [totals]. */
    private fun count(objects: List<DetectedObject>, expected: JSONArray, totals: IntArray) {
        val used = mutableSetOf<Int>()
        fun box(i: Int) = objects[i].boundingBox.let { r -> floatArrayOf(r.left, r.top, r.right, r.bottom) }
        for (k in 0 until expected.length()) {
            val e = expected.getJSONObject(k)
            val target = e.getJSONArray("box").let { b -> FloatArray(4) { b.getDouble(it).toFloat() } }
            val label = COCO_CLASSES[e.getInt("label")]
            val j = objects.indices.filter { it !in used && objects[it].label == label }.maxByOrNull { iou(box(it), target) }
            if (j != null && iou(box(j), target) >= 0.5f) {
                used += j
                totals[2]++
            }
        }
        totals[0] += expected.length()
        totals[1] += objects.size
    }

    private fun rgbBitmap(rgb: ByteArray): Bitmap {
        val pixels = IntArray(640 * 640) { i ->
            val r = rgb[3 * i].toInt() and 0xFF
            val g = rgb[3 * i + 1].toInt() and 0xFF
            val b = rgb[3 * i + 2].toInt() and 0xFF
            (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        return Bitmap.createBitmap(pixels, 640, 640, Bitmap.Config.ARGB_8888)
    }

    private fun iou(a: FloatArray, b: FloatArray): Float {
        val w = max(0f, min(a[2], b[2]) - max(a[0], b[0]))
        val h = max(0f, min(a[3], b[3]) - max(a[1], b[1]))
        val inter = w * h
        val union = (a[2] - a[0]) * (a[3] - a[1]) + (b[2] - b[0]) * (b[3] - b[1]) - inter
        return if (union > 0f) inter / union else 0f
    }

    private companion object {
        val COCO_CLASSES = arrayOf(
            "person", "bicycle", "car", "motorcycle", "airplane", "bus", "train", "truck",
            "boat", "traffic light", "fire hydrant", "stop sign", "parking meter", "bench",
            "bird", "cat", "dog", "horse", "sheep", "cow", "elephant", "bear", "zebra",
            "giraffe", "backpack", "umbrella", "handbag", "tie", "suitcase", "frisbee",
            "skis", "snowboard", "sports ball", "kite", "baseball bat", "baseball glove",
            "skateboard", "surfboard", "tennis racket", "bottle", "wine glass", "cup",
            "fork", "knife", "spoon", "bowl", "banana", "apple", "sandwich", "orange",
            "broccoli", "carrot", "hot dog", "pizza", "donut", "cake", "chair", "couch",
            "potted plant", "bed", "dining table", "toilet", "tv", "laptop", "mouse",
            "remote", "keyboard", "cell phone", "microwave", "oven", "toaster", "sink",
            "refrigerator", "book", "clock", "vase", "scissors", "teddy bear", "hair drier",
            "toothbrush",
        )
    }
}
