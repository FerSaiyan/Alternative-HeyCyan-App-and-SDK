package com.fersaiyan.cyanbridge.shared.devices.mentra

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MentraDisplayTextTest {
    @Test
    fun wrapsWordsKeepsBreaksAndSplitsLongWords() {
        assertEquals(
            listOf("The quick", "brown fox", "jumps", "Second", "line"),
            MentraDisplayText.wrap("The quick  brown fox jumps\r\n\nSecond line", columns = 10),
        )
        assertEquals(listOf("abcdefghij", "klm next"), MentraDisplayText.wrap("abcdefghijklm next", columns = 10))
        // Wide characters take two columns; an emoji's surrogate pair is never split.
        assertEquals(listOf("你好世界", "你好"), MentraDisplayText.wrap("你好世界你好", columns = 8))
        assertEquals(listOf("a😀", "😀"), MentraDisplayText.wrap("a😀😀", columns = 3))
        assertEquals(emptyList(), MentraDisplayText.wrap(" \n ", columns = 10))
    }

    @Test
    fun pagesRespectLinesAndTheByteBudget() {
        val limits = MentraDisplayLimits(columns = 10, lines = 2, maxBytes = 390)
        assertEquals(
            listOf("one two\nthree four", "five six\nseven"),
            MentraDisplayText.pages("one two three four five six seven", limits),
        )
        // Every model's pages fit its screen and its text command.
        MentraSdkModel.entries.mapNotNull { it.display }.forEach { model ->
            val answer = "Ünïcödé answer with plenty of words to fill several screens of the display. ".repeat(12)
            MentraDisplayText.pages(answer, model).forEach { page ->
                val lines = page.split('\n')
                assertTrue(lines.size <= model.lines, "$model lines")
                assertTrue(lines.all { it.length <= model.columns }, "$model columns")
                assertTrue(page.encodeToByteArray().size <= model.maxBytes, "$model bytes")
            }
        }
        val tight = MentraDisplayLimits(columns = 4, lines = 5, maxBytes = 12)
        assertEquals(listOf("éééé", "éééé"), MentraDisplayText.pages("éééé éééé", tight))
    }

    @Test
    fun dwellFollowsTheLengthWithinBounds() {
        assertEquals(3_000L, MentraDisplayText.dwellMs("Hi"))
        assertEquals(12_000L, MentraDisplayText.dwellMs("x".repeat(400)))
    }
}
