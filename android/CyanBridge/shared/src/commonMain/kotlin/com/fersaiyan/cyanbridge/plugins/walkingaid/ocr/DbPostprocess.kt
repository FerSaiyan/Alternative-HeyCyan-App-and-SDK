package com.fersaiyan.cyanbridge.plugins.walkingaid.ocr

import kotlin.math.max
import kotlin.math.min

/**
 * PP-OCR DB detector post-processing on a probability map (H x W, row-major): threshold, connected regions, mean
 * probability inside each region (box score), then "unclip" growth by area * ratio / perimeter. The bench uses
 * OpenCV's rotated minAreaRect; plates and bus signs in a crop of a car or bus are close to horizontal, so this uses
 * the region's axis-aligned bounds, which keeps it free of OpenCV.
 */
object DbPostprocess {
    data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float, val score: Float)

    fun boxes(
        map: FloatArray,
        width: Int,
        height: Int,
        // PP-OCRv6 small det inference.yml PostProcess values.
        thresh: Float = 0.2f,
        boxThresh: Float = 0.45f,
        unclipRatio: Float = 1.4f,
        minSide: Int = 3,
        maxBoxes: Int = 50,
    ): List<Box> {
        val label = IntArray(width * height)
        val out = mutableListOf<Box>()
        val queue = IntArray(width * height)
        var next = 0
        for (start in 0 until width * height) {
            if (label[start] != 0 || map[start] <= thresh) continue
            next++
            var head = 0
            var tail = 0
            queue[tail++] = start
            label[start] = next
            var x0 = width; var y0 = height; var x1 = -1; var y1 = -1
            var sum = 0f
            var count = 0
            while (head < tail) {
                val p = queue[head++]
                val x = p % width
                val y = p / width
                x0 = min(x0, x); x1 = max(x1, x); y0 = min(y0, y); y1 = max(y1, y)
                sum += map[p]
                count++
                if (x > 0) tail = visit(p - 1, map, label, queue, tail, thresh, next)
                if (x < width - 1) tail = visit(p + 1, map, label, queue, tail, thresh, next)
                if (y > 0) tail = visit(p - width, map, label, queue, tail, thresh, next)
                if (y < height - 1) tail = visit(p + width, map, label, queue, tail, thresh, next)
            }
            val w = (x1 - x0 + 1).toFloat()
            val h = (y1 - y0 + 1).toFloat()
            if (min(w, h) < minSide) continue
            val score = sum / count
            if (score < boxThresh) continue
            val d = w * h * unclipRatio / (2f * (w + h))
            out += Box(
                left = (x0 - d).coerceAtLeast(0f),
                top = (y0 - d).coerceAtLeast(0f),
                right = (x1 + 1 + d).coerceAtMost(width.toFloat()),
                bottom = (y1 + 1 + d).coerceAtMost(height.toFloat()),
                score = score,
            )
            if (out.size >= maxBoxes) break
        }
        // Reading order: rows top to bottom (by centre, a third of the box height as row tolerance), then left to right.
        val row = max(1f, rowHeight(out))
        return out.sortedWith(compareBy({ ((it.top + it.bottom) / 2f / row).toInt() }, { it.left }))
    }

    private fun rowHeight(boxes: List<Box>): Float =
        boxes.map { it.bottom - it.top }.sorted().let { if (it.isEmpty()) 1f else it[it.size / 2] * 0.66f }

    private fun visit(p: Int, map: FloatArray, label: IntArray, queue: IntArray, tail: Int, thresh: Float, id: Int): Int {
        if (label[p] != 0 || map[p] <= thresh) return tail
        label[p] = id
        queue[tail] = p
        return tail + 1
    }
}
