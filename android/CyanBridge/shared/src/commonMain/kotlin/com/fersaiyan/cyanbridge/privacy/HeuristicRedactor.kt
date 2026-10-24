package com.fersaiyan.cyanbridge.privacy

/**
 * Chapter 8 MVP redaction.
 *
 * Intentionally simple heuristics (best-effort):
 * - redact obvious emails
 * - redact phone-like sequences
 * - redact 2+ consecutive capitalized words ("John Smith")
 *
 * Kept modular so we can swap to NER-based redaction later.
 */
class HeuristicRedactor(
    private val nameReplacement: String = "[REDACTED_NAME]",
    private val emailReplacement: String = "[REDACTED_EMAIL]",
    private val phoneReplacement: String = "[REDACTED_PHONE]",
) : Redactor {

    // Rough email regex; good enough for MVP tests.
    private val emailRegex = Regex("\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\\b")

    // Phone-like patterns. Keep it somewhat conservative; expect at least 9 digits total.
    private val phoneRegex = Regex("(?:(?:\\+?\\d{1,3}[\\s.-]?)?(?:\\(\\d{2,3}\\)[\\s.-]?)?\\d[\\d\\s().-]{7,}\\d)")

    // Two or three capitalized words, optionally with a title prefix.
    // Examples: "John Smith", "Mary Jane Watson", "Dr John Smith".
    private val nameRegex = Regex(
        "\\b(?:(?:Mr|Mrs|Ms|Dr|Prof)\\.?\\s+)?([A-Z][a-z]{1,}\\s+[A-Z][a-z]{1,}(?:\\s+[A-Z][a-z]{1,})?)\\b"
    )

    override fun redact(input: String): String {
        if (input.isBlank()) return input

        var out = input
        out = out.replace(emailRegex, emailReplacement)

        // Redact names before phone; keeps phone regex from matching within replacements.
        out = out.replace(nameRegex) { _ -> nameReplacement }

        out = out.replace(phoneRegex) { matchResult ->
            // Avoid redacting short sequences that are likely not phone numbers.
            val digits = matchResult.value.count { it.isDigit() }
            if (digits >= 9) phoneReplacement else matchResult.value
        }

        return out
    }
}
