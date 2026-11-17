package com.fersaiyan.cyanbridge.shared.diagnostics

/**
 * Removes personal data and secrets from text that leaves the phone in a support report
 * (LGPD: data minimization). Applied to every logcat line, event, stack trace and free text.
 *
 * - Emails, bearer tokens, API keys (OpenAI, Google, Meta client tokens, relay tokens, JWTs),
 *   `key=value` secrets and long random-looking strings become placeholders.
 * - URL query strings are dropped (they often carry keys); the host and path stay.
 * - Bluetooth MAC addresses keep their last two bytes only. IP addresses stay: the app only logs
 *   LAN and Wi-Fi Direct addresses, which sync diagnosis needs (versions look like IPs too).
 * - Content-bearing `key: value` pairs (transcript, question, answer, prompt, text, message, title,
 *   …) keep only the value's length.
 */
object DiagnosticRedactor {
    const val VERSION = 1

    data class Result(val text: String, val count: Int)

    private class Rule(val regex: Regex, val replace: (MatchResult) -> String)

    private val CONTENT_KEYS =
        "transcript|transcription|question|answer|prompt|reply|response_text|utterance|caption|said|heard|" +
            "text|message|msg|body|title|content|query|notification|contact|sender|recipient|subject|ocr"

    private val rules: List<Rule> = listOf(
        // Content-bearing key/value pairs, quoted or to the end of the line.
        Rule(Regex("""(?i)\b($CONTENT_KEYS)(\s*[=:]\s*)"([^"\n]*)"""")) { m ->
            "${m.groupValues[1]}${m.groupValues[2]}<${m.groupValues[3].length} chars>"
        },
        Rule(Regex("""(?i)\b($CONTENT_KEYS)(\s*[=:]\s*)([^\s"<][^\n]*)""")) { m ->
            "${m.groupValues[1]}${m.groupValues[2]}<${m.groupValues[3].length} chars>"
        },
        Rule(Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""")) { "<email>" },
        Rule(Regex("""(?i)\bbearer\s+[A-Za-z0-9._~+/=-]{8,}""")) { "Bearer <token>" },
        Rule(Regex("""eyJ[A-Za-z0-9_-]{5,}\.[A-Za-z0-9_-]{5,}\.[A-Za-z0-9_-]{5,}""")) { "<jwt>" },
        Rule(Regex("""\bsk-[A-Za-z0-9_-]{16,}""")) { "<api-key>" },
        Rule(Regex("""\bAIza[0-9A-Za-z_-]{30,}""")) { "<api-key>" },
        Rule(Regex("""\bAR\|\d+\|[0-9a-fA-F]{16,}""")) { "<client-token>" },
        Rule(Regex("""\b(gh[pousr]_)[A-Za-z0-9]{20,}""")) { "<github-token>" },
        Rule(Regex("""\brelay_[A-Za-z0-9_-]{30,}""")) { "relay_<token>" },
        Rule(Regex("""(?i)\b(api[_-]?key|apikey|access[_-]?token|refresh[_-]?token|id[_-]?token|token|secret|password|passwd|pwd|authorization|cookie|session[_-]?id|purchase[_-]?token|otp|verification[_-]?code)(\s*[=:]\s*)"?[^\s",;&)}\]]{4,}"?""")) { m ->
            "${m.groupValues[1]}${m.groupValues[2]}<redacted>"
        },
        Rule(Regex("""(https?://[^\s?#"']+)\?[^\s"'#]*""")) { m -> "${m.groupValues[1]}?<query>" },
        Rule(Regex("""\b(?:[0-9A-Fa-f]{2}[:-]){5}([0-9A-Fa-f]{2})\b""")) { m ->
            val parts = m.value.split(':', '-')
            "XX:XX:XX:XX:${parts[4]}:${parts[5]}".uppercase()
        },
        // Long random-looking runs (tokens, keys) that no rule above named. Requires letters and digits.
        Rule(Regex("""[A-Za-z0-9_+/=-]{40,}""")) { m ->
            val v = m.value
            if (v.any(Char::isDigit) && v.any(Char::isUpperCase) && v.any(Char::isLowerCase)) "<secret:${v.length}>" else v
        },
    )

    fun redact(text: String): Result {
        if (text.isEmpty()) return Result(text, 0)
        var count = 0
        var current = text
        for (rule in rules) {
            current = rule.regex.replace(current) { match ->
                val replacement = rule.replace(match)
                if (replacement != match.value) count++
                replacement
            }
        }
        return Result(current, count)
    }

    fun redactText(text: String): String = redact(text).text
}
