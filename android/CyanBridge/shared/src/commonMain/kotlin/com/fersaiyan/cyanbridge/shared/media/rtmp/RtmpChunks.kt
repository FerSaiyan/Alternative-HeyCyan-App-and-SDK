package com.fersaiyan.cyanbridge.shared.media.rtmp

/** One complete RTMP message, reassembled from its chunks. */
class RtmpMessage(
    val typeId: Int,
    val streamId: Int,
    val timestamp: Long,
    val payload: ByteArray,
) {
    override fun toString(): String = "RtmpMessage(type=$typeId, stream=$streamId, ts=$timestamp, ${payload.size} bytes)"

    companion object {
        const val SET_CHUNK_SIZE = 1
        const val ABORT = 2
        const val ACKNOWLEDGEMENT = 3
        const val USER_CONTROL = 4
        const val WINDOW_ACK_SIZE = 5
        const val SET_PEER_BANDWIDTH = 6
        const val AUDIO = 8
        const val VIDEO = 9
        const val DATA_AMF3 = 15
        const val COMMAND_AMF3 = 17
        const val DATA_AMF0 = 18
        const val COMMAND_AMF0 = 20
    }
}

/**
 * Reassembles RTMP messages from a byte stream (RTMP 1.0 section 5.3). Bytes may arrive in any
 * split; [feed] keeps an incomplete chunk until the rest arrives. Set-chunk-size and abort
 * messages are applied here (and still returned) because they change how the next chunk parses.
 */
class RtmpChunkDecoder(private val maxMessageBytes: Int = DEFAULT_MAX_MESSAGE_BYTES) {
    private class StreamState {
        var timestamp = 0L
        var timestampField = 0L
        var length = 0
        var typeId = 0
        var streamId = 0
        var hasHeader = false
        var payload: ByteArray? = null
        var received = 0
    }

    /** The peer's current chunk size. */
    var chunkSize: Int = DEFAULT_CHUNK_SIZE
        private set

    private val streams = HashMap<Int, StreamState>()
    private var pending = ByteArray(0)
    private var pendingStart = 0
    private var pendingEnd = 0

    fun feed(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): List<RtmpMessage> {
        append(data, offset, length)
        val messages = mutableListOf<RtmpMessage>()
        while (true) {
            val consumed = parseChunk(messages)
            if (consumed == 0) break
            pendingStart += consumed
        }
        if (pendingStart == pendingEnd) {
            pendingStart = 0
            pendingEnd = 0
        }
        return messages
    }

    private fun append(data: ByteArray, offset: Int, length: Int) {
        if (pendingStart > 0 && pendingEnd + length > pending.size) {
            pending.copyInto(pending, 0, pendingStart, pendingEnd)
            pendingEnd -= pendingStart
            pendingStart = 0
        }
        if (pendingEnd + length > pending.size) {
            pending = pending.copyOf(maxOf(pending.size * 2, pendingEnd + length, 4096))
        }
        data.copyInto(pending, pendingEnd, offset, offset + length)
        pendingEnd += length
    }

    private fun byteAt(index: Int): Int = pending[pendingStart + index].toInt() and 0xFF

    private fun u24At(index: Int): Long =
        ((byteAt(index) shl 16) or (byteAt(index + 1) shl 8) or byteAt(index + 2)).toLong()

    /** Parses one chunk at the start of the pending bytes; returns the bytes it used, or 0 if incomplete. */
    private fun parseChunk(out: MutableList<RtmpMessage>): Int {
        val available = pendingEnd - pendingStart
        if (available < 1) return 0
        val first = byteAt(0)
        val format = first ushr 6
        var position = 1
        val chunkStreamId = when (first and 0x3F) {
            0 -> {
                if (available < 2) return 0
                position = 2
                64 + byteAt(1)
            }
            1 -> {
                if (available < 3) return 0
                position = 3
                64 + byteAt(1) + (byteAt(2) shl 8)
            }
            else -> first and 0x3F
        }
        val headerLength = when (format) {
            0 -> 11
            1 -> 7
            2 -> 3
            else -> 0
        }
        if (available < position + headerLength) return 0
        val state = streams.getOrPut(chunkStreamId) { StreamState() }
        if (format != 0 && !state.hasHeader) {
            throw RtmpProtocolException("Chunk stream $chunkStreamId continues a message it never started")
        }
        val startsMessage = state.payload == null
        var timestampField = state.timestampField
        var length = state.length
        var typeId = state.typeId
        var streamId = state.streamId
        if (format <= 2) timestampField = u24At(position)
        if (format <= 1) {
            length = u24At(position + 3).toInt()
            typeId = byteAt(position + 6)
        }
        if (format == 0) {
            streamId = byteAt(position + 7) or (byteAt(position + 8) shl 8) or
                (byteAt(position + 9) shl 16) or (byteAt(position + 10) shl 24)
        }
        position += headerLength
        var timestampValue = timestampField
        if (timestampField == EXTENDED_TIMESTAMP) {
            if (available < position + 4) return 0
            timestampValue = pending.u32At(pendingStart + position)
            position += 4
        }
        if (length < 0 || length > maxMessageBytes) {
            throw RtmpProtocolException("RTMP message of $length bytes exceeds the limit")
        }
        val alreadyReceived = if (startsMessage) 0 else state.received
        val bodyLength = minOf(chunkSize, length - alreadyReceived)
        if (available < position + bodyLength) return 0

        // The chunk is complete: commit its header to the chunk stream.
        if (startsMessage) {
            state.timestamp = if (format == 0) timestampValue else state.timestamp + timestampValue
            state.length = length
            state.typeId = typeId
            state.streamId = streamId
            state.payload = ByteArray(length)
            state.received = 0
        }
        state.timestampField = timestampField
        state.hasHeader = true
        val payload = state.payload!!
        pending.copyInto(payload, state.received, pendingStart + position, pendingStart + position + bodyLength)
        state.received += bodyLength
        position += bodyLength
        if (state.received >= state.length) {
            state.payload = null
            state.received = 0
            val message = RtmpMessage(state.typeId, state.streamId, state.timestamp, payload)
            applyControl(message)
            out += message
        }
        return position
    }

    private fun applyControl(message: RtmpMessage) {
        when (message.typeId) {
            RtmpMessage.SET_CHUNK_SIZE -> {
                if (message.payload.size < 4) throw RtmpProtocolException("Short set-chunk-size message")
                val size = (message.payload.u32At(0) and 0x7FFF_FFFFL).toInt()
                if (size < 1 || size > MAX_CHUNK_SIZE) throw RtmpProtocolException("Invalid chunk size $size")
                chunkSize = size
            }
            RtmpMessage.ABORT -> {
                if (message.payload.size >= 4) {
                    streams[message.payload.u32At(0).toInt()]?.let {
                        it.payload = null
                        it.received = 0
                    }
                }
            }
        }
    }

    companion object {
        const val DEFAULT_CHUNK_SIZE = 128
        const val MAX_CHUNK_SIZE = 0xFF_FFFF
        const val DEFAULT_MAX_MESSAGE_BYTES = 8 * 1024 * 1024
        internal const val EXTENDED_TIMESTAMP = 0xFF_FFFFL
    }
}

/** Splits outgoing messages into chunks: a type-0 header, then type-3 continuations. */
class RtmpChunkEncoder {
    /** Our outgoing chunk size; announce it with [RtmpControl.setChunkSize] before raising it. */
    var chunkSize: Int = RtmpChunkDecoder.DEFAULT_CHUNK_SIZE

    fun encode(chunkStreamId: Int, message: RtmpMessage): ByteArray {
        require(chunkStreamId in 2..63) { "Only one-byte chunk stream ids are used" }
        val payload = message.payload
        val extended = message.timestamp >= RtmpChunkDecoder.EXTENDED_TIMESTAMP
        val writer = ByteWriter(payload.size + 16 + (payload.size / chunkSize) * 5)
        writer.u8(chunkStreamId)
        writer.u24(if (extended) RtmpChunkDecoder.EXTENDED_TIMESTAMP.toInt() else message.timestamp.toInt())
        writer.u24(payload.size)
        writer.u8(message.typeId)
        writer.u32Le(message.streamId)
        if (extended) writer.u32(message.timestamp)
        var offset = 0
        while (true) {
            val count = minOf(chunkSize, payload.size - offset)
            writer.bytes(payload, offset, count)
            offset += count
            if (offset >= payload.size) break
            writer.u8(0xC0 or chunkStreamId)
            if (extended) writer.u32(message.timestamp)
        }
        return writer.toByteArray()
    }
}

/** Protocol control and user control messages a server sends. */
object RtmpControl {
    fun setChunkSize(size: Int) = RtmpMessage(RtmpMessage.SET_CHUNK_SIZE, 0, 0, ByteWriter(4).u32(size.toLong()).toByteArray())

    fun windowAckSize(size: Long) = RtmpMessage(RtmpMessage.WINDOW_ACK_SIZE, 0, 0, ByteWriter(4).u32(size).toByteArray())

    /** Limit type 2 = dynamic. */
    fun setPeerBandwidth(size: Long, limitType: Int = 2) =
        RtmpMessage(RtmpMessage.SET_PEER_BANDWIDTH, 0, 0, ByteWriter(5).u32(size).u8(limitType).toByteArray())

    fun acknowledgement(sequence: Long) =
        RtmpMessage(RtmpMessage.ACKNOWLEDGEMENT, 0, 0, ByteWriter(4).u32(sequence and 0xFFFF_FFFFL).toByteArray())

    /** User control event 0: Stream Begin. */
    fun streamBegin(streamId: Int) =
        RtmpMessage(RtmpMessage.USER_CONTROL, 0, 0, ByteWriter(6).u16(0).u32(streamId.toLong()).toByteArray())

    /** User control event 7: Ping Response, echoing a Ping Request's timestamp. */
    fun pingResponse(timestamp: Long) =
        RtmpMessage(RtmpMessage.USER_CONTROL, 0, 0, ByteWriter(6).u16(7).u32(timestamp).toByteArray())
}

/**
 * The server side of the simple RTMP handshake: C0+C1 in, S0+S1+S2 out, then C2. S1 carries a
 * zero version field, so digest-aware clients (librtmp, FFmpeg) fall back to the simple scheme.
 */
object RtmpHandshake {
    const val VERSION = 3
    const val SIGNATURE_SIZE = 1536
    const val C0C1_SIZE = 1 + SIGNATURE_SIZE

    fun serverResponse(c0c1: ByteArray, serverTimeMs: Long, random: ByteArray): ByteArray {
        require(c0c1.size >= C0C1_SIZE) { "C0+C1 needs $C0C1_SIZE bytes" }
        val version = c0c1[0].toInt() and 0xFF
        if (version != VERSION) throw RtmpProtocolException("Unsupported RTMP version $version")
        require(random.size >= SIGNATURE_SIZE - 8) { "S1 needs ${SIGNATURE_SIZE - 8} random bytes" }
        val writer = ByteWriter(1 + 2 * SIGNATURE_SIZE)
        writer.u8(VERSION)
        writer.u32(serverTimeMs and 0xFFFF_FFFFL).u32(0).bytes(random, 0, SIGNATURE_SIZE - 8)
        // S2 echoes C1 (its time, then the client's random bytes).
        writer.bytes(c0c1, 1, SIGNATURE_SIZE)
        return writer.toByteArray()
    }
}
