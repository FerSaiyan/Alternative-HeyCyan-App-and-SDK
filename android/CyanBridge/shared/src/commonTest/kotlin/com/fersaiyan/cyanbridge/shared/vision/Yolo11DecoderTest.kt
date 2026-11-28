package com.fersaiyan.cyanbridge.shared.vision

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Yolo11DecoderTest {
    private fun numbers(s: String): List<String> = if (s.isBlank()) emptyList() else s.trim().split(' ')

    /** Full-size outputs rebuilt from the sparse golden fixture (everything else is 0). */
    private class Outputs(val boxes: FloatArray, val scores: FloatArray, val probs: FloatArray)

    private fun outputs(frame: Yolo11GoldenFixture.Frame): Outputs {
        val boxes = FloatArray(Yolo11Decoder.ANCHORS * 4)
        val scores = FloatArray(Yolo11Decoder.ANCHORS)
        val probs = FloatArray(Yolo11Decoder.ANCHORS * Yolo11Decoder.CLASSES)
        val anchors = numbers(frame.anchors).map { it.toInt() }
        val b = numbers(frame.boxes).map { it.toFloat() }
        val s = numbers(frame.scores).map { it.toFloat() }
        anchors.forEachIndexed { k, a ->
            for (c in 0..3) boxes[a * 4 + c] = b[k * 4 + c]
            scores[a] = s[k]
        }
        numbers(frame.probs).chunked(3).forEach { (a, c, v) -> probs[a.toInt() * Yolo11Decoder.CLASSES + c.toInt()] = v.toFloat() }
        return Outputs(boxes, scores, probs)
    }

    private fun expected(frame: Yolo11GoldenFixture.Frame): List<Detection> =
        numbers(frame.expected).chunked(6).map { v ->
            Detection(v[0].toInt(), v[1].toFloat(), v[2].toFloat(), v[3].toFloat(), v[4].toFloat(), v[5].toFloat())
        }

    private fun assertSame(expected: List<Detection>, actual: List<Detection>, what: String) {
        assertEquals(expected.map { it.classId }, actual.map { it.classId }, "$what class ids")
        expected.zip(actual).forEachIndexed { i, (e, a) ->
            assertEquals(e.score, a.score, 1e-6f, "$what #$i score")
            for ((ev, av) in listOf(e.left to a.left, e.top to a.top, e.right to a.right, e.bottom to a.bottom)) {
                assertTrue(abs(ev - av) <= 1f, "$what #$i box $e vs $a")
            }
        }
    }

    @Test
    fun goldenFramesMatchThePythonPortOnTheFullOutputs() {
        assertEquals(4, Yolo11GoldenFixture.frames.size)
        for (frame in Yolo11GoldenFixture.frames) {
            val o = outputs(frame)
            val expected = expected(frame)
            assertTrue(expected.isNotEmpty())
            assertSame(expected, Yolo11Decoder.decode(o.boxes, o.probs, o.scores, scoreThreshold = 0.25f, iouThreshold = 0.45f), frame.name)
            // Outputs in any order (as a runtime may list them), and the score taken from the class probabilities.
            assertSame(expected, Yolo11Decoder.decodeOutputs(listOf(o.probs, o.scores, o.boxes), scoreThreshold = 0.25f), "${frame.name} by size")
            assertSame(expected, Yolo11Decoder.decode(o.boxes, o.probs, null, scoreThreshold = 0.25f), "${frame.name} no Identity_1")
        }
        // Spot checks: COCO 285 is one bear, 724 a stop sign.
        val bear = expected(Yolo11GoldenFixture.frames.single { it.name == "285" })
        assertEquals(listOf("bear"), bear.map { Yolo11Decoder.COCO_CLASSES[it.classId] })
        val stop = expected(Yolo11GoldenFixture.frames.single { it.name == "724" }).first()
        assertEquals("stop sign", Yolo11Decoder.COCO_CLASSES[stop.classId])
    }

    @Test
    fun defaultThresholdKeepsTheConfidentSubset() {
        val frame = Yolo11GoldenFixture.frames.single { it.name == "139" }
        val o = outputs(frame)
        val expected = expected(frame).filter { it.score >= Yolo11Decoder.DEFAULT_SCORE_THRESHOLD }
        assertSame(expected, Yolo11Decoder.decode(o.boxes, o.probs, o.scores), "139 @0.35")
        assertEquals(3, Yolo11Decoder.decode(o.boxes, o.probs, o.scores, scoreThreshold = 0.25f, maxDetections = 3).size)
    }

    @Test
    fun nmsIsClassAwareAndClampsToTheInput() {
        // Anchors: 0 and 1 overlap (IoU 9025 / 10975 = 0.822) same class; 2 overlaps 0 but is another class; 3 sticks out of the input.
        val boxes = floatArrayOf(
            10f, 10f, 110f, 110f,
            15f, 15f, 115f, 115f,
            10f, 10f, 110f, 110f,
            600f, -20f, 700f, 40f,
        )
        val probs = FloatArray(4 * 3)
        probs[0 * 3 + 1] = 0.9f
        probs[1 * 3 + 1] = 0.8f
        probs[2 * 3 + 2] = 0.7f
        probs[3 * 3 + 0] = 0.6f
        val out = Yolo11Decoder.decode(boxes, probs, scoreThreshold = 0.5f, iouThreshold = 0.45f)
        assertEquals(listOf(1, 2, 0), out.map { it.classId })
        assertEquals(Detection(0, 0.6f, 600f, 0f, 640f, 40f), out[2])
        // A stricter IoU threshold keeps both class-1 boxes.
        assertEquals(4, Yolo11Decoder.decode(boxes, probs, scoreThreshold = 0.5f, iouThreshold = 0.9f).size)
        assertEquals(0.8223f, Yolo11Decoder.iou(Detection(0, 1f, 10f, 10f, 110f, 110f), Detection(0, 1f, 15f, 15f, 115f, 115f)), 1e-4f)
    }

    @Test
    fun classIndexAndScoreOutputsAreToldApartByRange() {
        val boxes = floatArrayOf(0f, 0f, 10f, 10f, 20f, 20f, 40f, 40f)
        val classIds = floatArrayOf(5f, 11f)
        val scores = floatArrayOf(0.9f, 0.2f)
        val out = Yolo11Decoder.decodeOutputs(listOf(scores, boxes, classIds))
        assertEquals(listOf(Detection(5, 0.9f, 0f, 0f, 10f, 10f)), out)
    }

    @Test
    fun letterboxCentresAndMapsBack() {
        val lb = YoloLetterbox.of(1280, 720)
        assertEquals(0.5f, lb.scale)
        assertEquals(640 to 360, lb.scaledWidth to lb.scaledHeight)
        assertEquals(0 to 140, lb.padLeft to lb.padTop)
        val back = lb.toSource(Detection(3, 0.8f, 100f, 140f, 300f, 600f))
        assertEquals(Detection(3, 0.8f, 200f, 0f, 600f, 720f), back) // bottom clamped to the image
        // Odd padding: the extra pixel goes right/bottom; top-left anchored when not centred.
        val odd = YoloLetterbox.of(100, 99, centered = true)
        assertEquals(640 to 634, odd.scaledWidth to odd.scaledHeight) // round(99 * 6.4) = round(633.6)
        assertEquals(3, odd.padTop)
        assertEquals(0, YoloLetterbox.of(100, 99, centered = false).padTop)
    }

    @Test
    fun letterboxInputIsRgbOver255OnGreyPadding() {
        val lb = YoloLetterbox.of(4, 2) // 640 x 320 at top 160
        val red = IntArray(8) { 0xFFFF0000.toInt() }
        val input = lb.input(red)
        assertEquals(640 * 640 * 3, input.size)
        fun px(x: Int, y: Int) = (0..2).map { input[(y * 640 + x) * 3 + it] }
        assertEquals(listOf(114 / 255f, 114 / 255f, 114 / 255f), px(0, 0))
        assertEquals(listOf(1f, 0f, 0f), px(0, 160))
        assertEquals(listOf(1f, 0f, 0f), px(639, 479))
        assertEquals(listOf(114 / 255f, 114 / 255f, 114 / 255f), px(639, 480))
    }
}
