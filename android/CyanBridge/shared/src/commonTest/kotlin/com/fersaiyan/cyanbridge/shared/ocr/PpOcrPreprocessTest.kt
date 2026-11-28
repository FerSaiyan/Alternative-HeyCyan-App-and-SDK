package com.fersaiyan.cyanbridge.shared.ocr

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Expected values come from the spec pipeline in numpy + OpenCV 4.10 (ocrlib.py PPDet fixed_hw / PPRec), on the
 * RGB pattern img[y, x] = ((x*37 + y*91 + 11) % 256, (x*x*7 + y*13 + 101) % 256, (x*y*29 + 53) % 256):
 *   det: bgr = img[:, :, ::-1]; r = min(256/h, 512/w); nh, nw = int(round(h*r)), int(round(w*r))
 *        canvas = zeros((256, 512, 3), uint8); canvas[:nh, :nw] = cv2.resize(bgr, (nw, nh))
 *        x = (canvas.astype(float32) / 255.0 - [0.485, 0.456, 0.406]) / [0.229, 0.224, 0.225]
 *   rec: rw = max(min(320, ceil(48 * w / h)), 1); im = cv2.resize(bgr, (rw, 48), interpolation=cv2.INTER_LINEAR)
 *        out = zeros((48, 320, 3), float32); out[:, :rw] = (im.astype(float32) / 255.0 - 0.5) / 0.5
 */
class PpOcrPreprocessTest {
    private fun pattern(w: Int, h: Int) = IntArray(w * h) { i ->
        val x = i % w
        val y = i / w
        (0xFF shl 24) or (((x * 37 + y * 91 + 11) % 256) shl 16) or (((x * x * 7 + y * 13 + 101) % 256) shl 8) or
            ((x * y * 29 + 53) % 256)
    }

    private fun assertPixel(expected: List<Double>, tensor: FloatArray, width: Int, y: Int, x: Int) {
        for (c in 0..2) {
            assertEquals(expected[c].toFloat(), tensor[(y * width + x) * 3 + c], 1e-6f, "($y, $x) channel $c")
        }
    }

    @Test
    fun detLetterboxesTopLeftWithPythonRounding() {
        val det = PpOcrPreprocess.det(pattern(20, 4), 20, 4)
        assertEquals(256 * 512 * 3, det.tensor.size)
        assertEquals(25.6, det.ratio, 1e-12)
        assertEquals(512, det.resizedWidth)
        assertEquals(102, det.resizedHeight) // round(4 * 25.6) = round(102.4)
        // Python round is half to even; sizes from the same formula.
        mapOf((1280 to 720) to (455 to 256), (100 to 333) to (77 to 256), (3 to 1) to (512 to 171)).forEach { (src, dst) ->
            val d = PpOcrPreprocess.det(IntArray(src.first * src.second), src.first, src.second)
            assertEquals(dst, d.resizedWidth to d.resizedHeight, "$src")
        }
    }

    @Test
    fun detPixelsAreBgrImageNetNormalizedAndPaddingIsMinusMeanOverStd() {
        val t = PpOcrPreprocess.det(pattern(20, 4), 20, 4).tensor
        assertPixel(listOf(-1.210292100906372, -0.2675069570541382, -1.6127233505249023), t, 512, 0, 0)
        assertPixel(listOf(-1.210292100906372, -0.2675069570541382, -1.6127233505249023), t, 512, 0, 1)
        assertPixel(listOf(0.5535576939582825, 1.0630252361297607, 0.8273640275001526), t, 512, 50, 300)
        assertPixel(listOf(0.7933042645454407, -0.1624649614095688, 2.0125491619110107), t, 512, 101, 511)
        val pad = listOf(-2.1179039478302, -2.0357141494750977, -1.804444432258606) // -mean/std in B, G, R order
        assertPixel(pad, t, 512, 102, 0)
        assertPixel(pad, t, 512, 255, 511)
        // Solid R=200 G=100 B=50: B = (50/255 - 0.485)/0.229, G = (100/255 - 0.456)/0.224, R = (200/255 - 0.406)/0.225.
        val solid = IntArray(9) { 0xFFC86432.toInt() }
        assertPixel(listOf(-1.2616662979125977, -0.28501397371292114, 1.6813944578170776), PpOcrPreprocess.det(solid, 3, 3).tensor, 512, 0, 0)
    }

    @Test
    fun recKeepsAspectAndZeroFillsTheRight() {
        assertEquals(112, PpOcrPreprocess.recWidth(7, 3))
        assertEquals(320, PpOcrPreprocess.recWidth(1000, 10)) // squeezed
        assertEquals(3, PpOcrPreprocess.recWidth(3, 48))
        assertEquals(1, PpOcrPreprocess.recWidth(1, 100)) // ceil(0.48), at least 1
        assertEquals(320, PpOcrPreprocess.recWidth(100, 15))

        val t = PpOcrPreprocess.rec(pattern(10, 5), 10, 5)
        assertEquals(48 * 320 * 3, t.size)
        assertEquals(96, PpOcrPreprocess.recWidth(10, 5))
        assertPixel(listOf(-0.5843137502670288, -0.2078431248664856, -0.9137254953384399), t, 320, 0, 0)
        assertPixel(listOf(-0.2078431248664856, 0.16078436374664307, -0.05882352590560913), t, 320, 20, 47)
        assertPixel(listOf(-0.427450954914093, 0.6313725709915161, 0.5372549295425415), t, 320, 47, 95)
        for (y in 0 until 48) for (x in 96 until 320) assertPixel(listOf(0.0, 0.0, 0.0), t, 320, y, x)
        // Black and white map to -1 and 1.
        val bw = PpOcrPreprocess.rec(intArrayOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt()), 2, 1)
        assertPixel(listOf(-1.0, -1.0, -1.0), bw, 320, 0, 0)
        assertPixel(listOf(1.0, 1.0, 1.0), bw, 320, 47, 95)
    }
}
