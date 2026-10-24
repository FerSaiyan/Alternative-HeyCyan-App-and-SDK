package com.fersaiyan.cyanbridge.shared.json

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class JsonSyntaxException(message: String) : IllegalArgumentException(message)

/**
 * A reader with the leniency of Android's org.json `JSONTokener`, which the moved code
 * parsed model output with: single-quoted and unquoted strings, `=`/`=>` and `;`
 * separators, `//`, `/* */` and `#` comments, empty array slots read as null,
 * hex/octal integers and trailing text after the value.
 */
internal class OrgJsonReader(text: String) {
    private val input = text.removePrefix("﻿")
    private var pos = 0

    fun nextValue(): JsonElement {
        return when (val c = nextClean()) {
            -1 -> throw syntaxError("End of input")
            '{'.code -> readObject()
            '['.code -> readArray()
            '\''.code, '"'.code -> JsonPrimitive(nextString(c.toChar()))
            else -> {
                pos--
                readLiteral()
            }
        }
    }

    private fun nextClean(): Int {
        while (pos < input.length) {
            val c = input[pos++]
            when (c) {
                '\t', ' ', '\n', '\r' -> continue
                '/' -> {
                    if (pos == input.length) return c.code
                    when (input[pos]) {
                        '*' -> {
                            pos++
                            val end = input.indexOf("*/", pos)
                            if (end == -1) throw syntaxError("Unterminated comment")
                            pos = end + 2
                        }
                        '/' -> {
                            pos++
                            skipToEndOfLine()
                        }
                        else -> return c.code
                    }
                }
                '#' -> skipToEndOfLine()
                else -> return c.code
            }
        }
        return -1
    }

    private fun skipToEndOfLine() {
        while (pos < input.length) {
            val c = input[pos++]
            if (c == '\r' || c == '\n') break
        }
    }

    private fun nextString(quote: Char): String {
        val builder = StringBuilder()
        while (pos < input.length) {
            val c = input[pos++]
            if (c == quote) return builder.toString()
            if (c == '\\') {
                if (pos == input.length) throw syntaxError("Unterminated escape sequence")
                builder.append(readEscape())
            } else {
                builder.append(c)
            }
        }
        throw syntaxError("Unterminated string")
    }

    private fun readEscape(): Char {
        return when (val escaped = input[pos++]) {
            'u' -> {
                if (pos + 4 > input.length) throw syntaxError("Unterminated escape sequence")
                val hex = input.substring(pos, pos + 4)
                pos += 4
                hex.toIntOrNull(16)?.toChar() ?: throw syntaxError("Invalid escape sequence: $hex")
            }
            't' -> '\t'
            'b' -> '\b'
            'n' -> '\n'
            'r' -> '\r'
            'f' -> '\u000C'
            else -> escaped
        }
    }

    private fun readLiteral(): JsonElement {
        val start = pos
        while (pos < input.length && input[pos] !in LITERAL_STOPS) pos++
        val literal = input.substring(start, pos)
        if (literal.isEmpty()) throw syntaxError("Expected literal value")
        when {
            literal.equals("null", ignoreCase = true) -> return JsonNull
            literal.equals("true", ignoreCase = true) -> return JsonPrimitive(true)
            literal.equals("false", ignoreCase = true) -> return JsonPrimitive(false)
        }
        if ('.' !in literal) {
            var number = literal
            var radix = 10
            if (number.startsWith("0x") || number.startsWith("0X")) {
                number = number.substring(2)
                radix = 16
            } else if (number.startsWith("0") && number.length > 1) {
                number = number.substring(1)
                radix = 8
            }
            number.toLongOrNull(radix)?.let { value ->
                return if (value in Int.MIN_VALUE..Int.MAX_VALUE) JsonPrimitive(value.toInt()) else JsonPrimitive(value)
            }
        }
        literal.toDoubleOrNull()?.let { return JsonPrimitive(it) }
        return JsonPrimitive(literal)
    }

    private fun readObject(): JsonObject {
        val result = LinkedHashMap<String, JsonElement>()
        val first = nextClean()
        if (first == '}'.code) return JsonObject(result)
        if (first != -1) pos--
        while (true) {
            val name = nextValue()
            if (name !is JsonPrimitive || !name.isString) {
                throw syntaxError(if (name is JsonNull) "Names cannot be null" else "Names must be strings, but $name is not")
            }
            val separator = nextClean()
            if (separator != ':'.code && separator != '='.code) throw syntaxError("Expected ':' after ${name.content}")
            if (pos < input.length && input[pos] == '>') pos++
            val value = nextValue()
            if (value is JsonPrimitive && !value.isString && value !is JsonNull) {
                val number = value.content.toDoubleOrNull()
                if (number != null && !number.isFinite()) throw syntaxError("Forbidden numeric value: $number")
            }
            result[name.content] = value
            when (nextClean()) {
                '}'.code -> return JsonObject(result)
                ';'.code, ','.code -> continue
                else -> throw syntaxError("Unterminated object")
            }
        }
    }

    private fun readArray(): JsonArray {
        val result = ArrayList<JsonElement>()
        var hasTrailingSeparator = false
        while (true) {
            when (nextClean()) {
                -1 -> throw syntaxError("Unterminated array")
                ']'.code -> {
                    if (hasTrailingSeparator) result += JsonNull
                    return JsonArray(result)
                }
                ','.code, ';'.code -> {
                    result += JsonNull
                    hasTrailingSeparator = true
                    continue
                }
                else -> pos--
            }
            result += nextValue()
            when (nextClean()) {
                ']'.code -> return JsonArray(result)
                ','.code, ';'.code -> hasTrailingSeparator = true
                else -> throw syntaxError("Unterminated array")
            }
        }
    }

    private fun syntaxError(message: String) =
        JsonSyntaxException("$message at character $pos of ${input.take(200)}")

    private companion object {
        val LITERAL_STOPS = setOf('{', '}', '[', ']', '/', '\\', ':', ',', '=', ';', '#', ' ', '\t', '\u000C', '\r', '\n')
    }
}
