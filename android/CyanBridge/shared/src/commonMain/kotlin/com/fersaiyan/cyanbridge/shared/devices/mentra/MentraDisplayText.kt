package com.fersaiyan.cyanbridge.shared.devices.mentra

/**
 * How much text one screen of a Mentra SDK display holds. The SDK sends `displayText` as is (the
 * Mentra app wraps it first with per-model font tables), so CyanBridge wraps and pages it here.
 * [columns] counts narrow characters (wide CJK characters count 2) and is set below Mentra's
 * average glyph width per line so mixed text does not overflow; [maxBytes] is the per-message
 * UTF-8 budget of the model's text command.
 */
data class MentraDisplayLimits(val columns: Int, val lines: Int, val maxBytes: Int)

/** Wrapping and pagination of plain text for [MentraDisplayLimits]. */
object MentraDisplayText {
    /**
     * Word-wraps [text] to [columns]: explicit line breaks are kept, whitespace runs collapse, empty
     * lines are dropped (the screens are a few lines tall) and words longer than a line are split.
     */
    fun wrap(text: String, columns: Int): List<String> {
        require(columns > 0) { "columns must be positive" }
        val lines = mutableListOf<String>()
        text.replace("\r\n", "\n").replace('\r', '\n').split('\n').forEach { paragraph ->
            var line = StringBuilder()
            var lineWidth = 0
            fun flush() {
                if (line.isNotEmpty()) lines += line.toString()
                line = StringBuilder()
                lineWidth = 0
            }
            paragraph.split(' ', '\t').filter { it.isNotEmpty() }.forEach { word ->
                val wordWidth = width(word)
                val needed = if (lineWidth == 0) wordWidth else lineWidth + 1 + wordWidth
                when {
                    needed <= columns -> {
                        if (lineWidth > 0) line.append(' ')
                        line.append(word)
                        lineWidth = needed
                    }
                    wordWidth <= columns -> {
                        flush()
                        line.append(word)
                        lineWidth = wordWidth
                    }
                    else -> {
                        flush()
                        // Every piece but the last fills its own line; the last one stays open.
                        splitWord(word, columns).forEachIndexed { index, piece ->
                            if (index > 0) flush()
                            line.append(piece)
                            lineWidth = width(piece)
                        }
                    }
                }
            }
            flush()
        }
        return lines
    }

    /** [text] as screens of at most [MentraDisplayLimits.lines] lines and [MentraDisplayLimits.maxBytes] bytes. */
    fun pages(text: String, limits: MentraDisplayLimits): List<String> {
        val pages = mutableListOf<String>()
        val page = mutableListOf<String>()
        var pageBytes = 0
        wrap(text, limits.columns).forEach { line ->
            val lineBytes = line.encodeToByteArray().size
            val bytesWithLine = if (page.isEmpty()) lineBytes else pageBytes + 1 + lineBytes
            if (page.isNotEmpty() && (page.size == limits.lines || bytesWithLine > limits.maxBytes)) {
                pages += page.joinToString("\n")
                page.clear()
                pageBytes = 0
            }
            pageBytes = if (page.isEmpty()) lineBytes else pageBytes + 1 + lineBytes
            page += line
        }
        if (page.isNotEmpty()) pages += page.joinToString("\n")
        return pages
    }

    /** How long a page stays before the next one replaces it: a reading pace, within 3 to 12 s. */
    fun dwellMs(page: String): Long = (2_000L + 55L * page.length).coerceIn(3_000L, 12_000L)

    private fun splitWord(word: String, columns: Int): List<String> {
        val pieces = mutableListOf<String>()
        var piece = StringBuilder()
        var pieceWidth = 0
        var index = 0
        while (index < word.length) {
            // Keep surrogate pairs together.
            val end = if (word[index].isHighSurrogate() && index + 1 < word.length) index + 2 else index + 1
            val unit = word.substring(index, end)
            val unitWidth = width(unit)
            if (pieceWidth + unitWidth > columns && piece.isNotEmpty()) {
                pieces += piece.toString()
                piece = StringBuilder()
                pieceWidth = 0
            }
            piece.append(unit)
            pieceWidth += unitWidth
            index = end
        }
        if (piece.isNotEmpty()) pieces += piece.toString()
        return pieces
    }

    private fun width(text: String): Int = text.sumOf { if (isWide(it)) 2 else 1 }

    /** East Asian wide characters, which the display fonts draw about twice as wide as Latin. */
    private fun isWide(char: Char): Boolean {
        val code = char.code
        return code in 0x1100..0x115F ||
            code in 0x2E80..0xA4CF ||
            code in 0xAC00..0xD7A3 ||
            code in 0xF900..0xFAFF ||
            code in 0xFE30..0xFE4F ||
            code in 0xFF00..0xFF60 ||
            code in 0xFFE0..0xFFE6
    }
}
