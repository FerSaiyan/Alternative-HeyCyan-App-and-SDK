package com.fersaiyan.cyanbridge.devices.eyevue

import android.util.Log
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Single-session RTSP fan-out relay for the EyeVue live stream.
 *
 * The glasses box effectively serves one stream session: every new handshake
 * starves the previous viewer, so two viewers (inline player + VLC) murder
 * each other on alternating cycles. This relay holds exactly ONE upstream
 * session with the glasses (both tracks, like the vendor) and fans the
 * picture out to any number of local viewers. Viewers only ever talk to the
 * relay - describe, setup, play and pings are answered locally - so no viewer
 * can disturb another or the box. If the upstream stalls, the relay re-opens
 * it transparently while viewers just see a brief freeze.
 */
internal class RtspPlayRewriteProxy(
    private val upstreamHost: String,
    private val upstreamPort: Int,
) {
    private val tag = "EyevueRtspProxy"
    private var serverSocket: ServerSocket? = null
    private val active = AtomicBoolean(false)
    private var acceptThread: Thread? = null
    private var watchdogThread: Thread? = null

    // ---- shared upstream session ----
    private val upLock = Any()
    @Volatile private var upSock: Socket? = null
    private var upSession: String? = null
    private var upBasePath: String? = null
    private var upSdpVideoOnly: String? = null
    @Volatile private var upStreaming = false
    private val upCseq = AtomicInteger(1)
    @Volatile private var lastRtpAt = 0L
    @Volatile private var lastVideoSeq = 0
    @Volatile private var lastVideoRtptime = 0L
    private var upGeneration = 0

    // ---- viewers ----
    private class TcpViewer(val out: java.io.OutputStream, var ch0: Int = 0, var ch1: Int = 1)
    private val tcpViewers = CopyOnWriteArrayList<TcpViewer>()
    private class UdpViewer(
        val rtcpRecvSock: DatagramSocket,
        val clientRtp: Int,
        val clientRtcp: Int,
        @Volatile var playing: Boolean = false,
    )
    private val udpViewers = CopyOnWriteArrayList<UdpViewer>()
    private val clientSockets = CopyOnWriteArrayList<Socket>()
    private var udpSendSock: DatagramSocket? = null

    companion object {
        private const val RELAY_SESSION = "52454C4159"
        private const val PUBLIC =
            "OPTIONS, DESCRIBE, SETUP, TEARDOWN, PLAY, PAUSE, GET_PARAMETER, SET_PARAMETER"
    }

    fun start(): Int {
        val server = ServerSocket()
        server.reuseAddress = true
        server.bind(InetSocketAddress("127.0.0.1", 0))
        serverSocket = server
        active.set(true)
        acceptThread = Thread({
            while (active.get()) {
                try {
                    val client = server.accept()
                    Log.i(tag, "relay viewer ${client.inetAddress?.hostAddress} -> $upstreamHost:$upstreamPort")
                    clientSockets.add(client)
                    Thread({ handleClient(client) }, "eyevue-rtsp-relay").apply {
                        isDaemon = true
                        start()
                    }
                } catch (_: Exception) {
                    if (!active.get()) return@Thread
                }
            }
        }, "eyevue-rtsp-accept").apply {
            isDaemon = true
            start()
        }
        watchdogThread = Thread({
            while (active.get()) {
                try {
                    Thread.sleep(5_000)
                } catch (_: InterruptedException) {
                    return@Thread
                }
                if (active.get() && upStreaming && lastRtpAt > 0 &&
                    System.currentTimeMillis() - lastRtpAt > 12_000
                ) {
                    Log.w(tag, "upstream stall, reopening transparently")
                    synchronized(upLock) {
                        runCatching { upSock?.close() }
                        upSock = null
                        upStreaming = false
                    }
                    ensureUpstream()
                }
            }
        }, "eyevue-rtsp-watchdog").apply {
            isDaemon = true
            start()
        }
        return server.localPort
    }

    fun stop() {
        active.set(false)
        runCatching { serverSocket?.close() }
        serverSocket = null
        // Lock-free: never wait on the upstream lock here, it can be held by
        // a long blocking handshake. Closing dead sockets unblocks readers.
        clientSockets.forEach { runCatching { it.close() } }
        clientSockets.clear()
        upSock?.let { runCatching { it.close() } }
        upSock = null
        upStreaming = false
        tcpViewers.clear()
        udpViewers.forEach { runCatching { it.rtcpRecvSock.close() } }
        udpViewers.clear()
        runCatching { udpSendSock?.close() }
        udpSendSock = null
    }

    private fun localPort(): Int = serverSocket?.localPort ?: 0

    private fun localBase(): String = "rtsp://127.0.0.1:${localPort()}${upBasePath ?: "/xxx.mov/"}"

    // ---------------- upstream ----------------

    /** Opens (or reuses) the single shared session with the glasses. */
    private fun ensureUpstream(): Boolean {
        synchronized(upLock) {
            if (upStreaming && upSock?.isConnected == true) return true
            var lastError: Exception? = null
            // The box is occasionally slow or wedged after a long day of
            // sessions; retry the handshake a few times before giving up.
            repeat(3) { attempt ->
                if (!active.get()) return false
                runCatching { upSock?.close() }
                upSock = null
                upStreaming = false
                try {
                    openUpstreamSession()
                    return true
                } catch (error: Exception) {
                    if (!active.get()) return false
                    lastError = error
                    Log.w(tag, "upstream handshake ${attempt + 1}/3 failed: ${error.message}")
                    runCatching { upSock?.close() }
                    upSock = null
                    upStreaming = false
                    try {
                        Thread.sleep(2_000)
                    } catch (_: InterruptedException) {
                        return false
                    }
                }
            }
            Log.w(tag, "upstream handshake giving up: ${lastError?.message}")
            return false
        }
    }

    @Throws(IOException::class)
    private fun openUpstreamSession() {
        val sock = Socket()
        upSock = sock
        sock.connect(InetSocketAddress(upstreamHost, upstreamPort), 5_000)
        sock.soTimeout = 20_000
        val out = sock.getOutputStream()
        val input = sock.getInputStream()
        // Mirror a plain client handshake: OPTIONS first, then DESCRIBE
        // without an explicit port, both with a user agent. The box answered
        // exactly this shape all day; a bare DESCRIBE with explicit port
        // gets silence. DESCRIBE mints a fresh aggregate name.
        val options = listOf(
            "OPTIONS rtsp://$upstreamHost/xxx.mov RTSP/1.0",
            "CSeq: ${upCseq.getAndIncrement()}",
            "User-Agent: LibVLC/3.0.23 (LIVE555 Streaming Media v2016.11.28)",
            "",
        )
        writeLines(out, options)
        out.flush()
        Log.i(tag, "upstream OPTIONS sent, awaiting reply")
        readResponse(input)
        Log.i(tag, "upstream OPTIONS reply ok")
        val describe = listOf(
            "DESCRIBE rtsp://$upstreamHost/xxx.mov RTSP/1.0",
            "CSeq: ${upCseq.getAndIncrement()}",
            "User-Agent: LibVLC/3.0.23 (LIVE555 Streaming Media v2016.11.28)",
            "Accept: application/sdp",
            "",
        )
                writeLines(out, describe)
                out.flush()
                Log.i(tag, "upstream DESCRIBE sent, awaiting reply")
                val descResp = readResponse(input) ?: throw IOException("no DESCRIBE reply")
                Log.i(tag, "upstream DESCRIBE reply: ${descResp.first.firstOrNull()}")
                val base = descResp.first.firstOrNull {
                    it.startsWith("Content-Base:", ignoreCase = true)
                }?.substringAfter(':')?.trim()
                    ?: throw IOException("no Content-Base")
                upBasePath = base.substringAfter("://$upstreamHost").substringAfter("://$upstreamHost:$upstreamPort")
                    .takeIf { it.startsWith("/") } ?: "/"
                val sdpLen = contentLengthOf(descResp.first)
                Log.i(tag, "upstream SDP body declared bytes=$sdpLen")
                val sdp = readBytes(input, sdpLen)
                upSdpVideoOnly = stripAudio(String(sdp, Charsets.US_ASCII))
                Log.i(tag, "upstream aggregate $base")
                // SETUP both tracks like the vendor (picture + sound); only the
                // picture is fanned out, the sound subscription keeps the session.
                // Every request carries a user agent: the box answers agented
                // requests and hangs on bare ones.
                val setup1 = listOf(
                    "SETUP ${base}track1 RTSP/1.0",
                    "CSeq: ${upCseq.getAndIncrement()}",
                    "User-Agent: LibVLC/3.0.23 (LIVE555 Streaming Media v2016.11.28)",
                    "Transport: RTP/AVP/TCP;unicast;interleaved=0-1",
                    "",
                )
                writeLines(out, setup1)
                out.flush()
                Log.i(tag, "upstream SETUP track1 sent, awaiting reply")
                val setupResp = readResponse(input) ?: throw IOException("no SETUP reply")
                Log.i(tag, "upstream SETUP track1 reply: ${setupResp.first.firstOrNull()}")
                val session = setupResp.first.firstOrNull {
                    it.startsWith("Session:", ignoreCase = true)
                }?.substringAfter(':')?.trim()?.substringBefore(';')?.trim()
                    ?: throw IOException("no Session")
                upSession = session
                val setup2 = listOf(
                    "SETUP ${base}track2 RTSP/1.0",
                    "CSeq: ${upCseq.getAndIncrement()}",
                    "User-Agent: LibVLC/3.0.23 (LIVE555 Streaming Media v2016.11.28)",
                    "Session: $session",
                    "Transport: RTP/AVP/TCP;unicast;interleaved=2-3",
                    "",
                )
                writeLines(out, setup2)
                out.flush()
                Log.i(tag, "upstream SETUP track2 sent, awaiting reply")
                readResponse(input)
                Log.i(tag, "upstream SETUP track2 reply ok")
                val play = listOf(
                    "PLAY $base RTSP/1.0",
                    "CSeq: ${upCseq.getAndIncrement()}",
                    "User-Agent: LibVLC/3.0.23 (LIVE555 Streaming Media v2016.11.28)",
                    "Session: $session",
                    "Range: npt=0.000-",
                    "",
                )
                writeLines(out, play)
                out.flush()
                Log.i(tag, "upstream PLAY sent, awaiting reply")
                readResponse(input)
                Log.i(tag, "upstream PLAY reply ok")
                upStreaming = true
                lastRtpAt = 0L
                upGeneration++
                startUpstreamPump(sock, upGeneration)
                Log.i(tag, "upstream streaming session $session")
    }

    private fun startUpstreamPump(sock: Socket, generation: Int) {
        Thread({
            val input = sock.getInputStream()
            try {
                while (active.get() && generation == upGeneration && !sock.isClosed) {
                    val first = input.read()
                    if (first < 0) break
                    if (first != '$'.code) {
                        // Server-initiated message (e.g. keep-alive ping): answer.
                        val headers = readHeaders(input, first) ?: break
                        answerUpstreamPing(sock, headers)
                        continue
                    }
                    val channel = input.read()
                    val hi = input.read()
                    val lo = input.read()
                    if (channel < 0 || hi < 0 || lo < 0) break
                    val length = (hi shl 8) or lo
                    val payload = readBytes(input, length)
                    if (payload.size < length) break
                    lastRtpAt = System.currentTimeMillis()
                    when (channel) {
                        0 -> {
                            if (payload.size >= 8) {
                                lastVideoSeq = ((payload[2].toInt() and 0xFF) shl 8) or
                                    (payload[3].toInt() and 0xFF)
                                lastVideoRtptime =
                                    ((payload[4].toLong() and 0xFF) shl 24) or
                                    ((payload[5].toLong() and 0xFF) shl 16) or
                                    ((payload[6].toLong() and 0xFF) shl 8) or
                                    (payload[7].toLong() and 0xFF)
                            }
                            fanOutTcp(channel, payload)
                            fanOutUdpRtp(payload)
                        }
                        1 -> {
                            fanOutTcp(channel, payload)
                            fanOutUdpRtcp(payload)
                        }
                        else -> Unit // sound channels consumed, not fanned out
                    }
                }
            } catch (_: Exception) {
            } finally {
                synchronized(upLock) {
                    if (generation == upGeneration) {
                        upStreaming = false
                        if (upSock === sock) upSock = null
                    }
                }
            }
        }, "eyevue-rtsp-upstream").apply { isDaemon = true; start() }
    }

    private fun answerUpstreamPing(sock: Socket, headers: List<String>) {
        val first = headers.firstOrNull() ?: return
        Log.i(tag, "upstream ping: $first")
        if (!first.startsWith("GET_PARAMETER", ignoreCase = true)) return
        val cseq = headers.firstOrNull { it.startsWith("CSeq:", ignoreCase = true) } ?: "CSeq: 0"
        val session = headers.firstOrNull { it.startsWith("Session:", ignoreCase = true) }
        val lines = mutableListOf("RTSP/1.0 200 OK", cseq)
        if (session != null) lines.add(session)
        lines.add("")
        synchronized(upLock) {
            runCatching {
                writeLines(sock.getOutputStream(), lines)
                sock.getOutputStream().flush()
            }
        }
    }

    private fun fanOutTcp(channel: Int, payload: ByteArray) {
        for (viewer in tcpViewers) {
            try {
                synchronized(viewer.out) {
                    viewer.out.write('$'.code)
                    viewer.out.write(if (channel == 0) viewer.ch0 else viewer.ch1)
                    viewer.out.write((payload.size shr 8) and 0xFF)
                    viewer.out.write(payload.size and 0xFF)
                    viewer.out.write(payload)
                    viewer.out.flush()
                }
            } catch (_: Exception) {
                tcpViewers.remove(viewer)
            }
        }
    }

    private fun fanOutUdpRtp(payload: ByteArray) {
        val sendSock = synchronized(upLock) {
            if (udpSendSock == null || udpSendSock?.isClosed == true) {
                udpSendSock = runCatching { DatagramSocket() }.getOrNull()
            }
            udpSendSock
        } ?: return
        for (viewer in udpViewers) {
            if (!viewer.playing) continue
            try {
                val packet = DatagramPacket(
                    payload, payload.size,
                    InetAddress.getByName("127.0.0.1"), viewer.clientRtp,
                )
                synchronized(sendSock) { sendSock.send(packet) }
            } catch (_: Exception) {
            }
        }
    }

    private fun fanOutUdpRtcp(payload: ByteArray) {
        val sendSock = synchronized(upLock) { udpSendSock } ?: return
        for (viewer in udpViewers) {
            if (!viewer.playing) continue
            try {
                val packet = DatagramPacket(
                    payload, payload.size,
                    InetAddress.getByName("127.0.0.1"), viewer.clientRtcp,
                )
                synchronized(sendSock) { sendSock.send(packet) }
            } catch (_: Exception) {
            }
        }
    }

    /** Forwards one viewer's control-channel bytes upstream (RTCP reports). */
    private fun forwardUpstreamInterleaved(channel: Int, payload: ByteArray) {
        synchronized(upLock) {
            val sock = upSock
            if (!upStreaming || sock == null || sock.isClosed) return
            runCatching {
                val out = sock.getOutputStream()
                out.write('$'.code)
                out.write(channel)
                out.write((payload.size shr 8) and 0xFF)
                out.write(payload.size and 0xFF)
                out.write(payload)
                out.flush()
            }
        }
    }

    // ---------------- downstream (viewers) ----------------

    private fun handleClient(client: Socket) {
        var tcpViewer: TcpViewer? = null
        var pendingCh0 = 0
        var pendingCh1 = 1
        val ownedUdp = mutableListOf<UdpViewer>()
        try {
            val input = client.getInputStream()
            val out = client.getOutputStream()
            while (active.get() && !client.isClosed) {
                val first = input.read()
                if (first < 0) break
                if (first == '$'.code) {
                    val channel = input.read()
                    val hi = input.read()
                    val lo = input.read()
                    if (channel < 0 || hi < 0 || lo < 0) break
                    val payload = readBytes(input, (hi shl 8) or lo)
                    forwardUpstreamInterleaved(channel, payload)
                    continue
                }
                val headers = readHeaders(input, first) ?: break
                val requestLine = headers.firstOrNull() ?: break
                Log.i(tag, "viewer: $requestLine")
                val parts = requestLine.split(" ")
                val method = parts.getOrNull(0)?.uppercase() ?: break
                val cseq = headers.firstOrNull { it.startsWith("CSeq:", ignoreCase = true) } ?: "CSeq: 0"
                when (method) {
                    "OPTIONS", "GET_PARAMETER" -> {
                        val session = headers.firstOrNull { it.startsWith("Session:", ignoreCase = true) }
                        val lines = mutableListOf("RTSP/1.0 200 OK", cseq)
                        if (session != null) lines.add(session)
                        if (method == "OPTIONS") lines.add("Public: $PUBLIC")
                        lines.add("")
                        writeLines(out, lines)
                        out.flush()
                    }
                    "DESCRIBE" -> {
                        if (!ensureUpstream() || upSdpVideoOnly == null) {
                            writeLines(out, listOf("RTSP/1.0 503 Service Unavailable", cseq, ""))
                            out.flush()
                        } else {
                            val body = upSdpVideoOnly!!.toByteArray(Charsets.US_ASCII)
                            writeLines(
                                out,
                                listOf(
                                    "RTSP/1.0 200 OK",
                                    cseq,
                                    "Content-Base: ${localBase()}",
                                    "Content-Type: application/sdp",
                                    "Content-Length: ${body.size}",
                                    "",
                                ),
                            )
                            out.write(body)
                            out.flush()
                        }
                    }
                    "SETUP" -> {
                        val transport = headers.firstOrNull {
                            it.startsWith("Transport:", ignoreCase = true)
                        } ?: ""
                        if (transport.contains("/TCP", ignoreCase = true)) {
                            val m = Regex("interleaved=(\\d+)-(\\d+)").find(transport)
                            pendingCh0 = m?.groupValues?.get(1)?.toIntOrNull() ?: 0
                            pendingCh1 = m?.groupValues?.get(2)?.toIntOrNull() ?: 1
                            if (!ensureUpstream()) {
                                writeLines(out, listOf("RTSP/1.0 503 Service Unavailable", cseq, ""))
                            } else {
                                tcpViewer?.let { tcpViewers.remove(it) }
                                tcpViewer = TcpViewer(out, pendingCh0, pendingCh1).also {
                                    tcpViewers.add(it)
                                }
                                writeLines(
                                    out,
                                    listOf(
                                        "RTSP/1.0 200 OK",
                                        cseq,
                                        "Transport: RTP/AVP/TCP;unicast;interleaved=$pendingCh0-$pendingCh1",
                                        "Session: $RELAY_SESSION",
                                        "",
                                    ),
                                )
                            }
                            out.flush()
                        } else {
                            val viewer = setupUdpViewer(transport, out, cseq)
                            if (viewer != null) ownedUdp.add(viewer)
                        }
                    }
                    "PLAY" -> {
                        if (!ensureUpstream()) {
                            writeLines(out, listOf("RTSP/1.0 503 Service Unavailable", cseq, ""))
                        } else {
                            ownedUdp.forEach { it.playing = true }
                            writeLines(
                                out,
                                listOf(
                                    "RTSP/1.0 200 OK",
                                    cseq,
                                    "Range: npt=0.000-",
                                    "Session: $RELAY_SESSION",
                                    "RTP-Info: url=${localBase()}track1;seq=$lastVideoSeq;rtptime=$lastVideoRtptime",
                                    "",
                                ),
                            )
                        }
                        out.flush()
                    }
                    "TEARDOWN" -> {
                        tcpViewer?.let { tcpViewers.remove(it) }
                        tcpViewer = null
                        ownedUdp.forEach {
                            it.playing = false
                            runCatching { it.rtcpRecvSock.close() }
                            udpViewers.remove(it)
                        }
                        ownedUdp.clear()
                        writeLines(out, listOf("RTSP/1.0 200 OK", cseq, "Session: $RELAY_SESSION", ""))
                        out.flush()
                    }
                    else -> {
                        writeLines(out, listOf("RTSP/1.0 501 Not Implemented", cseq, ""))
                        out.flush()
                    }
                }
            }
        } catch (_: Exception) {
        } finally {
            tcpViewer?.let { tcpViewers.remove(it) }
            ownedUdp.forEach {
                runCatching { it.rtcpRecvSock.close() }
                udpViewers.remove(it)
            }
            clientSockets.remove(client)
            runCatching { client.close() }
        }
    }

    /**
     * UDP viewer (e.g. VLC): binds a return path for its control packets and
     * answers setup locally. Media itself is fanned out from the shared
     * picture, so its ports stay valid whatever the box does.
     */
    private fun setupUdpViewer(
        transportLine: String,
        out: java.io.OutputStream,
        cseq: String,
    ): UdpViewer? {
        val match = Regex("client_port=(\\d+)-(\\d+)", RegexOption.IGNORE_CASE).find(transportLine)
        val clientRtp = match?.groupValues?.get(1)?.toIntOrNull()
        val clientRtcp = match?.groupValues?.get(2)?.toIntOrNull()
        if (clientRtp == null || clientRtcp == null) {
            writeLines(out, listOf("RTSP/1.0 400 Bad Request", cseq, ""))
            out.flush()
            return null
        }
        if (!ensureUpstream()) {
            writeLines(out, listOf("RTSP/1.0 503 Service Unavailable", cseq, ""))
            out.flush()
            return null
        }
        return try {
            val recvSock = DatagramSocket()
            val viewer = UdpViewer(recvSock, clientRtp, clientRtcp)
            udpViewers.add(viewer)
            writeLines(
                out,
                listOf(
                    "RTSP/1.0 200 OK",
                    cseq,
                    "Transport: RTP/AVP;unicast;client_port=$clientRtp-$clientRtcp;" +
                        "server_port=${recvSock.localPort};source=127.0.0.1",
                    "Session: $RELAY_SESSION",
                    "",
                ),
            )
            out.flush()
            Log.i(tag, "UDP viewer control path via relay ${recvSock.localPort}")
            startUdpControlPump(viewer)
            viewer
        } catch (error: Exception) {
            Log.w(tag, "UDP viewer setup failed: ${error.message}")
            writeLines(out, listOf("RTSP/1.0 500 Internal Server Error", cseq, ""))
            out.flush()
            null
        }
    }

    /** Forwards one UDP viewer's control packets upstream as channel 1. */
    private fun startUdpControlPump(viewer: UdpViewer) {
        Thread({
            val buffer = ByteArray(64 * 1024)
            while (active.get() && !viewer.rtcpRecvSock.isClosed) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    viewer.rtcpRecvSock.receive(packet)
                    val payload = packet.data.copyOfRange(packet.offset, packet.offset + packet.length)
                    forwardUpstreamInterleaved(1, payload)
                } catch (_: Exception) {
                    if (viewer.rtcpRecvSock.isClosed) return@Thread
                }
            }
        }, "eyevue-udp-control").apply { isDaemon = true; start() }
    }

    // ---------------- helpers ----------------

    private fun stripAudio(sdp: String): String {
        val audioAt = sdp.indexOf("m=audio")
        return if (audioAt >= 0) {
            sdp.substring(0, audioAt).trimEnd('\r', '\n') + "\r\n"
        } else {
            sdp
        }
    }

    private fun readResponse(input: InputStream): Pair<List<String>, ByteArray>? {
        val headers = readHeaders(input) ?: return null
        val length = contentLengthOf(headers)
        val body = if (length > 0) readBytes(input, length) else ByteArray(0)
        return headers to body
    }

    private fun readBytes(input: InputStream, count: Int): ByteArray {
        if (count <= 0) return ByteArray(0)
        val out = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val read = input.read(out, offset, count - offset)
            if (read <= 0) break
            offset += read
        }
        return if (offset < count) out.copyOf(offset) else out
    }

    private fun readHeaders(input: InputStream, firstByte: Int? = null): List<String>? {
        val lines = mutableListOf<String>()
        val current = StringBuilder()
        if (firstByte != null) current.append(firstByte.toChar())
        while (true) {
            val byte = try {
                input.read()
            } catch (_: Exception) {
                return null
            }
            if (byte < 0) return if (lines.isEmpty() && current.isEmpty()) null else lines
            if (byte == '\n'.code) {
                var line = current.toString()
                if (line.endsWith("\r")) line = line.dropLast(1)
                current.clear()
                if (line.isEmpty()) {
                    lines.add("")
                    return lines
                }
                lines.add(line)
            } else {
                current.append(byte.toChar())
            }
            if (current.length > 16_384) return null
        }
    }

    private fun contentLengthOf(headers: List<String>): Int {
        val line = headers.firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
            ?: return 0
        return line.substringAfter(':').trim().toIntOrNull()?.coerceAtLeast(0) ?: 0
    }

    private fun writeLines(output: OutputStream, headers: List<String>) {
        val text = headers.joinToString("\r\n") + "\r\n"
        output.write(text.toByteArray(Charsets.US_ASCII))
    }
}
