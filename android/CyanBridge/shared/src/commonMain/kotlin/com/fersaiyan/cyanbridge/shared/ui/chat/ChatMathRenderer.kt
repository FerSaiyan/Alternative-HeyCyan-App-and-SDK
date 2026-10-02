package com.fersaiyan.cyanbridge.shared.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/**
 * Typeset math for chat bubbles. The iOS host provides a bundled-KaTeX renderer; without one,
 * ChatRichText keeps its Unicode approximation.
 */
interface ChatMathRenderer {
    /** A display formula (`$$…$$`, `\[…\]`). [background] is the bubble color behind it. */
    @Composable
    fun Formula(latex: String, color: Color, background: Color, modifier: Modifier)

    /** A paragraph mixing prose with inline math (`$…$`, `\(…\)`), light Markdown included. */
    @Composable
    fun Paragraph(text: String, color: Color, background: Color, modifier: Modifier)
}

val LocalChatMathRenderer = staticCompositionLocalOf<ChatMathRenderer?> { null }

/** `$x$` needs no space just inside the dollars, so "R$ 5,00 e R$ 10" is not math. */
internal val INLINE_MATH = Regex("""\$(?=\S)[^$\n]+?(?<=\S)\$|\\\(.+?\\\)""")

fun hasInlineMath(text: String): Boolean = INLINE_MATH.containsMatchIn(text)
