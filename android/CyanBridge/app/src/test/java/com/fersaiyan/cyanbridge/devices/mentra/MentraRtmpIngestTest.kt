package com.fersaiyan.cyanbridge.devices.mentra

import com.fersaiyan.cyanbridge.shared.media.rtmp.Amf0
import com.fersaiyan.cyanbridge.shared.media.rtmp.ByteWriter
import com.fersaiyan.cyanbridge.shared.media.rtmp.RtmpChunkEncoder
import com.fersaiyan.cyanbridge.shared.media.rtmp.RtmpHandshake
import com.fersaiyan.cyanbridge.shared.media.rtmp.RtmpMessage
import java.io.DataInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.Socket
import java.net.URL
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The ingest and loopback player server over real loopback sockets, with a scripted publisher. */
@RunWith(RobolectricTestRunner::class)
class MentraRtmpIngestTest {
    private val loopback = InetAddress.getByName("127.0.0.1")
    private val player = LoopbackTsServer("token")
    private val ingest = MentraRtmpIngest(loopback, "key", player)

    @After fun tearDown() {
        ingest.stop()
        player.stop()
    }

    private class Publisher(port: Int) : AutoCloseable {
        val socket = Socket(InetAddress.getByName("127.0.0.1"), port).apply { soTimeout = 5_000 }
        private val out = socket.getOutputStream()
        private val input = DataInputStream(socket.getInputStream())
        private val encoder = RtmpChunkEncoder()

        fun handshake() {
            out.write(ByteArray(RtmpHandshake.C0C1_SIZE).also { it[0] = 3 })
            input.readFully(ByteArray(1 + 2 * RtmpHandshake.SIGNATURE_SIZE))
            out.write(ByteArray(RtmpHandshake.SIGNATURE_SIZE))
        }

        fun command(streamId: Int, vararg values: Any?) =
            send(3, RtmpMessage(RtmpMessage.COMMAND_AMF0, streamId, 0, Amf0.encode(values.toList())))

        fun send(chunkStream: Int, message: RtmpMessage) {
            out.write(encoder.encode(chunkStream, message))
            out.flush()
        }

        fun publish(key: String) {
            handshake()
            command(0, "connect", 1.0, linkedMapOf("app" to "live"))
            command(0, "createStream", 2.0, null)
            command(1, "publish", 3.0, null, key, "live")
        }

        override fun close() = socket.close()
    }

    private fun sequenceHeader(): ByteArray {
        val sps = byteArrayOf(0x67, 0x42, 0xC0.toByte(), 0x1F, 0x11)
        val pps = byteArrayOf(0x68, 0xCE.toByte(), 0x3C, 0x80.toByte())
        return ByteWriter().u8(0x17).u8(0).u24(0).u8(1).u8(0x42).u8(0xC0).u8(0x1F).u8(0xFF)
            .u8(0xE1).u16(sps.size).bytes(sps).u8(1).u16(pps.size).bytes(pps).toByteArray()
    }

    private fun keyframe(): ByteArray {
        val idr = ByteArray(400) { if (it == 0) 0x65 else 7 }
        return ByteWriter().u8(0x17).u8(1).u24(0).u32(idr.size.toLong()).bytes(idr).toByteArray()
    }

    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "timed out" }
            Thread.sleep(20)
        }
    }

    @Test
    fun aPublishedKeyframeReachesTheLocalPlayerAsTransportStream() {
        val url = player.start()
        val port = ingest.start(preferredPort = 0)
        Publisher(port).use { publisher ->
            publisher.publish("key")
            waitFor { ingest.publishing.value }
            publisher.send(6, RtmpMessage(RtmpMessage.VIDEO, 1, 0, sequenceHeader()))
            publisher.send(6, RtmpMessage(RtmpMessage.VIDEO, 1, 40, keyframe()))
            // The ingest thread remuxes asynchronously; a later viewer still gets the cached keyframe.
            Thread.sleep(500)

            val connection = URL(url).openConnection() as HttpURLConnection
            connection.readTimeout = 5_000
            assertEquals(200, connection.responseCode)
            assertEquals("video/mp2t", connection.contentType)
            val packets = readFully(connection.inputStream, 3 * 188)
            // PAT, PMT, then the video PID, each 188 bytes and sync-aligned.
            assertEquals(0x47, packets[0].toInt() and 0xFF)
            assertEquals(0x00, packets[2].toInt() and 0xFF)
            assertEquals(0x47, packets[188].toInt() and 0xFF)
            assertEquals(0x10, packets[189].toInt() and 0x1F)
            assertEquals(0x47, packets[376].toInt() and 0xFF)
            assertEquals(0x01, packets[377].toInt() and 0x1F)
            connection.disconnect()
        }
    }

    @Test
    fun aWrongKeyIsDisconnectedAndOtherPathsAreNotServed() {
        val url = player.start()
        val port = ingest.start(preferredPort = 0)
        Publisher(port).use { publisher ->
            publisher.publish("guess")
            // The ingest answers BadName and closes; reading reaches end of stream.
            val input = publisher.socket.getInputStream()
            val deadline = System.currentTimeMillis() + 5_000
            while (input.read() >= 0) check(System.currentTimeMillis() < deadline)
            assertTrue(!ingest.publishing.value)
        }
        val other = URL(url.replace("token.ts", "other.ts")).openConnection() as HttpURLConnection
        assertEquals(404, other.responseCode)
    }

    private fun readFully(input: InputStream, count: Int): ByteArray {
        val data = ByteArray(count)
        DataInputStream(input).readFully(data)
        return data
    }
}
