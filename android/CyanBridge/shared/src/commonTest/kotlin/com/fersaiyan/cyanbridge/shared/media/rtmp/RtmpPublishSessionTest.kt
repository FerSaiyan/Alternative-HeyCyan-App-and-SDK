package com.fersaiyan.cyanbridge.shared.media.rtmp

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Drives [RtmpPublishSession] the way an RTMP publisher (librtmp, FFmpeg, StreamPack) does. */
class RtmpPublishSessionTest {
    private class Publisher(val session: RtmpPublishSession, private val split: Int = Int.MAX_VALUE) {
        val encoder = RtmpChunkEncoder()
        val replies = RtmpChunkDecoder()
        val events = mutableListOf<RtmpPublishEvent>()
        val received = mutableListOf<RtmpMessage>()
        var closed = false

        fun send(bytes: ByteArray) {
            bytes.toList().chunked(split.coerceAtMost(bytes.size.coerceAtLeast(1))).forEach { part ->
                val output = session.receive(part.toByteArray())
                events += output.events
                if (output.shouldClose) closed = true
                if (handshakeDone) received += replies.feed(output.reply) else handshakeReply += output.reply
            }
        }

        var handshakeDone = false
        var handshakeReply = ByteArray(0)

        fun handshake() {
            send(ByteArray(RtmpHandshake.C0C1_SIZE) { if (it == 0) 3 else (it and 0xFF).toByte() })
            assertEquals(1 + 2 * RtmpHandshake.SIGNATURE_SIZE, handshakeReply.size)
            handshakeDone = true
            send(ByteArray(RtmpHandshake.SIGNATURE_SIZE))
        }

        fun command(streamId: Int, vararg values: Any?) =
            send(encoder.encode(3, RtmpMessage(RtmpMessage.COMMAND_AMF0, streamId, 0, Amf0.encode(values.toList()))))

        fun commands(): List<List<Any?>> = received.filter { it.typeId == RtmpMessage.COMMAND_AMF0 }
            .map { Amf0.decodeAll(it.payload) }
    }

    private fun publish(split: Int = Int.MAX_VALUE, key: String = "secret"): Publisher {
        val publisher = Publisher(RtmpPublishSession(expectedStreamKey = "secret", random = { ByteArray(it) }), split)
        publisher.handshake()
        publisher.command(0, "connect", 1.0, linkedMapOf("app" to "live", "type" to "nonprivate", "tcUrl" to "rtmp://10.0.0.2:1935/live"))
        publisher.command(0, "releaseStream", 2.0, null, key)
        publisher.command(0, "FCPublish", 3.0, null, key)
        publisher.command(0, "createStream", 4.0, null)
        publisher.command(1, "publish", 5.0, null, key, "live")
        return publisher
    }

    @Test
    fun aPublisherWithTheKeyIsAcceptedAndItsMediaIsReported() {
        for (split in listOf(Int.MAX_VALUE, 1, 100)) {
            val publisher = publish(split)
            val commands = publisher.commands()
            val connectResult = commands.first { it[0] == "_result" && it[1] == 1.0 }
            assertEquals("NetConnection.Connect.Success", (connectResult[3] as Map<*, *>)["code"])
            assertEquals(1.0, commands.first { it[0] == "_result" && it[1] == 4.0 }[3])
            val status = commands.last()
            assertEquals("onStatus", status[0])
            assertEquals("NetStream.Publish.Start", (status[3] as Map<*, *>)["code"])
            assertTrue(publisher.received.any { it.typeId == RtmpMessage.SET_CHUNK_SIZE })
            assertTrue(publisher.received.any { it.typeId == RtmpMessage.WINDOW_ACK_SIZE })
            assertEquals(
                listOf(RtmpPublishEvent.Connected("live", "rtmp://10.0.0.2:1935/live"), RtmpPublishEvent.PublishStarted("live", "secret")),
                publisher.events,
            )
            assertTrue(publisher.session.isPublishing)

            publisher.events.clear()
            val metadata = Amf0.encode("@setDataFrame", "onMetaData", linkedMapOf("width" to 1280.0, "height" to 720.0))
            publisher.send(publisher.encoder.encode(4, RtmpMessage(RtmpMessage.DATA_AMF0, 1, 0, metadata)))
            val frame = ByteArray(500) { it.toByte() }
            publisher.send(publisher.encoder.encode(6, RtmpMessage(RtmpMessage.VIDEO, 1, 66, frame)))
            publisher.command(1, "FCUnpublish", 6.0, null, "secret")
            assertEquals(RtmpPublishEvent.Metadata(mapOf("width" to 1280.0, "height" to 720.0)), publisher.events[0])
            val video = publisher.events[1] as RtmpPublishEvent.Video
            assertEquals(66L, video.timestamp)
            assertContentEquals(frame, video.payload)
            assertEquals(RtmpPublishEvent.Unpublished, publisher.events[2])
            assertFalse(publisher.session.isPublishing)
        }
    }

    @Test
    fun aWrongStreamKeyIsRefusedAndClosesTheConnection() {
        val publisher = publish(key = "guess")
        val status = publisher.commands().last()
        assertEquals("NetStream.Publish.BadName", (status[3] as Map<*, *>)["code"])
        assertTrue(publisher.closed)
        assertFalse(publisher.session.isPublishing)
        // Nothing after the refusal is processed.
        publisher.send(publisher.encoder.encode(6, RtmpMessage(RtmpMessage.VIDEO, 1, 0, ByteArray(10))))
        assertTrue(publisher.events.none { it is RtmpPublishEvent.Video })
    }

    @Test
    fun mediaBeforePublishIsIgnored() {
        val publisher = Publisher(RtmpPublishSession(expectedStreamKey = "secret"))
        publisher.handshake()
        publisher.send(publisher.encoder.encode(6, RtmpMessage(RtmpMessage.VIDEO, 1, 0, ByteArray(10))))
        assertTrue(publisher.events.isEmpty())
    }

    @Test
    fun receivedBytesAreAcknowledgedAtThePeerWindow() {
        val publisher = publish()
        publisher.send(publisher.encoder.encode(2, RtmpControl.windowAckSize(1000)))
        publisher.received.clear()
        publisher.send(publisher.encoder.encode(6, RtmpMessage(RtmpMessage.VIDEO, 1, 0, ByteArray(1500))))
        val ack = publisher.received.single { it.typeId == RtmpMessage.ACKNOWLEDGEMENT }
        assertTrue(ack.payload.u32At(0) > 1500)
    }

    @Test
    fun pingRequestsAreAnswered() {
        val publisher = publish()
        publisher.received.clear()
        val ping = ByteWriter().u16(6).u32(1234).toByteArray()
        publisher.send(publisher.encoder.encode(2, RtmpMessage(RtmpMessage.USER_CONTROL, 0, 0, ping)))
        val pong = publisher.received.single { it.typeId == RtmpMessage.USER_CONTROL }
        assertContentEquals(ByteWriter().u16(7).u32(1234).toByteArray(), pong.payload)
    }
}
