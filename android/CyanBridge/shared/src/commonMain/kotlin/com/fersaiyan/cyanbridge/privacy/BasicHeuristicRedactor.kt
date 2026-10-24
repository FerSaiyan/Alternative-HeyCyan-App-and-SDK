package com.fersaiyan.cyanbridge.privacy

/**
 * MVP redaction: best-effort heuristic rules.
 *
 * Goals:
 * - Reduce obvious identifiers in exports (names/emails/phone numbers).
 * - Keep implementation simple and easily replaceable.
 */
class BasicHeuristicRedactor(
    private val redactNames: Boolean = true,
    private val redactEmails: Boolean = true,
    private val redactPhones: Boolean = true,
) : Redactor {

    override fun redact(input: String): String {
        var out = input
        if (redactEmails) out = out.redactEmails()
        if (redactPhones) out = out.redactPhones()
        if (redactNames) out = out.redactLikelyNames()
        return out
    }

    private fun String.redactEmails(): String {
        // Very small email heuristic.
        val emailRegex = Regex("(?i)\\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}\\b")
        return replace(emailRegex, "[REDACTED_EMAIL]")
    }

    private fun String.redactPhones(): String {
        // Best-effort phone number heuristic:
        // - requires at least 8 digits overall
        // - allows spaces, parentheses, plus, hyphens
        val phoneRegex = Regex("\\b(\\+?\\d[\\d\\s().-]{6,}\\d)\\b")
        return replace(phoneRegex) { "[REDACTED_PHONE]" }
    }

    private fun String.redactLikelyNames(): String {
        // Best-effort name heuristic:
        // - redact sequences of 2-3 capitalized words, e.g. "John Doe" / "Maria Clara Silva".
        // - avoids single capitalized words (too many false positives).
        // - keep a tiny allow-list to reduce accidental redaction of common note headings.
        val headingAllowList = setOf(
            "Action Items",
            "Key Decisions",
            "Open Questions",
            "Timeline Highlights",
            "Bullet Summary",
        )

        val nameRegex = Regex("\\b([A-Z][a-z]{2,})(\\s+([A-Z][a-z]{2,})){1,2}\\b")
        return replace(nameRegex) { m ->
            val value = m.value
            if (headingAllowList.contains(value)) value else "[REDACTED_NAME]"
        }
    }
}

