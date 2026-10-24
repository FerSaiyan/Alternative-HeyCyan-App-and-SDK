package com.fersaiyan.cyanbridge.shared.speech

/**
 * Splits a streamed LLM reply into speakable chunks (Android: MultilingualSpeechChunker with
 * SpeechChunkingConfig defaults): a short first clause as early as possible, then larger chunks
 * at sentence boundaries. Text inside <think>…</think> is never spoken. Timers stay with the
 * caller, which calls [flushIdle] after a pause in tokens and [finish] at the end.
 */
class StreamingSpeechChunker(private val emit: (String) -> Unit) {
    private val buffer = StringBuilder()
    private var raw = ""
    private var consumed = 0
    var chunksEmitted = 0
        private set

    val isFirstChunkPending: Boolean get() = chunksEmitted == 0

    fun append(delta: String) {
        raw += delta
        val visible = visibleText(raw)
        if (visible.length <= consumed) return
        buffer.append(visible.substring(consumed))
        consumed = visible.length
        evaluate(forceFlush = false)
    }

    /** Called after an idle pause: speaks a pending clause if it is long enough. */
    fun flushIdle() = evaluate(forceFlush = buffer.trim().length >= FIRST_MIN)

    fun finish() = evaluate(forceFlush = true)

    fun reset() {
        buffer.clear()
        raw = ""
        consumed = 0
        chunksEmitted = 0
    }

    private fun evaluate(forceFlush: Boolean) {
        while (true) {
            val text = buffer.toString()
            if (text.isBlank()) {
                buffer.clear()
                return
            }
            val first = chunksEmitted == 0
            val minLength = if (first) FIRST_MIN else NORMAL_MIN
            val preferredMax = if (first) FIRST_PREFERRED_MAX else PREFERRED_MAX
            val split = strongBoundary(text, minLength)
                ?: (if (text.length >= preferredMax) softBoundary(text, minLength) else null)
                ?: (if (text.length >= HARD_MAX) text.lastIndexOf(' ', HARD_MAX).takeIf { it >= minLength } else null)
            when {
                split != null -> emitUpTo(split + 1)
                forceFlush -> {
                    emitUpTo(text.length)
                    return
                }
                else -> return
            }
        }
    }

    private fun emitUpTo(end: Int) {
        val chunk = buffer.substring(0, end).replace("*", "").replace("#", "").trim()
        buffer.deleteRange(0, end)
        if (chunk.isEmpty()) return
        chunksEmitted++
        emit(chunk)
    }

    /** Sentence end (. ! ? or newline) followed by whitespace, at or after [minLength] chars. */
    private fun strongBoundary(text: String, minLength: Int): Int? {
        for (i in (minLength - 1).coerceAtLeast(0) until text.length) {
            val c = text[i]
            if (c == '\n' || c in "。！？") return i
            val next = text.getOrNull(i + 1)
            if (c in ".!?" && next != null && next.isWhitespace() && !isNumberOrInitial(text, i)) return i
        }
        return null
    }

    private fun softBoundary(text: String, minLength: Int): Int? =
        (text.length - 2 downTo minLength).firstOrNull { i ->
            text[i] in ",;:，；：—–" && text[i + 1].isWhitespace()
        }

    /** "3." list markers and single-letter initials ("J.") are not sentence ends. */
    private fun isNumberOrInitial(text: String, index: Int): Boolean {
        val word = text.substring(0, index).takeLastWhile { !it.isWhitespace() }
        return word.isNotEmpty() && (word.all { it.isDigit() } || (word.length == 1 && word[0].isUpperCase()))
    }

    companion object {
        const val FIRST_MIN = 12
        const val NORMAL_MIN = 35
        const val FIRST_PREFERRED_MAX = 52
        const val PREFERRED_MAX = 120
        const val HARD_MAX = 180
        const val FIRST_IDLE_FLUSH_MS = 250L
        const val NORMAL_IDLE_FLUSH_MS = 800L

        fun visibleText(raw: String): String =
            raw.replace(Regex("<think>[\\s\\S]*?</think>"), "").substringBefore("<think>").trimStart()
    }
}
