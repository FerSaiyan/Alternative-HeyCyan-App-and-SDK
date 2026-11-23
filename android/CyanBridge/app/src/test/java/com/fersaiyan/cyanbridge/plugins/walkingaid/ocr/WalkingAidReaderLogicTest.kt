package com.fersaiyan.cyanbridge.plugins.walkingaid.ocr

import com.fersaiyan.cyanbridge.localmodels.engine.NpuArtifacts
import com.fersaiyan.cyanbridge.localmodels.engine.NpuDType
import com.fersaiyan.cyanbridge.localmodels.engine.NpuFloatRunner
import com.fersaiyan.cyanbridge.localmodels.engine.NpuTensor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The JVM-testable parts of the Walking Aid ride/bus reader and the NPU float view. */
class WalkingAidReaderLogicTest {
    @Test fun plateFormatRulesFixLookalikesByPosition() {
        assertEquals("ABC1D23", OcrText.plateFormatFix("ABC-1D23"))
        assertEquals("ABC1D22", OcrText.plateFormatFix("A8C1D2Z"))      // 8 -> B in a letter slot, Z -> 2 in a digit slot
        assertEquals("PJU2853", OcrText.plateFormatFix("BRASIL PJU·2853")) // the number follows the BRASIL band
        assertTrue(OcrText.plateMatches("abc 1d23", "ABC1D23"))
        assertFalse(OcrText.plateMatches("ABC1D28", "ABC1D23"))         // one character off is another car
    }

    @Test fun busSignsMatchRouteTokensAndDestinations() {
        assertTrue(OcrText.busSignMatches("175 TERMINAL CAMPINA", "175"))
        assertFalse(OcrText.busSignMatches("1750 CENTRO", "175"))
        assertTrue(OcrText.busSignMatches("CAMPINA DO SIQUEIRA", "campina do siqueira"))
        assertTrue(OcrText.busSignMatches("CAMPINA D0 SIQUEIRA", "campina do siqueira")) // within 15 % edits
    }

    @Test fun ctcCollapsesRepeatsAndBlanks() {
        val chars = listOf("<blank>", "A", "B")
        // steps: A A blank A B B -> "AAB"
        val rows = listOf(1, 1, 0, 1, 2, 2)
        val probs = FloatArray(rows.size * 3).also { p -> rows.forEachIndexed { t, c -> p[t * 3 + c] = 0.9f } }
        assertEquals("AAB", OcrText.ctcGreedy(probs, 3, chars).first)
        assertEquals("ABC", OcrText.vote(listOf("ABC", "ABD", "ABC")))
        assertNull(OcrText.vote(listOf("ABC", "ABD")))
    }

    @Test fun dbPostprocessFindsTextRegionsInReadingOrder() {
        val w = 40; val h = 20
        val map = FloatArray(w * h)
        fun fill(x0: Int, y0: Int, x1: Int, y1: Int) { for (y in y0 until y1) for (x in x0 until x1) map[y * w + x] = 0.9f }
        fill(22, 2, 36, 7)  // second word, first row
        fill(2, 2, 18, 7)   // first word, first row
        fill(2, 12, 30, 17) // second row
        val boxes = DbPostprocess.boxes(map, w, h)
        assertEquals(3, boxes.size)
        assertTrue(boxes[0].left < boxes[1].left && boxes[0].top < boxes[2].top)
    }

    @Test fun goalParsing() {
        assertEquals(WalkingAidGoal.Ride("ABC1D23"), WalkingAidGoalParser.parseKeywords("meu uber é placa ABC 1D23"))
        assertEquals(WalkingAidGoal.Bus("175"), WalkingAidGoalParser.parseKeywords("estou esperando o ônibus 175"))
        assertEquals(WalkingAidGoal.Describe, WalkingAidGoalParser.parseKeywords("just tell me what's around"))
        assertEquals(WalkingAidGoal.Ride(null), WalkingAidGoalParser.parseModelReply("RIDE NONE"))
        assertEquals(WalkingAidGoal.Bus("Campina do Siqueira"), WalkingAidGoalParser.parseModelReply("BUS Campina do Siqueira\n"))
        assertNull(WalkingAidGoalParser.parseModelReply("I think they want a bus"))
    }

    @Test fun npuFloatViewRoundTripsQuantizedTensors() {
        val int8 = NpuArtifacts.TensorSpec("image", intArrayOf(1, 2), NpuDType.INT8, scale = 1f / 255f, zeroPoint = -128)
        val q = NpuFloatRunner.quantize(floatArrayOf(0f, 1f), int8) as NpuTensor.Bytes
        assertEquals(listOf<Byte>(-128, 127), q.values.toList())
        val back = NpuFloatRunner.dequantize(q, int8)
        assertEquals(0f, back[0], 1e-6f); assertEquals(1f, back[1], 1e-6f)
        val uint8 = int8.copy(dtype = NpuDType.UINT8, zeroPoint = 0)
        assertEquals(1f, NpuFloatRunner.dequantize(NpuTensor.Bytes(byteArrayOf(-1)), uint8)[0], 1e-6f) // 0xFF
        val int16 = NpuArtifacts.TensorSpec("pcm", intArrayOf(1), NpuDType.INT16, scale = 3.01e-5f)
        assertEquals(0.5f, NpuFloatRunner.dequantize(NpuFloatRunner.quantize(floatArrayOf(0.5f), int16), int16)[0], 1e-4f)
    }
}
