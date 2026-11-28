package com.fersaiyan.cyanbridge.plugins.walkingaid.ocr

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Walking Aid reader text rules and DB detector post-processing (moved from the app's WalkingAidReaderLogicTest). */
class OcrTextTest {
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
}
