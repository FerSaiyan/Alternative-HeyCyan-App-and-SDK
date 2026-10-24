package com.fersaiyan.cyanbridge.shared.json

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Parsing and readers with the same tolerance and fallbacks as Android's org.json, so
 * code moved out of the app reads model and server output as before: lenient syntax
 * (see [OrgJsonReader]), numbers given as strings, `null` read as the text "null",
 * nested values read as their JSON text.
 */

/** Parses [text] like `JSONObject(text)`; throws [JsonSyntaxException] when it is not an object. */
fun parseJsonObject(text: String): JsonObject =
    OrgJsonReader(text).nextValue() as? JsonObject
        ?: throw JsonSyntaxException("Value is not a JSON object: ${text.take(200)}")

/** Parses [text] like `JSONArray(text)`; throws [JsonSyntaxException] when it is not an array. */
fun parseJsonArray(text: String): JsonArray =
    OrgJsonReader(text).nextValue() as? JsonArray
        ?: throw JsonSyntaxException("Value is not a JSON array: ${text.take(200)}")

/** Parses any value like `JSONTokener(text).nextValue()`. */
fun parseJsonValue(text: String): JsonElement = OrgJsonReader(text).nextValue()

/** org.json's string form of a value: strings as-is, `null` as "null", objects and arrays as JSON. */
fun JsonElement.asOptString(): String = when (this) {
    is JsonPrimitive -> content
    else -> toString()
}

fun JsonObject.optString(key: String, fallback: String = ""): String =
    this[key]?.asOptString() ?: fallback

fun JsonObject.optInt(key: String, fallback: Int = 0): Int = this[key].toIntOrNull() ?: fallback

fun JsonObject.optLong(key: String, fallback: Long = 0L): Long = this[key].toLongOrNull() ?: fallback

fun JsonObject.optDouble(key: String, fallback: Double = Double.NaN): Double =
    this[key].toDoubleOrNull() ?: fallback

fun JsonObject.optBoolean(key: String, fallback: Boolean = false): Boolean =
    this[key].toBooleanOrNull() ?: fallback

fun JsonObject.optJsonObject(key: String): JsonObject? = this[key] as? JsonObject

fun JsonObject.optJsonArray(key: String): JsonArray? = this[key] as? JsonArray

fun JsonArray.optJsonObject(index: Int): JsonObject? = getOrNull(index) as? JsonObject

fun JsonArray.optString(index: Int, fallback: String = ""): String =
    getOrNull(index)?.asOptString() ?: fallback

private fun JsonElement?.numberPrimitive(): JsonPrimitive? =
    (this as? JsonPrimitive)?.takeUnless { it is JsonNull }

fun JsonElement?.toIntOrNull(): Int? {
    val primitive = numberPrimitive() ?: return null
    if (primitive.isString) return primitive.content.trim().toDoubleOrNull()?.toInt()
    return primitive.content.toLongOrNull()?.toInt() ?: primitive.content.toDoubleOrNull()?.toInt()
}

fun JsonElement?.toLongOrNull(): Long? {
    val primitive = numberPrimitive() ?: return null
    if (primitive.isString) return primitive.content.trim().toDoubleOrNull()?.toLong()
    return primitive.content.toLongOrNull() ?: primitive.content.toDoubleOrNull()?.toLong()
}

fun JsonElement?.toDoubleOrNull(): Double? {
    val primitive = numberPrimitive() ?: return null
    return if (primitive.isString) primitive.content.trim().toDoubleOrNull() else primitive.content.toDoubleOrNull()
}

fun JsonElement?.toBooleanOrNull(): Boolean? {
    val primitive = numberPrimitive() ?: return null
    return when {
        primitive.content.equals("true", ignoreCase = true) -> true
        primitive.content.equals("false", ignoreCase = true) -> false
        else -> null
    }
}

/** The value's string form, or null when it is missing or JSON null. */
fun JsonObject.optStringOrNull(key: String): String? =
    this[key]?.takeUnless { it is JsonNull }?.asOptString()

/**
 * Text as org.json's `toString(indentSpaces)` wrote it (`"/"` escaped, whole doubles
 * without ".0", `": "` after keys when indented), for prompts that embed JSON.
 */
fun JsonElement.toOrgJsonString(indentSpaces: Int = 0): String =
    StringBuilder().also { writeOrgJson(this, it, indentSpaces, 0) }.toString()

private fun writeOrgJson(element: JsonElement, out: StringBuilder, indent: Int, depth: Int) {
    fun newline(level: Int) {
        if (indent <= 0) return
        out.append('\n')
        repeat(indent * level) { out.append(' ') }
    }
    when (element) {
        is JsonObject -> {
            out.append('{')
            element.entries.forEachIndexed { index, (key, value) ->
                if (index > 0) out.append(',')
                newline(depth + 1)
                writeOrgJsonString(key, out)
                out.append(if (indent > 0) ": " else ":")
                writeOrgJson(value, out, indent, depth + 1)
            }
            if (element.isNotEmpty()) newline(depth)
            out.append('}')
        }
        is JsonArray -> {
            out.append('[')
            element.forEachIndexed { index, value ->
                if (index > 0) out.append(',')
                newline(depth + 1)
                writeOrgJson(value, out, indent, depth + 1)
            }
            if (element.isNotEmpty()) newline(depth)
            out.append(']')
        }
        is JsonNull -> out.append("null")
        is JsonPrimitive -> when {
            element.isString -> writeOrgJsonString(element.content, out)
            element.content.toLongOrNull() != null -> out.append(element.content)
            else -> {
                val number = element.content.toDoubleOrNull()
                if (number != null && number == number.toLong().toDouble()) out.append(number.toLong()) else out.append(element.content)
            }
        }
    }
}

private fun writeOrgJsonString(value: String, out: StringBuilder) {
    out.append('"')
    for (c in value) {
        when (c) {
            '"', '\\', '/' -> out.append('\\').append(c)
            '\t' -> out.append("\\t")
            '\b' -> out.append("\\b")
            '\n' -> out.append("\\n")
            '\r' -> out.append("\\r")
            '\u000C' -> out.append("\\f")
            else -> if (c.code <= 0x1F) out.append("\\u").append(c.code.toString(16).padStart(4, '0')) else out.append(c)
        }
    }
    out.append('"')
}
