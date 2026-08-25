package com.fersaiyan.cyanbridge.localmodels.tts

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Incrementally turns model output into speech-sized phrases. It is deliberately locale-neutral:
 * boundaries come from Unicode punctuation and surrounding text rather than an abbreviation list.
 */
class MultilingualSpeechChunker(
    private val config: SpeechChunkingConfig = SpeechChunkingConfig(),
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default),
    private val onChunkReady: (sessionId: Long, text: String) -> Unit,
) {
    data class Metrics(
        var acceptedCandidateBoundaries: Int = 0,
        var rejectedCandidateBoundaries: Int = 0,
        var timeoutFlushes: Int = 0,
    )

    private data class Boundary(
        val punctuationIndex: Int,
        val endIndex: Int,
        val punctuation: Char,
    )

    private val buffer = StringBuilder()
    private val accumulatedCallbackText = StringBuilder()
    val metrics = Metrics()

    private var firstChunk = true
    private var activeSessionId = 0L
    private var bufferVersion = 0L
    private var candidateJob: Job? = null
    private var candidateEndIndex = -1
    private var idleJob: Job? = null

    @Synchronized
    fun startSession(sessionId: Long) {
        resetInternal()
        activeSessionId = sessionId
    }

    /**
     * Accepts either a delta or a cumulative callback. LiteRT and remote backends have historically
     * differed here, so only a previously unseen suffix is appended when possible.
     */
    @Synchronized
    fun append(rawInput: String, sessionId: Long = activeSessionId) {
        if (sessionId != activeSessionId || rawInput.isEmpty()) return
        val delta = extractNewTextDelta(rawInput)
        if (delta.isEmpty()) return

        buffer.append(delta)
        bufferVersion++
        ensureIdleTimer()
        evaluateBuffer(forceFlush = false)
    }

    @Synchronized
    fun finish(sessionId: Long = activeSessionId) {
        if (sessionId != activeSessionId) return
        cancelTimers()
        flushRemaining()
    }

    @Synchronized
    fun reset() {
        resetInternal()
        activeSessionId = 0L
    }

    private fun extractNewTextDelta(input: String): String {
        val previous = accumulatedCallbackText.toString()
        return when {
            previous.isEmpty() -> input.also(accumulatedCallbackText::append)
            input == previous || previous.startsWith(input) -> ""
            input.startsWith(previous) -> input.substring(previous.length).also(accumulatedCallbackText::append)
            else -> input.also(accumulatedCallbackText::append)
        }
    }

    private fun resetInternal() {
        cancelTimers()
        buffer.clear()
        accumulatedCallbackText.clear()
        firstChunk = true
        bufferVersion++
        candidateEndIndex = -1
        metrics.acceptedCandidateBoundaries = 0
        metrics.rejectedCandidateBoundaries = 0
        metrics.timeoutFlushes = 0
    }

    private fun cancelTimers() {
        candidateJob?.cancel()
        candidateJob = null
        idleJob?.cancel()
        idleJob = null
    }

    /** One idle worker per active session, rather than one coroutine per model fragment. */
    private fun ensureIdleTimer() {
        if (idleJob?.isActive == true) return
        val sessionId = activeSessionId
        var observedVersion = bufferVersion
        idleJob = scope.launch {
            while (true) {
                delay(if (firstChunk) config.firstChunkIdleFlushMs else config.normalChunkIdleFlushMs)
                synchronized(this@MultilingualSpeechChunker) {
                    if (sessionId != activeSessionId) return@launch
                    if (observedVersion == bufferVersion) {
                        if (buffer.isNotEmpty()) {
                            metrics.timeoutFlushes++
                            evaluateBuffer(forceFlush = true)
                        }
                        return@launch
                    }
                    observedVersion = bufferVersion
                }
            }
        }
    }

    private fun evaluateBuffer(forceFlush: Boolean) {
        val text = buffer.toString()
        if (text.isBlank()) return

        val minCodePoints = if (firstChunk) config.firstChunkMinCodePoints else config.normalChunkMinCodePoints
        val currentCodePoints = codePointCount(text)
        if (currentCodePoints >= config.hardChunkMaxCodePoints) {
            flushAt(findBestSplitIndex(text, config.preferredChunkMaxCodePoints))
            return
        }

        for (boundary in strongBoundaries(text)) {
            when (boundaryDisposition(text, boundary, forceFlush)) {
                BoundaryDisposition.CONFIRMED -> {
                    metrics.acceptedCandidateBoundaries++
                    flushAt(boundary.endIndex)
                    return
                }
                BoundaryDisposition.WAIT -> {
                    scheduleCandidateConfirmation(boundary)
                    return
                }
                BoundaryDisposition.INTERNAL -> {
                    metrics.rejectedCandidateBoundaries++
                }
            }
        }

        // The first audible output optimizes for latency rather than perfect sentence prosody.
        // Once a useful short clause has arrived, a comma/colon/semicolon/dash is a safe place to
        // start speaking while the model continues generating the rest of the answer.
        if (firstChunk) {
            findLastSoftBoundary(text)?.let { boundary ->
                val prefix = text.substring(0, boundary.endIndex)
                if (codePointCount(prefix) >= minCodePoints) {
                    flushAt(boundary.endIndex)
                    return
                }
            }
        }

        val preferredMax = if (firstChunk) {
            config.firstChunkPreferredMaxCodePoints
        } else {
            config.preferredChunkMaxCodePoints
        }
        if (currentCodePoints >= preferredMax) {
            findLastSoftBoundary(text)?.let {
                flushAt(it.endIndex)
                return
            }
        }

        if (forceFlush && currentCodePoints >= minCodePoints) {
            flushRemaining()
        }
    }

    private enum class BoundaryDisposition { CONFIRMED, WAIT, INTERNAL }

    private fun boundaryDisposition(text: String, boundary: Boundary, forceFlush: Boolean): BoundaryDisposition {
        val punctuation = boundary.punctuation
        if (punctuation == '。' || punctuation == '！' || punctuation == '？' || punctuation == '\n' || punctuation == '\r') {
            return BoundaryDisposition.CONFIRMED
        }
        if (punctuation == '!' || punctuation == '?') {
            return BoundaryDisposition.CONFIRMED
        }
        if (punctuation != '.') return BoundaryDisposition.INTERNAL

        val next = text.getOrNull(boundary.endIndex)
        if (next == null) return if (forceFlush) BoundaryDisposition.CONFIRMED else BoundaryDisposition.WAIT
        if (next.isLetterOrDigit()) return BoundaryDisposition.INTERNAL // decimal, version, URL, filename, acronym
        if (isAcronymOrDottedIdentifier(text, boundary.punctuationIndex)) return BoundaryDisposition.INTERNAL
        if (isNumberedListMarker(text, boundary.punctuationIndex)) return BoundaryDisposition.INTERNAL

        val nextContent = nextNonWhitespace(text, boundary.endIndex)
            ?: return if (forceFlush) BoundaryDisposition.CONFIRMED else BoundaryDisposition.WAIT
        val wordLength = wordLengthBefore(text, boundary.punctuationIndex)
        // A short leading token before a capitalized name is more likely an initial/title than a
        // sentence. This is script-neutral and only defers; the later real boundary still wins.
        if (wordLength in 1..3 && nextContent.isUpperCase()) return BoundaryDisposition.INTERNAL
        return BoundaryDisposition.CONFIRMED
    }

    private fun scheduleCandidateConfirmation(boundary: Boundary) {
        if (candidateEndIndex == boundary.endIndex && candidateJob?.isActive == true) return
        candidateJob?.cancel()
        candidateEndIndex = boundary.endIndex
        val sessionId = activeSessionId
        candidateJob = scope.launch {
            delay(config.candidateBoundaryDelayMs)
            synchronized(this@MultilingualSpeechChunker) {
                if (sessionId != activeSessionId || buffer.length < boundary.endIndex) return@synchronized
                candidateJob = null
                candidateEndIndex = -1

                val noLaterText = buffer.length == boundary.endIndex
                val minCodePoints = if (firstChunk) config.firstChunkMinCodePoints else config.normalChunkMinCodePoints
                if (noLaterText && codePointCount(buffer.toString()) < minCodePoints) {
                    // Tiny fragments such as "Dr." are too ambiguous to speak immediately.
                    return@synchronized
                }

                // Re-evaluate with later context when available. If the model paused at a useful
                // sentence boundary, confirm it after the short candidate delay instead of waiting
                // for the much longer idle flush.
                evaluateBuffer(forceFlush = noLaterText)
            }
        }
    }

    private fun strongBoundaries(text: String): List<Boundary> {
        val boundaries = ArrayList<Boundary>()
        for (index in text.indices) {
            val c = text[index]
            if (!isStrongPunctuation(c)) continue
            var end = index + 1
            while (end < text.length && isClosingPunctuation(text[end])) end++
            boundaries += Boundary(index, end, c)
        }
        return boundaries
    }

    private fun findLastSoftBoundary(text: String): Boundary? {
        for (index in text.indices.reversed()) {
            if (!isSoftPunctuation(text[index])) continue
            var end = index + 1
            while (end < text.length && isClosingPunctuation(text[end])) end++
            return Boundary(index, end, text[index])
        }
        return null
    }

    private fun flushAt(index: Int) {
        if (index <= 0) return
        emitChunk(buffer.substring(0, index))
        buffer.delete(0, index)
        bufferVersion++
        candidateJob?.cancel()
        candidateJob = null
        candidateEndIndex = -1
        if (buffer.isNotEmpty()) evaluateBuffer(forceFlush = false)
    }

    private fun flushRemaining() {
        emitChunk(buffer.toString())
        buffer.clear()
        bufferVersion++
    }

    private fun emitChunk(rawChunk: String) {
        val chunk = rawChunk.trim()
        if (chunk.isEmpty()) return
        firstChunk = false
        onChunkReady(activeSessionId, chunk)
    }

    private fun findBestSplitIndex(text: String, preferredCodePoints: Int): Int {
        val limit = text.offsetByCodePoints(0, preferredCodePoints.coerceAtMost(codePointCount(text)))
        for (index in limit - 1 downTo 0) {
            if (isStrongPunctuation(text[index]) || isSoftPunctuation(text[index])) return index + 1
        }
        for (index in limit - 1 downTo 0) {
            if (text[index].isWhitespace()) return index + 1
        }
        return limit
    }

    private fun isAcronymOrDottedIdentifier(text: String, periodIndex: Int): Boolean {
        var start = periodIndex - 1
        while (start >= 0 && !text[start].isWhitespace()) start--
        val token = text.substring(start + 1, periodIndex + 1)
        return token.count { it == '.' } >= 2 || token.contains('@') || token.contains('/')
    }

    private fun isNumberedListMarker(text: String, periodIndex: Int): Boolean {
        var start = periodIndex - 1
        while (start >= 0 && text[start].isDigit()) start--
        return start + 1 < periodIndex && (start < 0 || text[start].isWhitespace())
    }

    private fun wordLengthBefore(text: String, periodIndex: Int): Int {
        var start = periodIndex - 1
        while (start >= 0 && text[start].isLetter()) start--
        return periodIndex - start - 1
    }

    private fun nextNonWhitespace(text: String, start: Int): Char? {
        for (index in start until text.length) {
            if (!text[index].isWhitespace()) return text[index]
        }
        return null
    }

    private fun isStrongPunctuation(c: Char) = c in charArrayOf('.', '!', '?', '。', '！', '？', '\n', '\r')
    private fun isSoftPunctuation(c: Char) = c in charArrayOf(',', ';', ':', '，', '；', '：', '—', '–')
    private fun isClosingPunctuation(c: Char) = c in charArrayOf('"', '\'', ')', ']', '}', '》', '」', '』', '”', '’')
    private fun codePointCount(text: String) = text.codePointCount(0, text.length)
}
