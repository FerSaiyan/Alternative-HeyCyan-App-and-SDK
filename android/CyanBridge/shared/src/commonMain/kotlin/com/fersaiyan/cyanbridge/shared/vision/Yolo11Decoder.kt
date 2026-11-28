package com.fersaiyan.cyanbridge.shared.vision

import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

/** One detected object; the box is in model-input pixels unless mapped with [YoloLetterbox.toSource]. */
data class Detection(
    val classId: Int,
    val score: Float,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

/**
 * Decoder for the CyanBridge YOLO11n graphs with the argmax left to the app: the GPU/CPU sources
 * `yolo11n_640_fp32_v4noargmax.tflite` / `yolo11n_640_v4noargmax_fp16w.tflite` and the per-SoC NPU builds of
 * cyanbridge-npu-models. Input float32 [1, 640, 640, 3], RGB / 255 ([YoloLetterbox.input]). Outputs:
 * - `Identity` [1, 8400, 4]: corner boxes (x1, y1, x2, y2) in input pixels, already decoded (DFL + anchors in graph);
 * - `Identity_1` [1, 8400]: each anchor's best class probability;
 * - `model_76/tf.math.sigmoid_77/Sigmoid` [1, 8400, 80]: per-class sigmoid probabilities (COCO order).
 *
 * Same semantics as the Android detector (LiteRtVisionBackend.decodeCatalogNpuOutputs, detect and
 * nonMaxSuppression on branch qwen-litert): class = first argmax of the class probabilities, score = `Identity_1`,
 * keep score >= threshold, boxes clamped to the input, then class-aware greedy NMS by descending score (stable)
 * that drops boxes with IoU >= the threshold.
 */
object Yolo11Decoder {
    const val ANCHORS = 8400
    const val CLASSES = 80
    const val INPUT_SIZE = 640
    /** Android's walking-aid defaults (LiteRtVisionBackend CONFIDENCE_THRESHOLD / IOU_THRESHOLD). */
    const val DEFAULT_SCORE_THRESHOLD = 0.35f
    const val DEFAULT_IOU_THRESHOLD = 0.45f

    /**
     * [boxes] = `Identity` (4 per anchor), [classProbs] = the sigmoid output (classes per anchor), [maxScores] =
     * `Identity_1` (null: the max of [classProbs], which is what the graph outputs there).
     */
    fun decode(
        boxes: FloatArray,
        classProbs: FloatArray,
        maxScores: FloatArray? = null,
        inputWidth: Int = INPUT_SIZE,
        inputHeight: Int = INPUT_SIZE,
        scoreThreshold: Float = DEFAULT_SCORE_THRESHOLD,
        iouThreshold: Float = DEFAULT_IOU_THRESHOLD,
        maxDetections: Int = 300,
    ): List<Detection> {
        require(boxes.size % 4 == 0) { "Boxes size ${boxes.size} is not N x 4" }
        val n = boxes.size / 4
        require(n > 0 && classProbs.size % n == 0) { "Class probabilities ${classProbs.size} do not fit $n anchors" }
        require(maxScores == null || maxScores.size == n) { "Scores ${maxScores?.size} != $n anchors" }
        val classes = classProbs.size / n
        val candidates = ArrayList<Detection>()
        for (i in 0 until n) {
            val base = i * classes
            var best = 0
            var bestProb = classProbs[base]
            for (c in 1 until classes) {
                val v = classProbs[base + c]
                if (v > bestProb) { bestProb = v; best = c }
            }
            val score = maxScores?.get(i) ?: bestProb
            if (score >= scoreThreshold) candidates += box(boxes, i, best, score, inputWidth, inputHeight)
        }
        return nms(candidates, iouThreshold, maxDetections)
    }

    /**
     * The same from a model's outputs in any order, told apart by size like the Android decoder: boxes [N, 4],
     * class probabilities [N, C] with C > 1, and a per-anchor [N] tensor (the max score). Builds that output the
     * class index and score as two [N] tensors instead are accepted too (the index tensor is the one above 1.5).
     */
    fun decodeOutputs(
        outputs: List<FloatArray>,
        inputWidth: Int = INPUT_SIZE,
        inputHeight: Int = INPUT_SIZE,
        scoreThreshold: Float = DEFAULT_SCORE_THRESHOLD,
        iouThreshold: Float = DEFAULT_IOU_THRESHOLD,
        maxDetections: Int = 300,
    ): List<Detection> {
        val boxes = outputs.firstOrNull { it.size % 4 == 0 && outputs.any { o -> o.size * 4 == it.size } }
            ?: error("No [1, N, 4] boxes among outputs ${outputs.map { it.size }}")
        val n = boxes.size / 4
        val perAnchor = outputs.filter { it !== boxes && it.size == n }
        val probs = outputs.firstOrNull { it !== boxes && it.size > n && it.size % n == 0 }
        if (probs != null) {
            return decode(boxes, probs, perAnchor.firstOrNull(), inputWidth, inputHeight, scoreThreshold, iouThreshold, maxDetections)
        }
        require(perAnchor.size == 2) { "Expected class and score outputs, got ${outputs.map { it.size }}" }
        val (a, b) = perAnchor
        val (classIds, scores) = if ((a.maxOrNull() ?: 0f) > 1.5f) a to b else b to a
        val candidates = ArrayList<Detection>()
        for (i in 0 until n) {
            if (scores[i] >= scoreThreshold) {
                candidates += box(boxes, i, round(classIds[i]).toInt(), scores[i], inputWidth, inputHeight)
            }
        }
        return nms(candidates, iouThreshold, maxDetections)
    }

    /** Class-aware greedy NMS: highest score first (stable for ties), drops same-class boxes with IoU >= [iouThreshold]. */
    fun nms(detections: List<Detection>, iouThreshold: Float, maxDetections: Int = Int.MAX_VALUE): List<Detection> {
        val sorted = detections.sortedByDescending { it.score }
        val removed = BooleanArray(sorted.size)
        val kept = ArrayList<Detection>()
        for (i in sorted.indices) {
            if (removed[i]) continue
            val best = sorted[i]
            kept += best
            if (kept.size >= maxDetections) break
            for (j in i + 1 until sorted.size) {
                if (!removed[j] && sorted[j].classId == best.classId && iou(best, sorted[j]) >= iouThreshold) removed[j] = true
            }
        }
        return kept
    }

    fun iou(a: Detection, b: Detection): Float {
        val left = max(a.left, b.left)
        val top = max(a.top, b.top)
        val right = min(a.right, b.right)
        val bottom = min(a.bottom, b.bottom)
        if (right <= left || bottom <= top) return 0f
        val inter = (right - left) * (bottom - top)
        return inter / (a.width * a.height + b.width * b.height - inter)
    }

    private fun box(boxes: FloatArray, i: Int, classId: Int, score: Float, width: Int, height: Int) = Detection(
        classId = classId,
        score = score,
        left = boxes[i * 4].coerceIn(0f, width.toFloat()),
        top = boxes[i * 4 + 1].coerceIn(0f, height.toFloat()),
        right = boxes[i * 4 + 2].coerceIn(0f, width.toFloat()),
        bottom = boxes[i * 4 + 3].coerceIn(0f, height.toFloat()),
    )

    /** COCO class names in the model's class order. */
    val COCO_CLASSES: List<String> = listOf(
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

/**
 * Letterbox geometry for a [srcWidth] x [srcHeight] image fitted into the [dstWidth] x [dstHeight] model input:
 * scaled by [scale] to [scaledWidth] x [scaledHeight] (rounded half to even, as Python's round in Ultralytics'
 * LetterBox) and placed at ([padLeft], [padTop]) (centred by default, the odd pixel going right/bottom).
 */
data class YoloLetterbox(
    val srcWidth: Int,
    val srcHeight: Int,
    val dstWidth: Int,
    val dstHeight: Int,
    val scale: Float,
    val scaledWidth: Int,
    val scaledHeight: Int,
    val padLeft: Int,
    val padTop: Int,
) {
    /** A box in model-input pixels mapped back to the source image, clamped to it. */
    fun toSource(d: Detection): Detection = d.copy(
        left = sourceX(d.left),
        top = sourceY(d.top),
        right = sourceX(d.right),
        bottom = sourceY(d.bottom),
    )

    fun sourceX(x: Float): Float = ((x - padLeft) / scale).coerceIn(0f, srcWidth.toFloat())
    fun sourceY(y: Float): Float = ((y - padTop) / scale).coerceIn(0f, srcHeight.toFloat())

    /**
     * The model input from ARGB pixels: bilinear resize ([ImageResize.bilinearArgb], cv2 INTER_LINEAR) into a
     * [padValue]-grey canvas (114, as Ultralytics), RGB / 255, NHWC float32.
     */
    fun input(argb: IntArray, padValue: Int = 114): FloatArray {
        require(argb.size >= srcWidth * srcHeight) { "Pixels ${argb.size} < $srcWidth x $srcHeight" }
        val resized = ImageResize.bilinearArgb(argb, srcWidth, srcHeight, scaledWidth, scaledHeight)
        val pad = padValue / 255f
        val out = FloatArray(dstWidth * dstHeight * 3) { pad }
        for (y in 0 until scaledHeight) {
            for (x in 0 until scaledWidth) {
                val p = resized[y * scaledWidth + x]
                val o = ((y + padTop) * dstWidth + x + padLeft) * 3
                out[o] = ((p shr 16) and 0xFF) / 255f
                out[o + 1] = ((p shr 8) and 0xFF) / 255f
                out[o + 2] = (p and 0xFF) / 255f
            }
        }
        return out
    }

    companion object {
        fun of(
            srcWidth: Int,
            srcHeight: Int,
            dstWidth: Int = Yolo11Decoder.INPUT_SIZE,
            dstHeight: Int = Yolo11Decoder.INPUT_SIZE,
            centered: Boolean = true,
        ): YoloLetterbox {
            require(srcWidth > 0 && srcHeight > 0 && dstWidth > 0 && dstHeight > 0) { "Empty size" }
            val r = min(dstWidth.toDouble() / srcWidth, dstHeight.toDouble() / srcHeight)
            val w = round(srcWidth * r).toInt().coerceIn(1, dstWidth)
            val h = round(srcHeight * r).toInt().coerceIn(1, dstHeight)
            return YoloLetterbox(
                srcWidth, srcHeight, dstWidth, dstHeight,
                scale = r.toFloat(),
                scaledWidth = w,
                scaledHeight = h,
                padLeft = if (centered) (dstWidth - w) / 2 else 0,
                padTop = if (centered) (dstHeight - h) / 2 else 0,
            )
        }
    }
}
