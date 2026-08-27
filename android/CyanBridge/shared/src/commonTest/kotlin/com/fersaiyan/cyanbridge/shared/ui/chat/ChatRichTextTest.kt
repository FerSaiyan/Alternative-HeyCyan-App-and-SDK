package com.fersaiyan.cyanbridge.shared.ui.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ChatRichTextTest {
    @Test
    fun parsesCommonLlmMarkdownBlocks() {
        val dollar = '$'
        val blocks = ChatMarkdownParser.parse(
            """
            # Result

            - first
            2. second

            ```kotlin
            val answer = 42
            ```

            ${dollar}${dollar}E = mc^2${dollar}${dollar}
            """.trimIndent()
        )

        assertIs<ChatMarkdownBlock.Heading>(blocks[0])
        assertIs<ChatMarkdownBlock.ListItem>(blocks[1])
        assertIs<ChatMarkdownBlock.ListItem>(blocks[2])
        assertIs<ChatMarkdownBlock.Code>(blocks[3])
        assertIs<ChatMarkdownBlock.Math>(blocks[4])
    }

    @Test
    fun normalizesCommonLatexWithoutWebRendering() {
        val normalized = normalizeLatex("\\frac{1}{2} + \\alpha_1 + x^2 \\leq \\infty")
        assertTrue(normalized.contains("(1)/(2)"))
        assertTrue(normalized.contains("α₁"))
        assertTrue(normalized.contains("x²"))
        assertTrue(normalized.contains("≤"))
        assertTrue(normalized.contains("∞"))
    }

    @Test
    fun incompleteStreamingFenceRemainsReadable() {
        val blocks = ChatMarkdownParser.parse("```python\nprint('streaming')")
        val code = assertIs<ChatMarkdownBlock.Code>(blocks.single())
        assertEquals("print('streaming')", code.code)
    }
}
