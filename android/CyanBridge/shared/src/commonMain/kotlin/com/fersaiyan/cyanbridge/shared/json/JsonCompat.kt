package com.fersaiyan.cyanbridge.shared.json

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Readers with the same fallbacks as Android's org.json `opt*` methods, so code moved
 * out of the app keeps its parsing tolerance (numbers given as strings, `null` read as
 * the text "null", nested values read as their JSON text).
 */
val LenientJson: Json = Json {
    isLenient = true
    ignoreUnknownKeys = true
}

/** Parses [text] as a JSON object, like `JSONObject(text)`; throws when it is not one. */
fun parseJsonObject(text: String): JsonObject =
    LenientJson.parseToJsonElement(text) as? JsonObject
        ?: throw IllegalArgumentException("Value is not a JSON object")

/** Parses [text] as a JSON array, like `JSONArray(text)`; throws when it is not one. */
fun parseJsonArray(text: String): JsonArray =
    LenientJson.parseToJsonElement(text) as? JsonArray
        ?: throw IllegalArgumentException("Value is not a JSON array")

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
