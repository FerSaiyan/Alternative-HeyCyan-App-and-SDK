package com.fersaiyan.cyanbridge.privacy

/**
 * Small, swappable redaction interface.
 *
 * MVP uses regex/heuristics; later we can replace this with NER-based redaction.
 */
fun interface Redactor {
    fun redact(input: String): String
}
