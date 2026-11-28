package com.fersaiyan.cyanbridge.shared.ocr

import com.fersaiyan.cyanbridge.shared.vision.ImageResize
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.round

/**
 * PP-OCRv6 model inputs per cyanbridge-npu-models `cpu_gpu/ANY/ocr_spec.json` (reference: the OCR bench's
 * ocrlib.py, PPDet fixed_hw=(256, 512) and PPRec fixed_width=320). Input pixels are ARGB ints, row-major
 * (`Bitmap.getPixels` layout); outputs are NHWC float32 in BGR channel order (channel 0 = blue), as the models
 * were trained on cv2-decoded images. Resizing is [ImageResize.bilinearArgb], bit-exact with cv2.INTER_LINEAR.
 */
object PpOcrPreprocess {
    const val DET_HEIGHT = 256
    const val DET_WIDTH = 512
    const val REC_HEIGHT = 48
    const val REC_WIDTH = 320

    /** ImageNet mean/std, applied in BGR order: index 0 goes with the blue channel (PaddleOCR's convention). */
    private val DET_MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
    private val DET_STD = floatArrayOf(0.229f, 0.224f, 0.225f)

    /**
     * Detector input [1, [height], [width], 3] and the letterbox it used. Detector-map coordinates map back to the
     * source image as `x / ratio`, `y / ratio`.
     */
    class DetInput(val tensor: FloatArray, val ratio: Double, val resizedWidth: Int, val resizedHeight: Int)

    /**
     * Letterbox, top-left anchored: r = min(height / h, width / w); nw = round(w * r), nh = round(h * r) (half to
     * even, as Python's round); bilinear resize pasted at (0, 0) on a black canvas; then (x / 255 - mean) / std per
     * BGR channel over the whole canvas, so the padding is -mean / std.
     */
    fun det(argb: IntArray, srcWidth: Int, srcHeight: Int, height: Int = DET_HEIGHT, width: Int = DET_WIDTH): DetInput {
        require(srcWidth > 0 && srcHeight > 0) { "Empty image $srcWidth x $srcHeight" }
        val r = min(height.toDouble() / srcHeight, width.toDouble() / srcWidth)
        val nh = round(srcHeight * r).toInt().coerceIn(1, height)
        val nw = round(srcWidth * r).toInt().coerceIn(1, width)
        val resized = ImageResize.bilinearArgb(argb, srcWidth, srcHeight, nw, nh)
        val pad = FloatArray(3) { c -> (0f - DET_MEAN[c]) / DET_STD[c] }
        val out = FloatArray(height * width * 3)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val o = (y * width + x) * 3
                if (x < nw && y < nh) {
                    val p = resized[y * nw + x]
                    out[o] = ((p and 0xFF) / 255f - DET_MEAN[0]) / DET_STD[0]
                    out[o + 1] = (((p shr 8) and 0xFF) / 255f - DET_MEAN[1]) / DET_STD[1]
                    out[o + 2] = (((p shr 16) and 0xFF) / 255f - DET_MEAN[2]) / DET_STD[2]
                } else {
                    out[o] = pad[0]; out[o + 1] = pad[1]; out[o + 2] = pad[2]
                }
            }
        }
        return DetInput(out, r, nw, nh)
    }

    /** Resized width of a text line: rw = min([width], ceil([height] * w / h)), at least 1. */
    fun recWidth(srcWidth: Int, srcHeight: Int, height: Int = REC_HEIGHT, width: Int = REC_WIDTH): Int {
        require(srcWidth > 0 && srcHeight > 0) { "Empty image $srcWidth x $srcHeight" }
        val ratio = srcWidth.toDouble() / srcHeight
        return min(width, ceil(height * ratio).toInt()).coerceAtLeast(1)
    }

    /**
     * Recognizer input [1, [height], [width], 3] for a text-line crop: keep-aspect bilinear resize to
     * [recWidth] x [height] at x = 0, (x / 255 - 0.5) / 0.5 in BGR order, columns rw until [width] = 0.0 (mid-grey
     * after normalization). Lines wider than width * h / height are squeezed to [width].
     */
    fun rec(argb: IntArray, srcWidth: Int, srcHeight: Int, height: Int = REC_HEIGHT, width: Int = REC_WIDTH): FloatArray {
        val rw = recWidth(srcWidth, srcHeight, height, width)
        val resized = ImageResize.bilinearArgb(argb, srcWidth, srcHeight, rw, height)
        val out = FloatArray(height * width * 3)
        for (y in 0 until height) {
            for (x in 0 until rw) {
                val p = resized[y * rw + x]
                val o = (y * width + x) * 3
                out[o] = ((p and 0xFF) / 255f - 0.5f) / 0.5f
                out[o + 1] = (((p shr 8) and 0xFF) / 255f - 0.5f) / 0.5f
                out[o + 2] = (((p shr 16) and 0xFF) / 255f - 0.5f) / 0.5f
            }
        }
        return out
    }
}
