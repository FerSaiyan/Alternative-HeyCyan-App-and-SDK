package com.fersaiyan.cyanbridge.shared.ocr

import com.fersaiyan.cyanbridge.plugins.walkingaid.ocr.OcrText
import kotlin.test.Test
import kotlin.test.assertEquals

class OcrDictionaryTest {
    @Test
    fun blankFirstLinesUntrimmedSpaceLast() {
        // U+3000 (ideographic space) is a real class; CRLF and the final newline are terminators, not characters.
        val chars = OcrDictionary.classes("﻿a\r\nb\n　\n \nc\n")
        assertEquals(listOf("<blank>", "a", "b", "　", " ", "c", " "), chars)
        assertEquals(listOf("a", "", "b"), OcrDictionary.lines("a\n\nb"))
        assertEquals(emptyList(), OcrDictionary.lines(""))
    }

    @Test
    fun spaceClassOnlyWhenTheRecognizerHasIt() {
        assertEquals(4, OcrDictionary.classes("x\ny", recognizerClasses = 4).size)
        assertEquals(3, OcrDictionary.classes("x\ny", recognizerClasses = 3).size)
    }

    @Test
    fun decodesWithCtcGreedy() {
        val chars = OcrDictionary.classes("B\nU\nS\n1\n5")
        val classes = chars.size // blank + 5 + space = 7
        // B U S space 1 5 with blanks and repeats.
        val steps = listOf(1, 1, 0, 2, 3, 6, 6, 4, 0, 5)
        val probs = FloatArray(steps.size * classes).also { p -> steps.forEachIndexed { t, c -> p[t * classes + c] = 0.9f } }
        assertEquals("BUS 15", OcrText.ctcGreedy(probs, classes, chars).first)
    }
}
