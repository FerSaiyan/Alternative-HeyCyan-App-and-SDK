package com.fersaiyan.cyanbridge.shared.ocr

/**
 * PP-OCRv6 recognizer class list from a dictionary file's text (ocr_spec.json "dictionaries"): class 0 = CTC blank,
 * dictionary line i (1-based) = class i, and the last class = " " (U+0020, not in the file). Lines are never
 * trimmed: some are whitespace characters themselves (U+3000 at line 1749 of the small dictionary, 617 of the
 * tiny one). Only line terminators (LF or CRLF), the final newline and a leading byte-order mark are dropped.
 * Feed the result to `OcrText.ctcGreedy`.
 */
object OcrDictionary {
    const val BLANK = "<blank>"
    const val SPACE = " "

    /**
     * [recognizerClasses] = the recognizer's last output dimension, when known: the space class is appended when it
     * is null or one more than blank + lines (always the case for the cpu_gpu PP-OCRv6 models). Check
     * `size == recognizerClasses` afterwards to catch a dictionary that belongs to the other model.
     */
    fun classes(dictionaryText: String, recognizerClasses: Int? = null): List<String> {
        val chars = ArrayList<String>()
        chars += BLANK
        chars += lines(dictionaryText)
        if (recognizerClasses == null || recognizerClasses == chars.size + 1) chars += SPACE
        return chars
    }

    /** The dictionary's lines, untrimmed. */
    fun lines(dictionaryText: String): List<String> {
        val text = dictionaryText.removePrefix("﻿")
        if (text.isEmpty()) return emptyList()
        val lines = text.split('\n').map { it.removeSuffix("\r") }
        return if (text.endsWith('\n')) lines.dropLast(1) else lines
    }
}
