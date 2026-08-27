package com.fersaiyan.cyanbridge.shared.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp

/**
 * Small dependency-free Markdown renderer for chat output.
 *
 * Chat responses are streamed and the shared UI is Kotlin Multiplatform, so this deliberately
 * keeps parsing deterministic and cheap. It supports the formatting LLMs use most often:
 * headings, paragraphs, bullets, numbered lists, blockquotes, fenced code, bold/italic/
 * bold-italic, strike-through, inline code, links, and readable LaTeX-style math.
 *
 * LaTeX is normalized into Unicode where that is safe (Greek symbols, operators, simple
 * fractions/square roots/super- and subscripts). Unknown commands remain visible instead of
 * disappearing. This is preferable to a WebView/remote MathJax dependency for a private,
 * offline-first chat surface.
 */
@Composable
fun ChatRichText(
    markdown: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val blocks = ChatMarkdownParser.parse(markdown)
    Column(modifier = modifier, verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
        blocks.forEach { block ->
            when (block) {
                is ChatMarkdownBlock.Heading -> Text(
                    text = richInline(block.text),
                    color = color,
                    style = when (block.level) {
                        1 -> MaterialTheme.typography.titleLarge
                        2 -> MaterialTheme.typography.titleMedium
                        else -> MaterialTheme.typography.titleSmall
                    },
                    fontWeight = FontWeight.SemiBold,
                )

                is ChatMarkdownBlock.Paragraph -> Text(
                    text = richInline(block.text),
                    color = color,
                    style = MaterialTheme.typography.bodyLarge,
                )

                is ChatMarkdownBlock.ListItem -> Text(
                    text = richInline("${block.prefix} ${block.text}"),
                    color = color,
                    style = MaterialTheme.typography.bodyLarge,
                )

                is ChatMarkdownBlock.Quote -> Text(
                    text = richInline("▌ ${block.text}"),
                    color = color.copy(alpha = 0.88f),
                    style = MaterialTheme.typography.bodyMedium,
                    fontStyle = FontStyle.Italic,
                )

                is ChatMarkdownBlock.Code -> Text(
                    text = block.code,
                    color = color,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(color.copy(alpha = 0.10f), RoundedCornerShape(8.dp))
                        .padding(10.dp),
                )

                is ChatMarkdownBlock.Math -> Text(
                    text = normalizeLatex(block.formula),
                    color = color,
                    style = MaterialTheme.typography.bodyLarge,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(color.copy(alpha = 0.08f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                )
            }
        }
    }
}

internal sealed interface ChatMarkdownBlock {
    data class Heading(val level: Int, val text: String) : ChatMarkdownBlock
    data class Paragraph(val text: String) : ChatMarkdownBlock
    data class ListItem(val prefix: String, val text: String) : ChatMarkdownBlock
    data class Quote(val text: String) : ChatMarkdownBlock
    data class Code(val code: String, val language: String?) : ChatMarkdownBlock
    data class Math(val formula: String) : ChatMarkdownBlock
}

internal object ChatMarkdownParser {
    private val heading = Regex("^(#{1,6})\\s+(.+)$")
    private val unordered = Regex("^\\s*[-+*]\\s+(.+)$")
    private val ordered = Regex("^\\s*(\\d+)[.)]\\s+(.+)$")

    fun parse(markdown: String): List<ChatMarkdownBlock> {
        if (markdown.isBlank()) return listOf(ChatMarkdownBlock.Paragraph(""))

        val out = mutableListOf<ChatMarkdownBlock>()
        val paragraph = mutableListOf<String>()
        val code = mutableListOf<String>()
        val math = mutableListOf<String>()
        var codeLanguage: String? = null
        var inCode = false
        var inMath = false

        fun flushParagraph() {
            if (paragraph.isNotEmpty()) {
                out += ChatMarkdownBlock.Paragraph(paragraph.joinToString("\n").trim())
                paragraph.clear()
            }
        }
        fun flushCode() {
            out += ChatMarkdownBlock.Code(code.joinToString("\n").trimEnd(), codeLanguage)
            code.clear()
            codeLanguage = null
        }
        fun flushMath() {
            out += ChatMarkdownBlock.Math(math.joinToString("\n").trim())
            math.clear()
        }

        markdown.replace("\r\n", "\n").replace('\r', '\n').lines().forEach { raw ->
            val line = raw.trimEnd()
            val trimmed = line.trim()

            if (inCode) {
                if (trimmed.startsWith("```")) {
                    inCode = false
                    flushCode()
                } else {
                    code += line
                }
                return@forEach
            }

            if (inMath) {
                val end = trimmed.indexOf("$$")
                if (end >= 0) {
                    math += trimmed.substring(0, end)
                    inMath = false
                    flushMath()
                    val remainder = trimmed.substring(end + 2).trim()
                    if (remainder.isNotEmpty()) paragraph += remainder
                } else {
                    math += line
                }
                return@forEach
            }

            if (trimmed.startsWith("```")) {
                flushParagraph()
                inCode = true
                codeLanguage = trimmed.removePrefix("```").trim().ifBlank { null }
                return@forEach
            }

            if (trimmed.startsWith("$$")) {
                flushParagraph()
                val rest = trimmed.removePrefix("$$")
                val closing = rest.indexOf("$$")
                if (closing >= 0) {
                    out += ChatMarkdownBlock.Math(rest.substring(0, closing).trim())
                    val remainder = rest.substring(closing + 2).trim()
                    if (remainder.isNotEmpty()) paragraph += remainder
                } else {
                    inMath = true
                    if (rest.isNotBlank()) math += rest
                }
                return@forEach
            }

            if (trimmed.startsWith("\\[") && trimmed.endsWith("\\]") && trimmed.length >= 4) {
                flushParagraph()
                out += ChatMarkdownBlock.Math(trimmed.removePrefix("\\[").removeSuffix("\\]").trim())
                return@forEach
            }

            if (trimmed.isBlank()) {
                flushParagraph()
                return@forEach
            }

            heading.matchEntire(trimmed)?.let { match ->
                flushParagraph()
                out += ChatMarkdownBlock.Heading(match.groupValues[1].length, match.groupValues[2].trim())
                return@forEach
            }
            unordered.matchEntire(line)?.let { match ->
                flushParagraph()
                out += ChatMarkdownBlock.ListItem("•", match.groupValues[1].trim())
                return@forEach
            }
            ordered.matchEntire(line)?.let { match ->
                flushParagraph()
                out += ChatMarkdownBlock.ListItem("${match.groupValues[1]}.", match.groupValues[2].trim())
                return@forEach
            }
            if (trimmed.startsWith(">")) {
                flushParagraph()
                out += ChatMarkdownBlock.Quote(trimmed.removePrefix(">").trim())
                return@forEach
            }

            paragraph += line
        }

        if (inCode) flushCode()
        if (inMath) flushMath()
        flushParagraph()
        return out.ifEmpty { listOf(ChatMarkdownBlock.Paragraph(markdown)) }
    }
}

private fun richInline(text: String): AnnotatedString = buildAnnotatedString {
    appendInline(this, text, 0, text.length)
}

private data class InlineDelimiter(
    val marker: String,
    val style: SpanStyle,
)

private val inlineDelimiters = listOf(
    InlineDelimiter("***", SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)),
    InlineDelimiter("___", SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)),
    InlineDelimiter("**", SpanStyle(fontWeight = FontWeight.Bold)),
    InlineDelimiter("__", SpanStyle(fontWeight = FontWeight.Bold)),
    InlineDelimiter("~~", SpanStyle(textDecoration = TextDecoration.LineThrough)),
    InlineDelimiter("*", SpanStyle(fontStyle = FontStyle.Italic)),
    InlineDelimiter("_", SpanStyle(fontStyle = FontStyle.Italic)),
)

private fun appendInline(builder: AnnotatedString.Builder, text: String, from: Int, to: Int) {
    var cursor = from
    while (cursor < to) {
        val inlineCodeStart = text.indexOf('`', cursor).takeIf { it in cursor until to }
        val inlineMathStart = text.indexOf('$', cursor).takeIf { it in cursor until to }
        val linkStart = text.indexOf('[', cursor).takeIf { it in cursor until to }
        val styled = inlineDelimiters.mapNotNull { delimiter ->
            val start = text.indexOf(delimiter.marker, cursor)
            if (start !in cursor until to) return@mapNotNull null
            val end = text.indexOf(delimiter.marker, start + delimiter.marker.length)
            if (end <= start || end >= to) return@mapNotNull null
            Triple(start, end, delimiter)
        }.minByOrNull { it.first }

        val candidates = listOfNotNull(
            inlineCodeStart?.let { it to "code" },
            inlineMathStart?.let { it to "math" },
            linkStart?.let { it to "link" },
            styled?.let { it.first to "style" },
        )
        val next = candidates.minByOrNull { it.first }
        if (next == null) {
            builder.append(normalizeInlineLatex(text.substring(cursor, to)))
            break
        }
        if (next.first > cursor) {
            builder.append(normalizeInlineLatex(text.substring(cursor, next.first)))
        }

        when (next.second) {
            "code" -> {
                val end = text.indexOf('`', next.first + 1)
                if (end <= next.first || end >= to) {
                    builder.append("`")
                    cursor = next.first + 1
                } else {
                    builder.pushStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = Color.Black.copy(alpha = 0.10f)))
                    builder.append(text.substring(next.first + 1, end))
                    builder.pop()
                    cursor = end + 1
                }
            }

            "math" -> {
                val end = text.indexOf('$', next.first + 1)
                if (end <= next.first + 1 || end >= to) {
                    builder.append("$")
                    cursor = next.first + 1
                } else {
                    builder.pushStyle(SpanStyle(fontFamily = FontFamily.Monospace))
                    builder.append(normalizeLatex(text.substring(next.first + 1, end)))
                    builder.pop()
                    cursor = end + 1
                }
            }

            "link" -> {
                val closeText = text.indexOf(']', next.first + 1)
                val openUrl = if (closeText >= 0) text.indexOf('(', closeText + 1) else -1
                val closeUrl = if (openUrl >= 0) text.indexOf(')', openUrl + 1) else -1
                if (closeText in (next.first + 1) until to && openUrl == closeText + 1 && closeUrl in (openUrl + 1) until to) {
                    builder.pushStyle(SpanStyle(textDecoration = TextDecoration.Underline))
                    appendInline(builder, text, next.first + 1, closeText)
                    builder.pop()
                    cursor = closeUrl + 1
                } else {
                    builder.append("[")
                    cursor = next.first + 1
                }
            }

            else -> {
                val match = styled
                if (match == null || match.first != next.first) {
                    builder.append(text[next.first].toString())
                    cursor = next.first + 1
                } else {
                    val delimiter = match.third
                    builder.pushStyle(delimiter.style)
                    appendInline(builder, text, match.first + delimiter.marker.length, match.second)
                    builder.pop()
                    cursor = match.second + delimiter.marker.length
                }
            }
        }
    }
}

private fun normalizeInlineLatex(text: String): String {
    if ('\\' !in text && '^' !in text && '_' !in text) return text
    return normalizeLatex(text)
}

internal fun normalizeLatex(input: String): String {
    var out = input.trim()
    val replacements = linkedMapOf(
        "\\alpha" to "α", "\\beta" to "β", "\\gamma" to "γ", "\\delta" to "δ",
        "\\epsilon" to "ε", "\\theta" to "θ", "\\lambda" to "λ", "\\mu" to "μ",
        "\\pi" to "π", "\\rho" to "ρ", "\\sigma" to "σ", "\\phi" to "φ",
        "\\omega" to "ω", "\\Gamma" to "Γ", "\\Delta" to "Δ", "\\Theta" to "Θ",
        "\\Lambda" to "Λ", "\\Pi" to "Π", "\\Sigma" to "Σ", "\\Phi" to "Φ", "\\Omega" to "Ω",
        "\\times" to "×", "\\cdot" to "·", "\\pm" to "±", "\\leq" to "≤", "\\le" to "≤",
        "\\geq" to "≥", "\\ge" to "≥", "\\neq" to "≠", "\\approx" to "≈", "\\infty" to "∞",
        "\\to" to "→", "\\rightarrow" to "→", "\\leftarrow" to "←", "\\sum" to "∑", "\\prod" to "∏",
        "\\int" to "∫", "\\partial" to "∂", "\\nabla" to "∇", "\\in" to "∈", "\\notin" to "∉",
    )
    replacements.forEach { (source, target) -> out = out.replace(source, target) }
    out = out.replace("\\left", "").replace("\\right", "")

    val fraction = Regex("\\\\frac\\{([^{}]+)}\\{([^{}]+)}")
    repeat(4) { out = fraction.replace(out) { match -> "(${match.groupValues[1]})/(${match.groupValues[2]})" } }
    val sqrt = Regex("\\\\sqrt\\{([^{}]+)}")
    out = sqrt.replace(out) { match -> "√(${match.groupValues[1]})" }

    val superscript = mapOf(
        '0' to '⁰', '1' to '¹', '2' to '²', '3' to '³', '4' to '⁴', '5' to '⁵',
        '6' to '⁶', '7' to '⁷', '8' to '⁸', '9' to '⁹', '+' to '⁺', '-' to '⁻',
        '=' to '⁼', '(' to '⁽', ')' to '⁾', 'n' to 'ⁿ', 'i' to 'ⁱ',
    )
    val subscript = mapOf(
        '0' to '₀', '1' to '₁', '2' to '₂', '3' to '₃', '4' to '₄', '5' to '₅',
        '6' to '₆', '7' to '₇', '8' to '₈', '9' to '₉', '+' to '₊', '-' to '₋',
        '=' to '₌', '(' to '₍', ')' to '₎', 'a' to 'ₐ', 'e' to 'ₑ', 'h' to 'ₕ', 'i' to 'ᵢ',
        'j' to 'ⱼ', 'k' to 'ₖ', 'l' to 'ₗ', 'm' to 'ₘ', 'n' to 'ₙ', 'o' to 'ₒ', 'p' to 'ₚ',
        'r' to 'ᵣ', 's' to 'ₛ', 't' to 'ₜ', 'u' to 'ᵤ', 'v' to 'ᵥ', 'x' to 'ₓ',
    )
    fun convertScript(value: String, table: Map<Char, Char>): String? {
        val converted = value.map { table[it] ?: return null }
        return converted.joinToString("")
    }
    out = Regex("\\^\\{([^{}]+)}").replace(out) { m -> convertScript(m.groupValues[1], superscript) ?: "^(${m.groupValues[1]})" }
    out = Regex("_\\{([^{}]+)}").replace(out) { m -> convertScript(m.groupValues[1], subscript) ?: "_(${m.groupValues[1]})" }
    out = Regex("\\^([0-9n+-])").replace(out) { m -> superscript[m.groupValues[1][0]]?.toString() ?: m.value }
    out = Regex("_([0-9aehijklmnoprstuvx+-])").replace(out) { m -> subscript[m.groupValues[1][0]]?.toString() ?: m.value }
    return out
}
