package com.fersaiyan.cyanbridge.shared.devices.memomind

/*
 * The MemoMind "GM" command framing and TLVs, as published in the MemoMind open platform
 * (GlassSDK/docs/PROTOCOL.md "GM packet framing"). Every multi-byte integer is big-endian.
 */

/** TLV types of the GM command transport. */
object GmTlvType {
    const val BYTES = 0x01
    const val STRING = 0x02
    const val INT32 = 0x03
    const val JSON = 0x05
    const val STATUS = 0x06
    const val INT16 = 0x07
    const val INT8 = 0x08

    /** Value widths the parser enforces for the integer types. */
    internal fun fixedWidth(type: Int): Int? = when (type) {
        INT32 -> 4
        STATUS, INT8 -> 1
        INT16 -> 2
        else -> null
    }
}

/** One TLV: a one-byte type, a 24-bit length and the value. */
class GmTlv(val type: Int, val value: ByteArray) {
    /** The unsigned big-endian value of an integer or STATUS TLV; null for other types. */
    val intValue: Long?
        get() = if (GmTlvType.fixedWidth(type) == null) {
            null
        } else {
            value.fold(0L) { acc, byte -> (acc shl 8) or (byte.toLong() and 0xFF) }
        }

    /** UTF-8 text of a STRING or JSON TLV (a trailing NUL, tolerated on JSON, is dropped). */
    val text: String get() = value.decodeToString().trimEnd('\u0000')

    override fun equals(other: Any?): Boolean =
        other is GmTlv && other.type == type && other.value.contentEquals(value)

    override fun hashCode(): Int = 31 * type + value.contentHashCode()

    override fun toString(): String = "GmTlv(type=0x${type.toString(16)}, ${value.size} bytes)"

    companion object {
        fun bytes(value: ByteArray) = GmTlv(GmTlvType.BYTES, value)
        fun string(value: String) = GmTlv(GmTlvType.STRING, value.encodeToByteArray())
        fun json(value: String) = GmTlv(GmTlvType.JSON, value.encodeToByteArray())
        fun int8(value: Int) = GmTlv(GmTlvType.INT8, byteArrayOf(value.toByte()))
        fun int16(value: Int) = GmTlv(GmTlvType.INT16, byteArrayOf((value shr 8).toByte(), value.toByte()))
        fun int32(value: Long) = GmTlv(
            GmTlvType.INT32,
            byteArrayOf((value shr 24).toByte(), (value shr 16).toByte(), (value shr 8).toByte(), value.toByte()),
        )
        fun status(value: Int) = GmTlv(GmTlvType.STATUS, byteArrayOf(value.toByte()))
    }
}

/** A complete logical GM message (all its physical frames reassembled). */
data class GmMessage(
    val eventId: Int,
    val service: Int,
    val command: Int,
    val tlvs: List<GmTlv> = emptyList(),
) {
    /** An empty message is flow control for a fragmented request, not its final response. */
    val isEmpty: Boolean get() = tlvs.isEmpty()

    fun first(type: Int): GmTlv? = tlvs.firstOrNull { it.type == type }

    /**
     * The result of an acknowledgement: the STATUS TLV (system namespace) or, for the plugin
     * service, its INT8 status. Inspect the TLV type before choosing an error table.
     */
    val status: Int? get() = (first(GmTlvType.STATUS) ?: first(GmTlvType.INT8))?.intValue?.toInt()
}

/** A request before an event ID is assigned. */
data class GmRequest(
    val service: Int,
    val command: Int,
    val tlvs: List<GmTlv> = emptyList(),
) {
    fun withEvent(eventId: Int) = GmMessage(eventId, service, command, tlvs)
}

class GmProtocolException(message: String) : Exception(message)

object GmCodec {
    const val FIRST_FRAME_HEAD = 0xFA
    const val HEADER_SIZE = 7
    const val ENVELOPE_SIZE = 9

    /** The SPP / firmware physical frame ceiling; BLE writes may use smaller complete frames. */
    const val MAX_FRAME_BYTES = 512

    /** The logical packet limit of the reference client. */
    const val MAX_LOGICAL_LENGTH = 80 * 1024

    /** Continuation heads above this would cross the App/firmware wrap difference (0xEF/0xF0). */
    const val MAX_CONTINUATION_HEAD = 0xEF

    fun encodeTlvs(tlvs: List<GmTlv>): ByteArray {
        val out = ByteArray(tlvs.sumOf { 4 + it.value.size })
        var offset = 0
        tlvs.forEach { tlv ->
            val size = tlv.value.size
            out[offset] = tlv.type.toByte()
            out[offset + 1] = (size shr 16).toByte()
            out[offset + 2] = (size shr 8).toByte()
            out[offset + 3] = size.toByte()
            tlv.value.copyInto(out, offset + 4)
            offset += 4 + size
        }
        return out
    }

    /**
     * Splits [message] into complete physical frames of at most [maxFrameBytes] bytes, each with
     * the repeated header and its own additive checksum. On BLE pass the characteristic's write
     * limit; on SPP/iAP2 the default 512.
     */
    fun encode(message: GmMessage, maxFrameBytes: Int = MAX_FRAME_BYTES): List<ByteArray> {
        require(maxFrameBytes in (ENVELOPE_SIZE + 1)..MAX_FRAME_BYTES) { "frame size must be 10..512" }
        val body = encodeTlvs(message.tlvs)
        val logicalLength = body.size + ENVELOPE_SIZE
        if (logicalLength > MAX_LOGICAL_LENGTH) throw GmProtocolException("GM message of $logicalLength bytes exceeds 80 KiB")
        val chunkSize = maxFrameBytes - ENVELOPE_SIZE
        val chunkCount = maxOf(1, (body.size + chunkSize - 1) / chunkSize)
        if (chunkCount - 1 > MAX_CONTINUATION_HEAD) {
            throw GmProtocolException("GM message needs $chunkCount frames; use a larger frame size or a smaller payload")
        }
        return List(chunkCount) { index ->
            val start = index * chunkSize
            val end = minOf(body.size, start + chunkSize)
            val frame = ByteArray(HEADER_SIZE + (end - start) + 2)
            frame[0] = (if (index == 0) FIRST_FRAME_HEAD else index).toByte()
            frame[1] = (logicalLength shr 16).toByte()
            frame[2] = (logicalLength shr 8).toByte()
            frame[3] = logicalLength.toByte()
            frame[4] = message.eventId.toByte()
            frame[5] = message.service.toByte()
            frame[6] = message.command.toByte()
            body.copyInto(frame, HEADER_SIZE, start, end)
            val sum = checksum(frame, 0, frame.size - 2)
            frame[frame.size - 2] = (sum shr 8).toByte()
            frame[frame.size - 1] = sum.toByte()
            frame
        }
    }

    /** Unsigned sum of `bytes[from until until]`, modulo 65536. */
    fun checksum(bytes: ByteArray, from: Int, until: Int): Int {
        var sum = 0
        for (index in from until until) sum += bytes[index].toInt() and 0xFF
        return sum and 0xFFFF
    }

    /** Parses a reassembled TLV stream; throws [GmProtocolException] on malformed lengths. */
    fun decodeTlvs(body: ByteArray): List<GmTlv> {
        val tlvs = mutableListOf<GmTlv>()
        var position = 0
        while (position < body.size) {
            if (body.size - position < 4) throw GmProtocolException("Truncated TLV header")
            val type = body[position].toInt() and 0xFF
            val size = readUInt24(body, position + 1)
            position += 4
            if (position + size > body.size) throw GmProtocolException("Truncated TLV value")
            GmTlvType.fixedWidth(type)?.let { width ->
                if (size != width) throw GmProtocolException("TLV type 0x${type.toString(16)} must be $width bytes")
            }
            tlvs += GmTlv(type, body.copyOfRange(position, position + size))
            position += size
        }
        return tlvs
    }

    internal fun readUInt24(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            (bytes[offset + 2].toInt() and 0xFF)
}

/**
 * Reassembles GM messages from a command link. It accepts an SPP/iAP2 byte stream (partial or
 * several frames per read) and BLE notifications alike: frames are delimited by the protocol
 * lengths, assuming the sender's physical frame ceiling [maxFrameBytes] (the firmware always
 * sends 512-byte frames). Bad checksums, out-of-order continuations and malformed TLVs drop the
 * partial message and resynchronise on the next 0xFA; a new first frame replaces a partial one.
 */
class GmFrameDecoder(private val maxFrameBytes: Int = GmCodec.MAX_FRAME_BYTES) {
    private var buffer = ByteArray(1024)
    private var size = 0
    private var partial: Partial? = null

    /** Frames or bytes skipped since the last [reset]. */
    var malformedCount: Int = 0
        private set

    private class Partial(
        val logicalLength: Int,
        val eventId: Int,
        val service: Int,
        val command: Int,
        var nextHead: Int,
        val body: ByteArray,
        var received: Int,
    )

    fun reset() {
        size = 0
        partial = null
        malformedCount = 0
    }

    fun append(chunk: ByteArray): List<GmMessage> {
        ensureCapacity(size + chunk.size)
        chunk.copyInto(buffer, size)
        size += chunk.size
        val messages = mutableListOf<GmMessage>()
        while (true) {
            val message = next()
            if (message != null) messages += message else if (needsMoreBytes) break
        }
        return messages
    }

    /** Set by [next] when it stopped because the buffer holds no complete frame. */
    private var needsMoreBytes = false

    /** Returns a completed message, or null after consuming/skipping input (see [needsMoreBytes]). */
    private fun next(): GmMessage? {
        needsMoreBytes = false
        if (size == 0) {
            needsMoreBytes = true
            return null
        }
        val head = buffer[0].toInt() and 0xFF
        val current = partial
        if (head != GmCodec.FIRST_FRAME_HEAD && (current == null || !isAcceptedContinuation(current, head))) {
            if (current != null) {
                partial = null
                malformedCount++
            }
            skipToFirstFrame(from = 1)
            return null
        }
        if (size < GmCodec.HEADER_SIZE) {
            needsMoreBytes = true
            return null
        }
        val logicalLength = GmCodec.readUInt24(buffer, 1)
        val eventId = buffer[4].toInt() and 0xFF
        val service = buffer[5].toInt() and 0xFF
        val command = buffer[6].toInt() and 0xFF
        if (logicalLength < GmCodec.ENVELOPE_SIZE || logicalLength > GmCodec.MAX_LOGICAL_LENGTH) {
            malformedCount++
            skipToFirstFrame(from = 1)
            return null
        }
        val continuing = if (head == GmCodec.FIRST_FRAME_HEAD) {
            if (current != null) malformedCount++ // A new first frame replaces the partial message.
            null
        } else {
            current?.takeIf {
                it.logicalLength == logicalLength && it.eventId == eventId && it.service == service && it.command == command
            } ?: run {
                partial = null
                malformedCount++
                skipToFirstFrame(from = 1)
                return null
            }
        }
        val alreadyReceived = continuing?.received ?: 0
        val remaining = logicalLength - GmCodec.ENVELOPE_SIZE - alreadyReceived
        val frameBody = minOf(remaining, maxFrameBytes - GmCodec.ENVELOPE_SIZE)
        val frameLength = GmCodec.HEADER_SIZE + frameBody + 2
        if (size < frameLength) {
            needsMoreBytes = true
            return null
        }
        val expected = ((buffer[frameLength - 2].toInt() and 0xFF) shl 8) or (buffer[frameLength - 1].toInt() and 0xFF)
        if (GmCodec.checksum(buffer, 0, frameLength - 2) != expected) {
            partial = null
            malformedCount++
            skipToFirstFrame(from = 1)
            return null
        }
        val target = continuing ?: Partial(
            logicalLength = logicalLength,
            eventId = eventId,
            service = service,
            command = command,
            nextHead = 1,
            body = ByteArray(logicalLength - GmCodec.ENVELOPE_SIZE),
            received = 0,
        )
        buffer.copyInto(target.body, target.received, GmCodec.HEADER_SIZE, GmCodec.HEADER_SIZE + frameBody)
        target.received += frameBody
        consume(frameLength)
        if (target.received < target.body.size) {
            if (continuing != null) target.nextHead = nextContinuationHead(head)
            partial = target
            return null
        }
        partial = null
        return try {
            GmMessage(eventId, service, command, GmCodec.decodeTlvs(target.body))
        } catch (error: GmProtocolException) {
            malformedCount++
            null
        }
    }

    /** The firmware wraps 0xF0 to 0x01 and the App after 0xEF; accept both after 0xEF. */
    private fun isAcceptedContinuation(partial: Partial, head: Int): Boolean =
        head == partial.nextHead || (partial.nextHead == 0xF0 && head == 0x01)

    private fun nextContinuationHead(head: Int): Int = if (head >= 0xF0) 0x01 else head + 1

    private fun skipToFirstFrame(from: Int) {
        var index = from
        while (index < size && (buffer[index].toInt() and 0xFF) != GmCodec.FIRST_FRAME_HEAD) index++
        if (index > from) malformedCount++
        consume(index)
    }

    private fun consume(count: Int) {
        buffer.copyInto(buffer, 0, count, size)
        size -= count
    }

    private fun ensureCapacity(required: Int) {
        if (required <= buffer.size) return
        buffer = buffer.copyOf(maxOf(required, buffer.size * 2))
    }
}
