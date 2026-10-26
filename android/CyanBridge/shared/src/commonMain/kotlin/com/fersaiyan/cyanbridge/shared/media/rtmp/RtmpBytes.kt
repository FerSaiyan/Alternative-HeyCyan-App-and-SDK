package com.fersaiyan.cyanbridge.shared.media.rtmp

/** Malformed or unsupported RTMP/FLV input. Platform adapters close the connection on it. */
class RtmpProtocolException(message: String) : Exception(message)

/** A growable big-endian byte buffer for building protocol messages. */
class ByteWriter(initialCapacity: Int = 256) {
    private var buffer = ByteArray(initialCapacity.coerceAtLeast(16))

    var size: Int = 0
        private set

    private fun ensure(extra: Int) {
        val needed = size + extra
        if (needed <= buffer.size) return
        var next = buffer.size * 2
        while (next < needed) next *= 2
        buffer = buffer.copyOf(next)
    }

    fun u8(value: Int): ByteWriter {
        ensure(1)
        buffer[size++] = value.toByte()
        return this
    }

    fun u16(value: Int): ByteWriter = u8(value ushr 8).u8(value)

    fun u24(value: Int): ByteWriter = u8(value ushr 16).u8(value ushr 8).u8(value)

    fun u32(value: Long): ByteWriter = u8((value ushr 24).toInt()).u8((value ushr 16).toInt())
        .u8((value ushr 8).toInt()).u8(value.toInt())

    fun u32Le(value: Int): ByteWriter = u8(value).u8(value ushr 8).u8(value ushr 16).u8(value ushr 24)

    fun u64(value: Long): ByteWriter = u32(value ushr 32).u32(value and 0xFFFF_FFFFL)

    fun bytes(source: ByteArray, offset: Int = 0, length: Int = source.size - offset): ByteWriter {
        ensure(length)
        source.copyInto(buffer, size, offset, offset + length)
        size += length
        return this
    }

    fun fill(value: Int, count: Int): ByteWriter {
        ensure(count)
        buffer.fill(value.toByte(), size, size + count)
        size += count
        return this
    }

    fun toByteArray(): ByteArray = buffer.copyOf(size)
}

/** Sequential big-endian reads over a byte range; reads past the end throw [RtmpProtocolException]. */
class ByteReader(private val data: ByteArray, offset: Int = 0, private val end: Int = data.size) {
    var position: Int = offset
        private set

    val remaining: Int get() = end - position

    private fun require(count: Int) {
        if (count < 0 || position + count > end) throw RtmpProtocolException("Truncated data")
    }

    fun u8(): Int {
        require(1)
        return data[position++].toInt() and 0xFF
    }

    fun u16(): Int = (u8() shl 8) or u8()

    fun u24(): Int = (u8() shl 16) or (u8() shl 8) or u8()

    /** A signed 24-bit value (FLV composition time). */
    fun s24(): Int {
        val value = u24()
        return if (value and 0x80_0000 != 0) value - 0x100_0000 else value
    }

    fun u32(): Long = (u16().toLong() shl 16) or u16().toLong()

    fun u64(): Long = (u32() shl 32) or u32()

    fun bytes(count: Int): ByteArray {
        require(count)
        return data.copyOfRange(position, position + count).also { position += count }
    }

    fun skip(count: Int) {
        require(count)
        position += count
    }

    fun rest(): ByteArray = bytes(remaining)
}

internal fun ByteArray.u32At(index: Int): Long =
    ((this[index].toLong() and 0xFF) shl 24) or ((this[index + 1].toLong() and 0xFF) shl 16) or
        ((this[index + 2].toLong() and 0xFF) shl 8) or (this[index + 3].toLong() and 0xFF)
