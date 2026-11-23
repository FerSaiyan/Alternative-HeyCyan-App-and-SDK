package com.fersaiyan.cyanbridge.plugins.walkingaid.ocr

/**
 * Text side of the Walking Aid reader (bus signs, car plates): CTC decoding, Brazilian plate format rules and the
 * "is this the one I'm waiting for?" matching. Ported from the OCR bench (/mnt/seagate/ML/ocr-bench, scripts/ocrlib.py
 * and platefmt.py, 2026-10-28), where target matching was what made reads reliable:
 * - plates: PP-OCRv6 small det+rec on the plate crop + format rules read 85.6 % of Mercosul plates exactly; an exact
 *   match after the format rules accepted another plate 0-0.5 % of the time, a 1-edit match 87-89 %, so plates need
 *   an exact match voted across frames;
 * - bus signs: the destination was read ("contains") in 79-80 % of signs.
 */
object OcrText {
    /**
     * Greedy CTC over [probs] (T x C, row-major; probabilities or logits). [chars] maps class index -> text, index 0
     * being the blank. [allowed] restricts decoding to those classes (plus blank), e.g. plate characters.
     */
    fun ctcGreedy(probs: FloatArray, classes: Int, chars: List<String>, allowed: BooleanArray? = null): Pair<String, Float> {
        val steps = probs.size / classes
        val out = StringBuilder()
        var confSum = 0f
        var confCount = 0
        var prev = -1
        for (t in 0 until steps) {
            var best = -1
            var bestValue = Float.NEGATIVE_INFINITY
            val base = t * classes
            for (c in 0 until classes) {
                if (allowed != null && c != 0 && !allowed[c]) continue
                val v = probs[base + c]
                if (v > bestValue) { bestValue = v; best = c }
            }
            if (best != prev && best != 0 && best in chars.indices) {
                out.append(chars[best])
                confSum += bestValue
                confCount++
            }
            prev = best
        }
        return out.toString() to (if (confCount > 0) confSum / confCount else 0f)
    }

    /** Plate characters (letters, digits and the separators plates print; separators are stripped later). */
    fun plateClassMask(chars: List<String>): BooleanArray =
        BooleanArray(chars.size) { i -> i == 0 || chars[i].length == 1 && chars[i][0].let { it.isLetterOrDigit() && it.code < 128 || it in "·-.: " } }

    fun normalize(text: String): String = text.uppercase().filter { it in 'A'..'Z' || it in '0'..'9' }

    private val LETTER_TO_DIGIT = mapOf('O' to '0', 'I' to '1', 'B' to '8', 'S' to '5', 'Z' to '2', 'G' to '6')
    private val DIGIT_TO_LETTER = LETTER_TO_DIGIT.entries.associate { (k, v) -> v to k }
    private val PLATE_PATTERNS = listOf("LLLNNNN", "LLLNLNN") // old Brazilian, Mercosul

    /**
     * The best 7-character window fitted to the old LLLNNNN or Mercosul LLLNLNN pattern, fixing O/0 I/1 B/8 S/5 Z/2
     * G/6 by position. Ties prefer the lower cost, then the rightmost window (the number follows the BRASIL band).
     */
    fun plateFormatFix(text: String): String {
        val s = normalize(text)
        if (s.length < 7) return s
        var best: Triple<Int, Int, String>? = null
        for (i in 0..s.length - 7) {
            for (pattern in PLATE_PATTERNS) {
                val fitted = fit(s.substring(i, i + 7), pattern) ?: continue
                val key = fitted.first to -i
                if (best == null || key.first < best.first || key.first == best.first && key.second < best.second) {
                    best = Triple(key.first, key.second, fitted.second)
                }
            }
        }
        return best?.third ?: s
    }

    private fun fit(window: String, pattern: String): Pair<Int, String>? {
        val out = StringBuilder()
        var cost = 0
        for ((ch, p) in window.zip(pattern)) {
            if (p == 'L') {
                when {
                    ch.isLetter() -> out.append(ch)
                    ch in DIGIT_TO_LETTER -> { out.append(DIGIT_TO_LETTER.getValue(ch)); cost++ }
                    else -> return null
                }
            } else {
                when {
                    ch.isDigit() -> out.append(ch)
                    ch in LETTER_TO_DIGIT -> { out.append(LETTER_TO_DIGIT.getValue(ch)); cost++ }
                    else -> return null
                }
            }
        }
        return cost to out.toString()
    }

    fun editDistance(a: String, b: String): Int {
        if (a == b) return 0
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) {
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            }
            prev = cur
        }
        return prev[b.length]
    }

    /** Minimum edit distance between [target] and any substring of [text] (semi-global alignment). */
    fun substringDistance(target: String, text: String): Int {
        if (target.isEmpty()) return 0
        var prev = IntArray(text.length + 1)
        for (i in 1..target.length) {
            val cur = IntArray(text.length + 1)
            cur[0] = i
            for (j in 1..text.length) {
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (target[i - 1] == text[j - 1]) 0 else 1)
            }
            prev = cur
        }
        return prev.min()
    }

    /** A plate read matches the expected plate only exactly after the format rules (see the class comment). */
    fun plateMatches(read: String, target: String): Boolean {
        val t = normalize(target)
        return t.length >= 6 && plateFormatFix(read) == plateFormatFix(t)
    }

    /**
     * A bus sign mentions [target] (route number or destination words): route numbers must appear exactly as a
     * token; destination text within max(1, 15 %) edits of a substring, as scored in the bench.
     */
    fun busSignMatches(read: String, target: String): Boolean {
        val t = normalize(target)
        if (t.isEmpty()) return false
        val r = normalize(read)
        if (t.all { it.isDigit() } || t.length <= 4) {
            return Regex("[A-Z0-9]+").findAll(read.uppercase()).any { normalize(it.value) == t }
        }
        return substringDistance(t, r) <= maxOf(1, Math.round(0.15f * t.length))
    }

    /** Majority read over the last frames: returns the read seen at least [minVotes] times, if any. */
    fun vote(reads: List<String>, minVotes: Int = 2): String? =
        reads.filter { it.isNotBlank() }.groupingBy { it }.eachCount().entries
            .filter { it.value >= minVotes }.maxByOrNull { it.value }?.key
}
