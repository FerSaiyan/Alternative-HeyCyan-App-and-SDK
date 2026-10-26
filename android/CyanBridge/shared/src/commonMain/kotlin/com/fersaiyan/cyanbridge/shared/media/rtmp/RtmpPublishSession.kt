package com.fersaiyan.cyanbridge.shared.media.rtmp

import kotlin.random.Random

/** What happened on an RTMP publishing connection, in order. */
sealed interface RtmpPublishEvent {
    /** `connect` arrived for [app]. */
    data class Connected(val app: String, val tcUrl: String?) : RtmpPublishEvent

    /** `publish` was accepted; media for [streamKey] follows. */
    data class PublishStarted(val app: String, val streamKey: String) : RtmpPublishEvent

    /** `publish` named a stream this server does not accept; the connection should close. */
    data class Rejected(val reason: String) : RtmpPublishEvent

    /** `@setDataFrame`/`onMetaData` properties (width, height, framerate, codec ids). */
    data class Metadata(val properties: Map<String, Any?>) : RtmpPublishEvent

    /** An FLV video tag body. */
    data class Video(val timestamp: Long, val payload: ByteArray) : RtmpPublishEvent

    /** An FLV audio tag body. */
    data class Audio(val timestamp: Long, val payload: ByteArray) : RtmpPublishEvent

    /** The publisher ended the stream (FCUnpublish, deleteStream or closeStream). */
    data object Unpublished : RtmpPublishEvent
}

/** Bytes to send back and the events [RtmpPublishSession.receive] produced. */
class RtmpPublishOutput(val reply: ByteArray, val events: List<RtmpPublishEvent>) {
    val shouldClose: Boolean get() = events.any { it is RtmpPublishEvent.Rejected }
}

/**
 * The server side of one RTMP publishing connection: handshake, `connect`, `createStream`,
 * `publish`, then media. Socket I/O stays in the platform; this class only turns received bytes
 * into reply bytes and [RtmpPublishEvent]s, so it runs the same way in tests.
 *
 * Only [expectedStreamKey] may publish (the key is a per-session secret in the ingest URL). The
 * app name is not checked: some publishers put the key in a different path segment.
 */
class RtmpPublishSession(
    private val expectedStreamKey: String,
    private val nowMs: () -> Long = { 0L },
    private val random: (Int) -> ByteArray = { Random.nextBytes(it) },
    private val outgoingChunkSize: Int = 4096,
) {
    private enum class Phase { AWAIT_C0C1, AWAIT_C2, OPEN, CLOSED }

    private var phase = Phase.AWAIT_C0C1
    private var handshakeBuffer = ByteWriter(RtmpHandshake.C0C1_SIZE)
    private val decoder = RtmpChunkDecoder()
    private val encoder = RtmpChunkEncoder()
    private var app = ""
    private var tcUrl: String? = null
    private var publishing = false
    private var nextStreamId = 1
    private var bytesReceived = 0L
    private var lastAcknowledged = 0L
    private var peerWindowAckSize = DEFAULT_WINDOW_ACK_SIZE

    val isPublishing: Boolean get() = publishing

    fun receive(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): RtmpPublishOutput {
        if (phase == Phase.CLOSED || length == 0) return RtmpPublishOutput(ByteArray(0), emptyList())
        val reply = ByteWriter(64)
        val events = mutableListOf<RtmpPublishEvent>()
        var position = offset
        val end = offset + length
        if (phase == Phase.AWAIT_C0C1 || phase == Phase.AWAIT_C2) {
            position = handshake(data, position, end, reply)
        }
        if (phase == Phase.OPEN && position < end) {
            bytesReceived += end - position
            for (message in decoder.feed(data, position, end - position)) {
                handle(message, reply, events)
                if (phase == Phase.CLOSED) break
            }
            if (bytesReceived - lastAcknowledged >= peerWindowAckSize) {
                lastAcknowledged = bytesReceived
                send(reply, CONTROL_CHUNK_STREAM, RtmpControl.acknowledgement(bytesReceived))
            }
        }
        return RtmpPublishOutput(reply.toByteArray(), events)
    }

    private fun handshake(data: ByteArray, start: Int, end: Int, reply: ByteWriter): Int {
        val target = if (phase == Phase.AWAIT_C0C1) RtmpHandshake.C0C1_SIZE else RtmpHandshake.SIGNATURE_SIZE
        val take = minOf(target - handshakeBuffer.size, end - start)
        handshakeBuffer.bytes(data, start, take)
        if (handshakeBuffer.size < target) return start + take
        val collected = handshakeBuffer.toByteArray()
        if (phase == Phase.AWAIT_C0C1) {
            reply.bytes(RtmpHandshake.serverResponse(collected, nowMs(), random(RtmpHandshake.SIGNATURE_SIZE - 8)))
            phase = Phase.AWAIT_C2
            handshakeBuffer = ByteWriter(RtmpHandshake.SIGNATURE_SIZE)
            // C2 may already be in this read.
            return handshake(data, start + take, end, reply)
        }
        phase = Phase.OPEN
        return start + take
    }

    private fun handle(message: RtmpMessage, reply: ByteWriter, events: MutableList<RtmpPublishEvent>) {
        when (message.typeId) {
            RtmpMessage.WINDOW_ACK_SIZE -> {
                if (message.payload.size >= 4) peerWindowAckSize = message.payload.u32At(0).coerceAtLeast(1)
            }
            RtmpMessage.USER_CONTROL -> {
                val payload = message.payload
                if (payload.size >= 6 && (((payload[0].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF)) == 6) {
                    send(reply, CONTROL_CHUNK_STREAM, RtmpControl.pingResponse(payload.u32At(2)))
                }
            }
            RtmpMessage.COMMAND_AMF0 -> command(Amf0.decodeAll(message.payload), message.streamId, reply, events)
            // AMF3 commands carry one leading format byte, then AMF0 values.
            RtmpMessage.COMMAND_AMF3 -> if (message.payload.isNotEmpty()) {
                command(Amf0.decodeAll(message.payload, 1), message.streamId, reply, events)
            }
            RtmpMessage.DATA_AMF0 -> data(Amf0.decodeAll(message.payload), events)
            RtmpMessage.DATA_AMF3 -> if (message.payload.isNotEmpty()) data(Amf0.decodeAll(message.payload, 1), events)
            RtmpMessage.VIDEO -> if (publishing && message.payload.isNotEmpty()) {
                events += RtmpPublishEvent.Video(message.timestamp, message.payload)
            }
            RtmpMessage.AUDIO -> if (publishing && message.payload.isNotEmpty()) {
                events += RtmpPublishEvent.Audio(message.timestamp, message.payload)
            }
        }
    }

    private fun command(values: List<Any?>, streamId: Int, reply: ByteWriter, events: MutableList<RtmpPublishEvent>) {
        val name = values.getOrNull(0) as? String ?: return
        val transaction = values.getOrNull(1) as? Double ?: 0.0
        when (name) {
            "connect" -> {
                val properties = values.getOrNull(2) as? Map<*, *>
                app = (properties?.get("app") as? String).orEmpty()
                tcUrl = properties?.get("tcUrl") as? String
                send(reply, CONTROL_CHUNK_STREAM, RtmpControl.windowAckSize(DEFAULT_WINDOW_ACK_SIZE))
                send(reply, CONTROL_CHUNK_STREAM, RtmpControl.setPeerBandwidth(DEFAULT_WINDOW_ACK_SIZE))
                send(reply, CONTROL_CHUNK_STREAM, RtmpControl.setChunkSize(outgoingChunkSize))
                encoder.chunkSize = outgoingChunkSize
                sendCommand(
                    reply, 0, "_result", transaction,
                    linkedMapOf("fmsVer" to "FMS/3,0,1,123", "capabilities" to 31.0),
                    linkedMapOf(
                        "level" to "status",
                        "code" to "NetConnection.Connect.Success",
                        "description" to "Connection succeeded.",
                        "objectEncoding" to ((properties?.get("objectEncoding") as? Double) ?: 0.0),
                    ),
                )
                events += RtmpPublishEvent.Connected(app, tcUrl)
            }
            "releaseStream", "FCPublish" -> sendCommand(reply, 0, "_result", transaction, null, Amf0Undefined)
            "createStream" -> sendCommand(reply, 0, "_result", transaction, null, (nextStreamId++).toDouble())
            "publish" -> {
                val key = (values.getOrNull(3) as? String).orEmpty().substringBefore('?')
                val messageStream = if (streamId == 0) 1 else streamId
                if (key != expectedStreamKey) {
                    sendStatus(reply, messageStream, "error", "NetStream.Publish.BadName", "Unknown stream key")
                    events += RtmpPublishEvent.Rejected("Publish to an unknown stream key")
                    phase = Phase.CLOSED
                    return
                }
                publishing = true
                send(reply, CONTROL_CHUNK_STREAM, RtmpControl.streamBegin(messageStream))
                sendStatus(reply, messageStream, "status", "NetStream.Publish.Start", "Publishing $key")
                events += RtmpPublishEvent.PublishStarted(app, key)
            }
            "FCUnpublish", "deleteStream", "closeStream" -> if (publishing) {
                publishing = false
                events += RtmpPublishEvent.Unpublished
            }
        }
    }

    private fun data(values: List<Any?>, events: MutableList<RtmpPublishEvent>) {
        var index = 0
        if (values.getOrNull(0) == "@setDataFrame") index = 1
        if (values.getOrNull(index) != "onMetaData") return
        val properties = values.getOrNull(index + 1) as? Map<*, *> ?: return
        events += RtmpPublishEvent.Metadata(properties.entries.associate { (k, v) -> k.toString() to v })
    }

    private fun sendCommand(reply: ByteWriter, streamId: Int, vararg values: Any?) {
        send(reply, COMMAND_CHUNK_STREAM, RtmpMessage(RtmpMessage.COMMAND_AMF0, streamId, 0, Amf0.encode(values.toList())))
    }

    private fun sendStatus(reply: ByteWriter, streamId: Int, level: String, code: String, description: String) {
        val info = linkedMapOf<String, Any?>("level" to level, "code" to code, "description" to description)
        send(
            reply, STATUS_CHUNK_STREAM,
            RtmpMessage(RtmpMessage.COMMAND_AMF0, streamId, 0, Amf0.encode("onStatus", 0.0, null, info)),
        )
    }

    private fun send(reply: ByteWriter, chunkStreamId: Int, message: RtmpMessage) {
        reply.bytes(encoder.encode(chunkStreamId, message))
    }

    private companion object {
        const val CONTROL_CHUNK_STREAM = 2
        const val COMMAND_CHUNK_STREAM = 3
        const val STATUS_CHUNK_STREAM = 5
        const val DEFAULT_WINDOW_ACK_SIZE = 2_500_000L
    }
}
