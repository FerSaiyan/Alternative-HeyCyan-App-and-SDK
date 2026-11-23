package com.fersaiyan.cyanbridge.plugins.walkingaid.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.Log
import com.fersaiyan.cyanbridge.localmodels.engine.NpuArtifacts
import com.fersaiyan.cyanbridge.localmodels.engine.NpuFloatRunner
import org.tensorflow.lite.Interpreter
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** One float-in / float-out network: the NPU build for this phone, or the CPU model. */
interface OcrNet : Closeable {
    val label: String
    fun run(input: FloatArray): FloatArray
}

internal class NpuOcrNet(private val runner: NpuFloatRunner) : OcrNet {
    override val label = "${runner.artifact.vendor?.label} NPU (${runner.artifact.localName})"
    override fun run(input: FloatArray): FloatArray = runner.run(input).single()
    override fun close() = runner.close()
}

class CpuOcrNet(file: File, outputElements: Int, threads: Int = 4) : OcrNet {
    override val label = "CPU (${file.name})"
    private val interpreter = Interpreter(
        FileInputStream(file).channel.use { it.map(FileChannel.MapMode.READ_ONLY, 0, it.size()) },
        Interpreter.Options().setNumThreads(threads),
    )
    private val output = ByteBuffer.allocateDirect(outputElements * 4).order(ByteOrder.nativeOrder())
    private var input: ByteBuffer? = null

    override fun run(input: FloatArray): FloatArray {
        val buffer = this.input?.takeIf { it.capacity() == input.size * 4 }
            ?: ByteBuffer.allocateDirect(input.size * 4).order(ByteOrder.nativeOrder()).also { this.input = it }
        buffer.rewind()
        buffer.asFloatBuffer().put(input)
        output.rewind()
        interpreter.run(buffer, output)
        output.rewind()
        return FloatArray(output.capacity() / 4).also { output.asFloatBuffer().get(it) }
    }

    override fun close() = interpreter.close()
}

/**
 * PP-OCRv6 detector + recognizer on a crop (plate region or bus sign), as in the OCR bench's ocrlib.PPDet / PPRec:
 * - detector: the crop letterboxed top-left into [detHeight] x [detWidth], BGR, ImageNet mean/std on x/255; its
 *   probability map goes through [DbPostprocess];
 * - recognizer: each line resized to height 48 (aspect kept, width at most [recWidth]), BGR, (x/255 - 0.5) / 0.5,
 *   right-padded with 0; greedy CTC over [chars] (index 0 = blank).
 * Not thread-safe.
 */
class PpOcrEngine(
    private val det: OcrNet,
    private val rec: OcrNet,
    private val chars: List<String>,
    private val detHeight: Int = 256,
    private val detWidth: Int = 512,
    private val recWidth: Int = 320,
) : Closeable {
    data class Line(val text: String, val confidence: Float)

    private val plateMask = OcrText.plateClassMask(chars)
    val description: String get() = "det ${det.label}, rec ${rec.label}"

    /** Text lines in reading order. [plate] restricts the recognizer to plate characters. */
    fun read(crop: Bitmap, plate: Boolean = false): List<Line> {
        val (input, scale) = detInput(crop)
        val map = det.run(input)
        val boxes = DbPostprocess.boxes(map, detWidth, detHeight)
        return boxes.mapNotNull { box ->
            val rect = Rect(
                (box.left / scale).toInt().coerceIn(0, crop.width - 1),
                (box.top / scale).toInt().coerceIn(0, crop.height - 1),
                ceil(box.right / scale).toInt().coerceIn(1, crop.width),
                ceil(box.bottom / scale).toInt().coerceIn(1, crop.height),
            )
            if (rect.width() < 4 || rect.height() < 4) return@mapNotNull null
            val line = Bitmap.createBitmap(crop, rect.left, rect.top, rect.width(), rect.height())
            recognize(line, plate).takeIf { it.text.isNotBlank() }
        }
    }

    /**
     * Plate reading as in the bench's crop_det_read: the plate crop scaled to 128 px high with a 16 px replicated
     * border, then detector + recognizer (splits two-row motorcycle plates and the BRASIL band), lines joined.
     */
    fun readPlate(plateCrop: Bitmap): String {
        val scaled = Bitmap.createScaledBitmap(
            plateCrop, max(1, (plateCrop.width * 128f / plateCrop.height).roundToInt()), 128, true,
        )
        val bordered = Bitmap.createBitmap(scaled.width + 32, scaled.height + 32, Bitmap.Config.ARGB_8888)
        Canvas(bordered).apply {
            drawColor(scaled.getPixel(0, 0))
            drawBitmap(scaled, 16f, 16f, Paint(Paint.FILTER_BITMAP_FLAG))
        }
        return read(bordered, plate = true).joinToString("") { it.text }
    }

    fun recognize(line: Bitmap, plate: Boolean): Line {
        val ratio = line.width.toFloat() / max(1, line.height)
        val w = min(recWidth, max(1, ceil(48 * ratio).toInt()))
        val resized = Bitmap.createScaledBitmap(line, w, 48, true)
        val pixels = IntArray(w * 48)
        resized.getPixels(pixels, 0, w, 0, 0, w, 48)
        val input = FloatArray(48 * recWidth * 3) // zero = padding, after normalization
        for (y in 0 until 48) for (x in 0 until w) {
            val p = pixels[y * w + x]
            val o = (y * recWidth + x) * 3
            input[o] = Color.blue(p) / 127.5f - 1f
            input[o + 1] = Color.green(p) / 127.5f - 1f
            input[o + 2] = Color.red(p) / 127.5f - 1f
        }
        val probs = rec.run(input)
        val classes = probs.size / RECOGNIZER_STEPS
        val (text, conf) = OcrText.ctcGreedy(probs, classes, chars, if (plate) plateMask else null)
        return Line(text, conf)
    }

    private fun detInput(crop: Bitmap): Pair<FloatArray, Float> {
        val r = min(detHeight.toFloat() / crop.height, detWidth.toFloat() / crop.width)
        val nw = max(1, (crop.width * r).roundToInt())
        val nh = max(1, (crop.height * r).roundToInt())
        val resized = Bitmap.createScaledBitmap(crop, nw, nh, true)
        val pixels = IntArray(nw * nh)
        resized.getPixels(pixels, 0, nw, 0, 0, nw, nh)
        // Letterbox fill is black (0) before normalization, as in ocrlib.
        val input = FloatArray(detHeight * detWidth * 3)
        val fill = floatArrayOf((0f - MEAN[0]) / STD[0], (0f - MEAN[1]) / STD[1], (0f - MEAN[2]) / STD[2])
        for (i in 0 until detHeight * detWidth) for (c in 0..2) input[i * 3 + c] = fill[c]
        for (y in 0 until nh) for (x in 0 until nw) {
            val p = pixels[y * nw + x]
            val o = (y * detWidth + x) * 3
            input[o] = (Color.blue(p) / 255f - MEAN[0]) / STD[0]
            input[o + 1] = (Color.green(p) / 255f - MEAN[1]) / STD[1]
            input[o + 2] = (Color.red(p) / 255f - MEAN[2]) / STD[2]
        }
        return input to r
    }

    override fun close() {
        det.close()
        rec.close()
    }

    companion object {
        private const val TAG = "PpOcrEngine"
        /** Recognizer output steps for a 48 x 320 input. */
        const val RECOGNIZER_STEPS = 40
        // ImageNet mean/std, applied to the B, G, R channels in that order (PaddleOCR on cv2 BGR images).
        private val MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
        private val STD = floatArrayOf(0.229f, 0.224f, 0.225f)

        /**
         * Dictionary file (one character per line) to class list: index 0 = CTC blank, then the file's characters,
         * plus a trailing space class when the recognizer has one more class than that (ocrlib.PPRec).
         */
        fun loadChars(dictionary: File, recognizerClasses: Int): List<String> {
            val chars = mutableListOf("<blank>")
            dictionary.forEachLine(Charsets.UTF_8) { line -> chars += line.trimEnd('\r', '\n') }
            if (recognizerClasses == chars.size + 1) chars += " "
            if (recognizerClasses != chars.size) Log.w(TAG, "Dictionary has ${chars.size} classes, recognizer $recognizerClasses")
            return chars
        }

        /** NPU net for [artifact] when its file is installed and loads, else null. */
        internal fun npuNet(context: Context, artifact: NpuArtifacts.Artifact?, file: File?): OcrNet? {
            if (artifact == null || file == null || !file.isFile) return null
            return runCatching { NpuOcrNet(NpuFloatRunner.open(context, artifact, file.absolutePath)) }
                .onFailure { Log.w(TAG, "NPU ${artifact.localName} failed to load; using the CPU model", it) }
                .getOrNull()
        }
    }
}
