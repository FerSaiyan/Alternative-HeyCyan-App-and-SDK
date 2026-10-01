package com.fersaiyan.cyanbridge.devices.eyevue

import java.io.ByteArrayInputStream
import java.io.EOFException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class EyevueRtspRelayTest {
    @Test
    fun describeBodyIsConsumedOnceAndLeavesNextResponseIntact() {
        val bytes = EyevueRtspMessage(listOf("RTSP/1.0 200 OK", "Content-Length: ${SDP.length}"), SDP.toByteArray()).bytes() +
            EyevueRtspMessage(listOf("RTSP/1.0 200 OK", "CSeq: 3")).bytes()
        val input = object : ByteArrayInputStream(bytes) {
            override fun read(target: ByteArray, offset: Int, length: Int): Int =
                super.read(target, offset, minOf(length, 3)) // TCP may split a body anywhere.
        }
        val describe = readEyevueRtspMessage(input)!!
        assertEquals(SDP, String(describe.body))
        assertEquals("3", readEyevueRtspMessage(input)!!.header("CSeq"))
        assertEquals(-1, input.read())
    }

    @Test(expected = EOFException::class)
    fun incompleteBodyDoesNotBecomeACachedSdp() {
        readEyevueRtspMessage(ByteArrayInputStream("RTSP/1.0 200 OK\r\nContent-Length: 10\r\n\r\nshort".toByteArray()))
    }

    @Test
    fun sdpKeepsAudioConfigurationAndResolvesPortAndAggregate() {
        val parsed = EyevueRtspDescription.from(EyevueRtspMessage(
            listOf("RTSP/1.0 200 OK", "Content-Base: rtsp://127.0.0.1:1234/00000005/"), SDP.toByteArray(),
        ), "rtsp://127.0.0.1:1234/xxx.mov")
        assertEquals("rtsp://127.0.0.1:1234/00000005/", parsed.aggregateUrl)
        assertEquals("rtsp://127.0.0.1:1234/00000005/track2", parsed.tracks[1].controlUrl)
        assertTrue(String(parsed.localSdp).contains("MPEG4-GENERIC/16000"))
        assertTrue(String(parsed.localSdp).contains("config=1408"))
    }

    @Test(timeout = 10_000)
    fun twoViewersReceiveVideoAudioAndClosingOneDoesNotTouchGlassesSession() {
        FakeGlasses().use { glasses ->
            val relay = RtspPlayRewriteProxy("127.0.0.1", glasses.port)
            val port = relay.start()
            try {
                TestViewer(port).use { first ->
                    TestViewer(port).use { second ->
                        first.describe()
                        second.describe()
                        first.setup(1, "RTP/AVP/TCP;unicast;interleaved=4-5")
                        first.setup(2, "RTP/AVP/TCP;unicast;interleaved=6-7")
                        second.setup(1, "RTP/AVP/TCP;unicast;interleaved=0-1")
                        second.setup(2, "RTP/AVP/TCP;unicast;interleaved=2-3")
                        assertFalse("Source PLAY must wait for local subscriptions", glasses.requests.any { it.firstLine.startsWith("PLAY ") })
                        first.play()
                        second.play()
                        assertEquals(1, glasses.connections.get())
                        assertEquals(1, glasses.requests.count { it.firstLine.startsWith("DESCRIBE ") })
                        assertEquals(2, glasses.requests.count { it.firstLine.startsWith("SETUP ") })
                        val play = glasses.requests.single { it.firstLine.startsWith("PLAY ") }
                        assertEquals("PLAY rtsp://127.0.0.1:${glasses.port}/00000005/ RTSP/1.0", play.firstLine)
                        assertNotEquals(first.session, second.session)

                        for (channel in 0..3) {
                            val payload = byteArrayOf(0x80.toByte(), (96 + channel / 2).toByte(), 0, 5, 0, 0, 0, 20)
                            glasses.emit(channel, payload)
                            assertFrame(first, channel + 4, payload)
                            assertFrame(second, channel, payload)
                        }
                        first.command("TEARDOWN", headers = listOf("Session: ${first.session}"))
                        first.close()
                        val payload = byteArrayOf(0x80.toByte(), 97, 1, 2)
                        glasses.emit(2, payload)
                        assertFrame(second, 2, payload)
                        assertFalse(glasses.requests.any { it.firstLine.startsWith("TEARDOWN ") })
                        assertEquals(1, glasses.connections.get())
                    }
                }
            } finally { relay.stop() }
        }
    }

    @Test(timeout = 10_000)
    fun udpVideoAndAudioComeFromTheAdvertisedRtpAndRtcpPorts() {
        FakeGlasses().use { glasses ->
            val relay = RtspPlayRewriteProxy("127.0.0.1", glasses.port)
            val port = relay.start()
            try {
                TestViewer(port).use { viewer ->
                    DatagramSocket(InetSocketAddress("127.0.0.1", 0)).use { rtp ->
                        DatagramSocket(InetSocketAddress("127.0.0.1", 0)).use { rtcp ->
                            rtp.soTimeout = 2_000
                            rtcp.soTimeout = 2_000
                            viewer.describe()
                            val response = viewer.setup(2, "RTP/AVP;unicast;client_port=${rtp.localPort}-${rtcp.localPort}")
                            val ports = Regex("server_port=(\\d+)-(\\d+)").find(response.header("Transport")!!)!!
                            val serverRtp = ports.groupValues[1].toInt()
                            val serverRtcp = ports.groupValues[2].toInt()
                            assertEquals(serverRtp + 1, serverRtcp)
                            viewer.play()
                            val payload = byteArrayOf(0x80.toByte(), 97, 1, 2)
                            glasses.emit(2, payload)
                            glasses.emit(3, payload)
                            for ((socket, source) in listOf(rtp to serverRtp, rtcp to serverRtcp)) {
                                val packet = DatagramPacket(ByteArray(1024), 1024)
                                socket.receive(packet)
                                assertEquals(source, packet.port)
                                assertArrayEquals(payload, packet.data.copyOf(packet.length))
                            }
                        }
                    }
                }
            } finally { relay.stop() }
        }
    }

    @Test(timeout = 10_000)
    fun stopCancelsBlockedDescribeWithoutWaitingForHandshakeLock() {
        FakeGlasses(blockDescribe = true).use { glasses ->
            val relay = RtspPlayRewriteProxy("127.0.0.1", glasses.port)
            val port = relay.start()
            TestViewer(port).use { viewer ->
                viewer.socket.getOutputStream().write(EyevueRtspMessage(listOf(
                    "DESCRIBE rtsp://127.0.0.1:$port/live/ RTSP/1.0", "CSeq: 1",
                )).bytes())
                assertTrue(glasses.describeReceived.await(2, TimeUnit.SECONDS))
                val start = System.nanoTime()
                relay.stop()
                assertTrue("Stop waited for a socket read", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 500)
                assertEquals(-1, viewer.socket.getInputStream().read())
            }
        }
    }

    @Test(timeout = 10_000)
    fun mediaWaitsForPlayResponseAndSlowViewerCannotBlockOtherViewer() {
        FakeGlasses().use { glasses ->
            val prePlayPacketProcessed = CountDownLatch(1)
            val relay = RtspPlayRewriteProxy("127.0.0.1", glasses.port, log = {
                if (it.startsWith("first upstream packet")) prePlayPacketProcessed.countDown()
            })
            val port = relay.start()
            try {
                TestViewer(port).use { slow ->
                    TestViewer(port).use { fast ->
                        slow.socket.receiveBufferSize = 1024
                        slow.describe()
                        slow.setup(1, "RTP/AVP/TCP;unicast;interleaved=0-1")
                        fast.describe()
                        fast.setup(1, "RTP/AVP/TCP;unicast;interleaved=4-5")
                        slow.play()
                        // The source is playing for slow, but fast has not sent PLAY.
                        glasses.emit(0, byteArrayOf(1, 2, 3))
                        assertTrue(prePlayPacketProcessed.await(2, TimeUnit.SECONDS))
                        fast.play() // Must still be a complete RTSP reply, not early RTP.
                        val largePacket = ByteArray(60_000) { 42 }
                        repeat(180) {
                            glasses.emit(0, largePacket)
                            assertFrame(fast, 4, largePacket)
                        }
                        assertEquals(1, glasses.connections.get())
                    }
                }
            } finally { relay.stop() }
        }
    }

    private fun assertFrame(viewer: TestViewer, channel: Int, payload: ByteArray) {
        val input = viewer.socket.getInputStream()
        assertEquals('$'.code, input.read())
        val header = readEyevueBytes(input, 3)
        assertEquals(channel, header[0].toInt() and 255)
        val size = ((header[1].toInt() and 255) shl 8) or (header[2].toInt() and 255)
        assertArrayEquals(payload, readEyevueBytes(input, size))
    }

    @Test(timeout = 10_000)
    fun firstFrameImmediatelyAfterSourcePlayIsNotDiscarded() {
        FakeGlasses(emitOnPlay = true).use { glasses ->
            val relay = RtspPlayRewriteProxy("127.0.0.1", glasses.port)
            val port = relay.start()
            try {
                TestViewer(port).use { viewer ->
                    viewer.describe()
                    viewer.setup(1, "RTP/AVP/TCP;unicast;interleaved=0-1")
                    viewer.play()
                    assertFrame(viewer, 0, FIRST_FRAME)
                }
            } finally { relay.stop() }
        }
    }

    private class TestViewer(port: Int) : AutoCloseable {
        val socket = Socket("127.0.0.1", port).apply { soTimeout = 3_000 }
        private val url = "rtsp://127.0.0.1:$port/live/"
        var session: String? = null
        private var seq = 1
        fun command(method: String, target: String = url, headers: List<String> = emptyList()): EyevueRtspMessage {
            val request = EyevueRtspMessage(listOf("$method $target RTSP/1.0", "CSeq: ${seq++}") + headers)
            socket.getOutputStream().apply { write(request.bytes()); flush() }
            return readEyevueRtspMessage(socket.getInputStream())!!.requireOk()
        }
        fun describe() {
            val response = command("DESCRIBE")
            assertEquals(url, response.header("Content-Base"))
            assertTrue(String(response.body).contains("m=audio"))
        }
        fun setup(track: Int, transport: String): EyevueRtspMessage {
            val response = command("SETUP", "${url}track$track", listOf("Transport: $transport"))
            session = response.header("Session")!!.substringBefore(';')
            return response
        }
        fun play() { command("PLAY", headers = listOf("Session: $session")) }
        override fun close() { socket.close() }
    }

    private class FakeGlasses(private val blockDescribe: Boolean = false, private val emitOnPlay: Boolean = false) : AutoCloseable {
        private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val port = server.localPort
        val connections = AtomicInteger()
        val requests = CopyOnWriteArrayList<EyevueRtspMessage>()
        val describeReceived = CountDownLatch(1)
        @Volatile private var peer: Socket? = null
        private val outputLock = Any()
        private val thread = Thread {
            try {
                val socket = server.accept()
                peer = socket
                connections.incrementAndGet()
                while (!socket.isClosed) {
                    val request = readEyevueRtspMessage(socket.getInputStream()) ?: break
                    requests.add(request)
                    val method = request.firstLine.substringBefore(' ')
                    var headers = listOf("CSeq: ${request.header("CSeq")}")
                    var body = byteArrayOf()
                    when (method) {
                        "OPTIONS" -> headers += "Public: OPTIONS, DESCRIBE, SETUP, PLAY, GET_PARAMETER"
                        "DESCRIBE" -> {
                            describeReceived.countDown()
                            if (blockDescribe) continue
                            headers += listOf("Content-Base: rtsp://127.0.0.1:$port/00000005/", "Content-Type: application/sdp")
                            body = SDP.toByteArray()
                        }
                        "SETUP" -> headers += listOf("Session: glasses;timeout=60", "Transport: ${request.header("Transport")}")
                        "PLAY", "GET_PARAMETER" -> headers += "Session: glasses"
                    }
                    val response = EyevueRtspMessage(listOf("RTSP/1.0 200 OK") + headers + "Content-Length: ${body.size}", body)
                    synchronized(outputLock) { socket.getOutputStream().apply { write(response.bytes()); flush() } }
                    if (method == "PLAY" && emitOnPlay) emit(0, FIRST_FRAME)
                }
            } catch (_: Exception) { }
        }.apply { isDaemon = true; start() }

        fun emit(channel: Int, payload: ByteArray) = synchronized(outputLock) {
            peer!!.getOutputStream().apply {
                write(byteArrayOf('$'.code.toByte(), channel.toByte(), (payload.size shr 8).toByte(), payload.size.toByte()) + payload)
                flush()
            }
        }
        override fun close() { server.close(); peer?.close(); thread.join(1_000) }
    }

    companion object {
        private val FIRST_FRAME = byteArrayOf(0x80.toByte(), 96, 0, 1, 0, 0, 0, 20, 0, 0, 0, 1, 0x65)
        private val SDP = listOf(
            "v=0", "o=- 1 1 IN IP4 0.0.0.0", "s=Nvt RTSP", "t=0 0", "a=control:*",
            "m=video 0 RTP/AVP 96", "a=rtpmap:96 H264/90000", "a=control:track1",
            "m=audio 0 RTP/AVP 97", "a=rtpmap:97 MPEG4-GENERIC/16000",
            "a=fmtp:97 streamtype=5;mode=AAC-hbr;sizelength=13;indexlength=3;indexdeltalength=3;config=1408", "a=control:track2",
        ).joinToString("\r\n", postfix = "\r\n")
    }
}
