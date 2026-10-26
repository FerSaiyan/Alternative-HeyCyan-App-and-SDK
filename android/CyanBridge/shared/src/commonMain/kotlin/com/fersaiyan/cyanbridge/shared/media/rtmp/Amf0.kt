package com.fersaiyan.cyanbridge.shared.media.rtmp

/** The AMF0 `undefined` marker (distinct from `null`). */
object Amf0Undefined {
    override fun toString(): String = "undefined"
}

/**
 * AMF0, the encoding of RTMP commands and metadata. Values map to Kotlin as: number -> [Double],
 * boolean -> [Boolean], string -> [String], object and ECMA array -> [Map] (insertion order kept),
 * strict array -> [List], null -> `null`, undefined -> [Amf0Undefined], date -> [Double] (ms).
 */
object Amf0 {
    private const val NUMBER = 0x00
    private const val BOOLEAN = 0x01
    private const val STRING = 0x02
    private const val OBJECT = 0x03
    private const val NULL = 0x05
    private const val UNDEFINED = 0x06
    private const val ECMA_ARRAY = 0x08
    private const val OBJECT_END = 0x09
    private const val STRICT_ARRAY = 0x0A
    private const val DATE = 0x0B
    private const val LONG_STRING = 0x0C
    private const val MAX_DEPTH = 16

    fun encode(vararg values: Any?): ByteArray = encode(values.toList())

    fun encode(values: List<Any?>): ByteArray {
        val writer = ByteWriter()
        values.forEach { writeValue(writer, it) }
        return writer.toByteArray()
    }

    /** Decodes every value in [data]; throws [RtmpProtocolException] on malformed input. */
    fun decodeAll(data: ByteArray, offset: Int = 0, end: Int = data.size): List<Any?> {
        val reader = ByteReader(data, offset, end)
        val values = mutableListOf<Any?>()
        while (reader.remaining > 0) values += readValue(reader, 0)
        return values
    }

    private fun writeValue(writer: ByteWriter, value: Any?) {
        when (value) {
            null -> writer.u8(NULL)
            Amf0Undefined -> writer.u8(UNDEFINED)
            is Boolean -> writer.u8(BOOLEAN).u8(if (value) 1 else 0)
            is Number -> writer.u8(NUMBER).u64(value.toDouble().toRawBits())
            is String -> {
                val bytes = value.encodeToByteArray()
                if (bytes.size > 0xFFFF) {
                    writer.u8(LONG_STRING).u32(bytes.size.toLong()).bytes(bytes)
                } else {
                    writer.u8(STRING).u16(bytes.size).bytes(bytes)
                }
            }
            is Map<*, *> -> {
                writer.u8(OBJECT)
                value.forEach { (key, entry) ->
                    val name = key.toString().encodeToByteArray()
                    writer.u16(name.size).bytes(name)
                    writeValue(writer, entry)
                }
                writer.u16(0).u8(OBJECT_END)
            }
            is List<*> -> {
                writer.u8(STRICT_ARRAY).u32(value.size.toLong())
                value.forEach { writeValue(writer, it) }
            }
            else -> throw IllegalArgumentException("Cannot encode ${value::class.simpleName} as AMF0")
        }
    }

    private fun readValue(reader: ByteReader, depth: Int): Any? {
        if (depth > MAX_DEPTH) throw RtmpProtocolException("AMF0 nesting too deep")
        return when (val marker = reader.u8()) {
            NUMBER -> Double.fromBits(reader.u64())
            BOOLEAN -> reader.u8() != 0
            STRING -> reader.bytes(reader.u16()).decodeToString()
            LONG_STRING -> reader.bytes(length(reader.u32(), reader)).decodeToString()
            OBJECT -> readProperties(reader, depth)
            ECMA_ARRAY -> {
                reader.u32() // The count is advisory; the end marker terminates the list.
                readProperties(reader, depth)
            }
            STRICT_ARRAY -> {
                val count = length(reader.u32(), reader)
                List(count) { readValue(reader, depth + 1) }
            }
            NULL -> null
            UNDEFINED -> Amf0Undefined
            DATE -> Double.fromBits(reader.u64()).also { reader.skip(2) }
            else -> throw RtmpProtocolException("Unsupported AMF0 marker $marker")
        }
    }

    private fun length(value: Long, reader: ByteReader): Int {
        if (value < 0 || value > reader.remaining) throw RtmpProtocolException("AMF0 length out of range")
        return value.toInt()
    }

    private fun readProperties(reader: ByteReader, depth: Int): Map<String, Any?> {
        val properties = LinkedHashMap<String, Any?>()
        while (true) {
            val nameLength = reader.u16()
            if (nameLength == 0) {
                val end = reader.u8()
                if (end != OBJECT_END) throw RtmpProtocolException("AMF0 object without end marker")
                return properties
            }
            val name = reader.bytes(nameLength).decodeToString()
            properties[name] = readValue(reader, depth + 1)
        }
    }
}
