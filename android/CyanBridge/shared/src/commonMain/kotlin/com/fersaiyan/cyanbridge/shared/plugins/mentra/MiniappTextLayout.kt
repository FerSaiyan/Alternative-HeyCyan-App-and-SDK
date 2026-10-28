package com.fersaiyan.cyanbridge.shared.plugins.mentra

import com.fersaiyan.cyanbridge.shared.devices.DeviceClass
import com.fersaiyan.cyanbridge.shared.devices.mentra.MentraSdkModel

/**
 * The text canvas CyanBridge reports to miniapps (`capabilities.display`). Its glasses take plain
 * text, so scenes degrade to a text wall (`canPosition:false`, as on the G1). The metrics are
 * approximations of a 5-line HUD to calibrate per device on hardware.
 */
data class MiniappDisplayProfile(
    val width: Int = 500,
    val height: Int = 220,
    val lineHeightPx: Int = 44,
    val glyphWidthPx: Int = 12,
    val maxLines: Int = 5,
) {
    fun charsPerLine(widthPx: Int): Int = maxOf(1, widthPx / glyphWidthPx)

    companion object {
        /** Mentra SDK display glasses: a canvas of their text limits (columns × lines) in the same glyph metrics. */
        fun forClass(deviceClass: DeviceClass): MiniappDisplayProfile {
            val limits = MentraSdkModel.forClass(deviceClass)?.display ?: return MiniappDisplayProfile()
            val base = MiniappDisplayProfile()
            return base.copy(
                width = limits.columns * base.glyphWidthPx,
                height = limits.lines * base.lineHeightPx,
                maxLines = limits.lines,
            )
        }
    }
}

/** One visible line with UTF-16 offsets into the element's text (end exclusive), as `RenderTextLine`. */
data class MiniappTextLine(val text: String, val start: Int, val end: Int)

/** `RenderTextLayout`: what the host shows of one text element, and where every wrapped line starts. */
data class MiniappTextLayoutResult(
    val lines: List<MiniappTextLine>,
    val lineStarts: List<Int>,
    val capacity: Int,
    val truncated: Boolean,
) {
    val text: String get() = lines.joinToString("\n") { it.text }
}

/**
 * Host-side wrapping for `display.render` (Mentra's `scene/text.ts`, simplified to a fixed glyph
 * width): word or character breaking, the box's and `maxLines`' capacity, and `textWindow`
 * choosing which end stays visible. Apps such as Teleprompter scroll by these offsets.
 */
object MiniappTextLayout {
    fun layout(
        text: String,
        boxWidth: Int,
        boxHeight: Int,
        maxLines: Int?,
        fromEnd: Boolean,
        breakByCharacter: Boolean,
        profile: MiniappDisplayProfile,
    ): MiniappTextLayoutResult {
        val all = wrap(text, profile.charsPerLine(boxWidth), breakByCharacter)
        val heightLimit = maxOf(0, boxHeight / profile.lineHeightPx)
        val capacity = minOf(profile.maxLines, heightLimit, maxLines?.coerceAtLeast(0) ?: Int.MAX_VALUE)
        val visible = when {
            capacity == 0 -> emptyList()
            fromEnd -> all.takeLast(capacity)
            else -> all.take(capacity)
        }
        return MiniappTextLayoutResult(visible, all.map { it.start }, capacity, visible.size < all.size)
    }

    /** Wraps [text] into lines of at most [width] characters, keeping source offsets. */
    fun wrap(text: String, width: Int, breakByCharacter: Boolean): List<MiniappTextLine> {
        val out = mutableListOf<MiniappTextLine>()
        var paragraphStart = 0
        while (paragraphStart <= text.length) {
            val newline = text.indexOf('\n', paragraphStart).let { if (it < 0) text.length else it }
            wrapParagraph(text, paragraphStart, newline, width, breakByCharacter, out)
            paragraphStart = newline + 1
        }
        return out
    }

    private fun wrapParagraph(text: String, from: Int, to: Int, width: Int, byChar: Boolean, out: MutableList<MiniappTextLine>) {
        var i = from
        fun skipSpaces() { while (i < to && text[i].isWhitespace()) i++ }
        skipSpaces()
        if (i >= to) {
            // An empty paragraph is still a line (blank lines separate paragraphs).
            out += MiniappTextLine("", minOf(from, text.length), minOf(from, text.length))
            return
        }
        while (i < to) {
            val lineStart = i
            val line = StringBuilder()
            var lineEnd = i
            if (byChar) {
                while (i < to && line.length < width) {
                    line.append(text[i]); i++
                }
                lineEnd = i
                while (lineEnd > lineStart && text[lineEnd - 1].isWhitespace()) lineEnd--
                out += MiniappTextLine(text.substring(lineStart, lineEnd), lineStart, lineEnd)
                skipSpaces()
                continue
            }
            while (i < to) {
                var wordEnd = i
                while (wordEnd < to && !text[wordEnd].isWhitespace()) wordEnd++
                val wordLength = wordEnd - i
                val needed = if (line.isEmpty()) wordLength else line.length + 1 + wordLength
                if (needed <= width) {
                    if (line.isNotEmpty()) line.append(' ')
                    line.append(text, i, wordEnd)
                    lineEnd = wordEnd
                    i = wordEnd
                    skipSpaces()
                } else if (line.isEmpty()) {
                    // A word longer than the line breaks inside the word.
                    line.append(text, i, i + width)
                    i += width
                    lineEnd = i
                    break
                } else {
                    break
                }
            }
            out += MiniappTextLine(line.toString(), lineStart, lineEnd)
        }
    }
}
