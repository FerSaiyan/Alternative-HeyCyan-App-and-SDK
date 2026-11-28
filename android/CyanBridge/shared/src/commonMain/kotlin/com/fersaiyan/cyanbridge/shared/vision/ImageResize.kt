package com.fersaiyan.cyanbridge.shared.vision

import kotlin.math.floor
import kotlin.math.round

/**
 * Image resampling shared by the Android and iOS model inputs. Pixels are ARGB ints, row-major, as
 * `Bitmap.getPixels` returns them (iOS builds the same layout from a CGImage); alpha is ignored.
 */
object ImageResize {
    /**
     * Bilinear resize with half-pixel centres, bit-exact with OpenCV 4.x `cv2.resize(..., INTER_LINEAR)` on 8-bit
     * images (checked against cv2 4.10 on random sizes, up and down, including exact 2x; ImageResizeTest holds cv2
     * outputs). It follows OpenCV's fixed-point path: 11-bit coefficients, an exact integer horizontal pass, then
     * the vertical pass as cv2's SIMD code rounds it, `((S0 >> 4) * b0 >> 16) + ((S1 >> 4) * b1 >> 16) + 2 >> 2`.
     * Columns are clamped by zeroing the weight; rows outside the image reuse the edge row with both weights, as
     * OpenCV does. The result is opaque ARGB.
     */
    fun bilinearArgb(src: IntArray, srcWidth: Int, srcHeight: Int, dstWidth: Int, dstHeight: Int): IntArray {
        require(srcWidth > 0 && srcHeight > 0 && dstWidth > 0 && dstHeight > 0) {
            "Empty image: $srcWidth x $srcHeight -> $dstWidth x $dstHeight"
        }
        require(src.size >= srcWidth * srcHeight) { "Pixels ${src.size} < $srcWidth x $srcHeight" }
        val xs = axis(srcWidth, dstWidth, clampWeights = true)
        val ys = axis(srcHeight, dstHeight, clampWeights = false)
        // Horizontal pass per source row (computed once per row, on demand), 3 channels: R, G, B.
        val rows = arrayOfNulls<IntArray>(srcHeight)
        fun row(y: Int): IntArray = rows[y] ?: IntArray(dstWidth * 3).also { out ->
            val base = y * srcWidth
            for (x in 0 until dstWidth) {
                val p0 = src[base + xs.index[x]]
                val p1 = src[base + minOf(xs.index[x] + 1, srcWidth - 1)]
                val a0 = xs.w0[x]
                val a1 = xs.w1[x]
                out[x * 3] = ((p0 shr 16) and 0xFF) * a0 + ((p1 shr 16) and 0xFF) * a1
                out[x * 3 + 1] = ((p0 shr 8) and 0xFF) * a0 + ((p1 shr 8) and 0xFF) * a1
                out[x * 3 + 2] = (p0 and 0xFF) * a0 + (p1 and 0xFF) * a1
            }
            rows[y] = out
        }
        val dst = IntArray(dstWidth * dstHeight)
        val channel = IntArray(3)
        for (y in 0 until dstHeight) {
            val s0 = row(ys.index[y].coerceIn(0, srcHeight - 1))
            val s1 = row((ys.index[y] + 1).coerceIn(0, srcHeight - 1))
            val b0 = ys.w0[y]
            val b1 = ys.w1[y]
            for (x in 0 until dstWidth) {
                for (c in 0..2) {
                    val i = x * 3 + c
                    val v = (((s0[i] shr 4) * b0) shr 16) + (((s1[i] shr 4) * b1) shr 16)
                    channel[c] = ((v + 2) shr 2).coerceIn(0, 255)
                }
                dst[y * dstWidth + x] = (0xFF shl 24) or (channel[0] shl 16) or (channel[1] shl 8) or channel[2]
            }
        }
        return dst
    }

    private class Axis(val index: IntArray, val w0: IntArray, val w1: IntArray)

    /** OpenCV's per-axis source index and 11-bit weights (float maths as in cv::resize, rounding half to even). */
    private fun axis(src: Int, dst: Int, clampWeights: Boolean): Axis {
        val scale = 1.0 / (dst.toDouble() / src)
        val index = IntArray(dst)
        val w0 = IntArray(dst)
        val w1 = IntArray(dst)
        for (d in 0 until dst) {
            var f = ((d + 0.5) * scale - 0.5).toFloat()
            var s = floor(f).toInt()
            f -= s
            if (clampWeights) {
                if (s < 0) { f = 0f; s = 0 }
                if (s >= src - 1) { f = 0f; s = src - 1 }
            }
            index[d] = s
            w0[d] = round((1f - f) * COEF_SCALE).toInt()
            w1[d] = round(f * COEF_SCALE).toInt()
        }
        return Axis(index, w0, w1)
    }

    private const val COEF_SCALE = 2048f
}
