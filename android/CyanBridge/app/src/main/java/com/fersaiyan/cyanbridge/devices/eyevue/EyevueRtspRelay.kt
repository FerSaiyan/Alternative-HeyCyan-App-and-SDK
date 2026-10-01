package com.fersaiyan.cyanbridge.devices.eyevue

import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * One glasses RTSP session, shared by independent loopback clients. Both RTP
 * tracks are preserved. Client control/RTCP never tears down the glasses session.
 * Network reads have one owner; slow viewers have bounded, independent writers.
 */
internal class RtspPlayRewriteProxy(
    private val upstreamHost: String,
    private val upstreamPort: Int,
    private val streamPath: String = "/xxx.mov",
    private val log: (String) -> Unit = {},
) {
    private val active = AtomicBoolean(false)
    private val upstreamLock = Any()
    private val upstreamWriteLock = Any()
    private val cseq = AtomicInteger(1)
    private val sessionIds = AtomicInteger(1)
    private val clients = CopyOnWriteArrayList<Viewer>()
    @Volatile private var server: ServerSocket? = null
    @Volatile private var upstream: Socket? = null
    @Volatile private var description: EyevueRtspDescription? = null
    @Volatile private var upstreamSession: String? = null
    private var streaming = false // Guarded by upstreamLock.
    private var keepaliveMethod = "OPTIONS"
    private var keepalive: Thread? = null
    @Volatile private var lastVideoPacketAt = 0L
    private val loopback = InetAddress.getByName("127.0.0.1")
    private val upstreamUrl: String get() = "rtsp://$upstreamHost" +
        (if (upstreamPort == 554) "" else ":$upstreamPort") + streamPath
    private val localBase: String get() = "rtsp://127.0.0.1:${server?.localPort}/live/"

    fun start(): Int {
        check(active.compareAndSet(false, true))
        val listener = ServerSocket().apply { bind(InetSocketAddress(loopback, 0)) }
        server = listener
        worker("accept") {
            while (active.get()) {
                try {
                    val socket = listener.accept().apply { tcpNoDelay = true }
                    val viewer = Viewer(socket)
                    clients.add(viewer)
                    if (!active.get() || viewer.closed.get()) {
                        viewer.close()
                        clients.remove(viewer)
                    } else worker("client") { handleClient(viewer) }
                } catch (error: IOException) {
                    if (active.get()) log("accept failed: ${error.message}")
                    break
                }
            }
        }
        return listener.localPort
    }

    /** No handshake lock, thread join or socket write on the caller (UI) thread. */
    fun stop() {
        active.set(false)
        runCatching { server?.close() }
        server = null
        keepalive?.interrupt()
        clients.forEach { it.close() }
        val socket = upstream
        val session = upstreamSession
        val aggregate = description?.aggregateUrl
        if (socket != null && session != null && aggregate != null) {
            // Best-effort RTSP teardown, with a separate deadline closer. Neither
            // a socket write nor its write lock can delay the Stop button.
            worker("teardown") {
                try { sendUpstream(socket, request("TEARDOWN", aggregate, listOf("Session: $session"))) }
                catch (_: IOException) { }
                finally { runCatching { socket.close() } }
            }
            worker("close-deadline") {
                Thread.sleep(250)
                runCatching { socket.close() }
            }
        } else {
            // Cancel a pending DESCRIBE/SETUP immediately.
            runCatching { socket?.close() }
        }
        upstream = null
        description = null
        upstreamSession = null
    }

    private fun ensureUpstream(): EyevueRtspDescription = synchronized(upstreamLock) {
        description?.let { return@synchronized it }
        if (!active.get()) throw IOException("Relay stopped")
        val socket = Socket()
        upstream = socket // Publish before connect/read so Stop can cancel the handshake.
        try {
            if (!active.get()) throw IOException("Relay stopped")
            socket.connect(InetSocketAddress(upstreamHost, upstreamPort), 5_000)
            socket.soTimeout = 10_000
            socket.tcpNoDelay = true
            val options = exchange(socket, "OPTIONS", upstreamUrl)
            val describe = exchange(socket, "DESCRIBE", upstreamUrl, listOf("Accept: application/sdp"))
            // exchange already read the body. Never read it a second time.
            val parsed = EyevueRtspDescription.from(describe, upstreamUrl)
            log("SDP received ${describe.body.size} bytes, ${parsed.tracks.size} tracks")
            var session: String? = null
            parsed.tracks.forEach { track ->
                val headers = mutableListOf("Transport: RTP/AVP/TCP;unicast;interleaved=${track.rtpChannel}-${track.rtcpChannel}")
                session?.let { headers.add("Session: $it") }
                val response = exchange(socket, "SETUP", track.controlUrl, headers)
                session = response.header("Session")?.substringBefore(';') ?: session
                val transport = response.header("Transport").orEmpty()
                if (!transport.contains("interleaved=${track.rtpChannel}-${track.rtcpChannel}")) {
                    throw IOException("Unexpected glasses transport: $transport")
                }
            }
            val sessionId = session ?: throw IOException("Missing glasses RTSP Session")
            if (!active.get()) throw IOException("Relay stopped")
            upstreamSession = sessionId
            description = parsed
            streaming = false
            keepaliveMethod = if (options.header("Public").orEmpty().contains("GET_PARAMETER", ignoreCase = true)) "GET_PARAMETER" else "OPTIONS"
            // Start PLAY only once a local player is subscribed. Otherwise the
            // first H264 keyframe may be discarded during local DESCRIBE/SETUP.
            log("glasses session prepared: $sessionId")
            parsed
        } catch (error: Exception) {
            runCatching { socket.close() }
            if (upstream === socket) upstream = null
            log("glasses handshake failed: ${error.message}")
            throw error
        }
    }

    private fun play(viewer: Viewer, message: EyevueRtspMessage) = synchronized(upstreamLock) {
        val parsed = ensureUpstream()
        val socket = upstream ?: throw IOException("Glasses disconnected")
        val session = upstreamSession ?: throw IOException("Missing glasses Session")
        if (!streaming) {
            exchange(socket, "PLAY", parsed.aggregateUrl, listOf("Session: $session", "Range: npt=0.000-"))
        }
        // Serialize the complete PLAY response before any RTP, including the
        // very first keyframe already buffered in the upstream socket.
        viewer.reply(message, headers = listOf("Session: ${viewer.session}", "Range: npt=0.000-"))
        viewer.playing = true
        if (!streaming) {
            streaming = true
            lastVideoPacketAt = System.nanoTime()
            val input = socket.getInputStream()
            val videoChannel = parsed.tracks.firstOrNull { it.mediaType == "video" }?.rtpChannel
            worker("upstream") { pumpUpstream(socket, input, videoChannel) }
            val ping = keepaliveMethod
            keepalive = worker("keepalive") {
                try {
                    while (active.get() && upstream === socket && !socket.isClosed) {
                        Thread.sleep(5_000)
                        if (upstream === socket && !socket.isClosed) {
                            if (videoChannel != null && System.nanoTime() - lastVideoPacketAt > 15_000_000_000L) {
                                log("no video RTP for 15 seconds; closing stalled session")
                                socket.close()
                                break
                            }
                            sendUpstream(socket, request(ping, parsed.aggregateUrl, listOf("Session: $session")))
                        }
                    }
                } catch (_: InterruptedException) {
                } catch (error: IOException) {
                    log("keepalive failed: ${error.message}")
                    runCatching { socket.close() }
                }
            }
            log("glasses session playing: $session")
        }
    }

    private fun request(method: String, url: String, headers: List<String> = emptyList()) =
        EyevueRtspMessage(listOf("$method $url RTSP/1.0", "CSeq: ${cseq.getAndIncrement()}",
            "User-Agent: CyanBridge LibVLC relay") + headers)

    private fun sendUpstream(socket: Socket, message: EyevueRtspMessage) = synchronized(upstreamWriteLock) {
        socket.getOutputStream().apply { write(message.bytes()); flush() }
    }

    private fun exchange(socket: Socket, method: String, url: String, headers: List<String> = emptyList()): EyevueRtspMessage {
        sendUpstream(socket, request(method, url, headers))
        val response = readEyevueRtspMessage(socket.getInputStream()) ?: throw IOException("Glasses disconnected")
        log("glasses $method: ${response.firstLine}")
        return response.requireOk()
    }

    private fun pumpUpstream(socket: Socket, input: java.io.InputStream, videoChannel: Int?) {
        val seen = mutableSetOf<Int>()
        try {
            while (active.get() && upstream === socket) {
                val first = input.read()
                if (first < 0) break
                if (first == '$'.code) {
                    val header = readEyevueBytes(input, 3)
                    val channel = header[0].toInt() and 255
                    val size = ((header[1].toInt() and 255) shl 8) or (header[2].toInt() and 255)
                    val payload = readEyevueBytes(input, size)
                    if (channel == videoChannel) lastVideoPacketAt = System.nanoTime()
                    clients.forEach { it.media(channel, payload) }
                    if (seen.add(channel)) log("first upstream packet channel=$channel bytes=$size")
                } else {
                    val message = readEyevueRtspMessage(input, first) ?: break
                    if (!message.firstLine.startsWith("RTSP/")) {
                        sendUpstream(socket, EyevueRtspMessage(listOf("RTSP/1.0 200 OK",
                            "CSeq: ${message.header("CSeq") ?: "0"}")))
                    }
                }
            }
        } catch (error: IOException) {
            if (active.get()) log("glasses stream closed: ${error.message}")
        } finally {
            runCatching { socket.close() }
            synchronized(upstreamLock) {
                if (upstream === socket) {
                    description = null
                    upstreamSession = null
                    streaming = false
                    upstream = null
                    keepalive?.interrupt()
                    clients.forEach { it.close() }
                }
            }
        }
    }

    private inner class Viewer(val socket: Socket) {
        val session = "CB${sessionIds.getAndIncrement()}"
        val queue = ArrayBlockingQueue<ByteArray>(128)
        val closed = AtomicBoolean(false)
        val subscriptions = CopyOnWriteArrayList<Subscription>()
        @Volatile var playing = false
        private val writer = Thread({
            try {
                val output = socket.getOutputStream()
                while (!closed.get()) {
                    output.write(queue.take())
                    output.flush()
                }
            } catch (_: InterruptedException) {
            } catch (_: IOException) {
            } finally { close() }
        }, "eyevue-rtsp-writer").apply { isDaemon = true }

        init { writer.start() }

        fun send(bytes: ByteArray) {
            if (!closed.get() && !queue.offer(bytes)) {
                log("disconnecting slow viewer $session")
                close() // Never block the upstream reader or another client's picture/audio.
            }
        }

        fun reply(request: EyevueRtspMessage, status: String = "200 OK", headers: List<String> = emptyList(), body: ByteArray = byteArrayOf()) {
            send(EyevueRtspMessage(listOf("RTSP/1.0 $status", "CSeq: ${request.header("CSeq") ?: "0"}") +
                headers + "Content-Length: ${body.size}", body).bytes())
        }

        fun media(channel: Int, payload: ByteArray) {
            if (!playing || closed.get()) return
            subscriptions.forEach { subscription ->
                when (channel) {
                    subscription.track.rtpChannel -> subscription.send(this, false, payload)
                    subscription.track.rtcpChannel -> subscription.send(this, true, payload)
                }
            }
        }

        fun close() {
            if (!closed.compareAndSet(false, true)) return
            playing = false
            runCatching { socket.close() }
            subscriptions.forEach { it.close() }
            subscriptions.clear()
            queue.clear()
            writer.interrupt()
            clients.remove(this)
        }
    }

    private class Subscription(
        val track: EyevueRtspTrack,
        val channels: Pair<Int, Int>? = null,
        val udp: Pair<DatagramSocket, DatagramSocket>? = null,
    ) {
        fun send(viewer: Viewer, rtcp: Boolean, payload: ByteArray) {
            if (channels != null) {
                val channel = if (rtcp) channels.second else channels.first
                viewer.send(byteArrayOf('$'.code.toByte(), channel.toByte(), (payload.size shr 8).toByte(), payload.size.toByte()) + payload)
            } else if (udp != null) {
                try {
                    (if (rtcp) udp.second else udp.first).send(DatagramPacket(payload, payload.size))
                } catch (_: IOException) { viewer.close() }
            }
        }
        fun close() { udp?.let { it.first.close(); it.second.close() } }
    }

    private fun handleClient(viewer: Viewer) {
        try {
            val input = viewer.socket.getInputStream()
            while (active.get() && !viewer.closed.get()) {
                val first = input.read()
                if (first < 0) break
                if (first == '$'.code) {
                    // Drain local RTCP reports/BYE. A viewer must not end the shared session.
                    val header = readEyevueBytes(input, 3)
                    readEyevueBytes(input, ((header[1].toInt() and 255) shl 8) or (header[2].toInt() and 255))
                    continue
                }
                val request = readEyevueRtspMessage(input, first) ?: break
                val method = request.firstLine.substringBefore(' ')
                log("viewer ${viewer.session}: ${request.firstLine}")
                when (method) {
                    "OPTIONS" -> viewer.reply(request, headers = listOf("Public: OPTIONS, DESCRIBE, SETUP, PLAY, PAUSE, TEARDOWN, GET_PARAMETER"))
                    "GET_PARAMETER" -> viewer.reply(request, headers = listOf("Session: ${viewer.session}"))
                    "DESCRIBE" -> {
                        val parsed = ensureUpstream()
                        viewer.reply(request, headers = listOf("Content-Base: $localBase", "Content-Type: application/sdp"), body = parsed.localSdp)
                    }
                    "SETUP" -> setup(viewer, request)
                    "PLAY" -> {
                        if (viewer.subscriptions.isEmpty()) viewer.reply(request, "455 Method Not Valid in This State")
                        else play(viewer, request)
                    }
                    "PAUSE", "TEARDOWN" -> {
                        viewer.playing = false
                        if (method == "TEARDOWN") {
                            viewer.subscriptions.forEach { it.close() }
                            viewer.subscriptions.clear()
                        }
                        viewer.reply(request, headers = listOf("Session: ${viewer.session}"))
                    }
                    else -> viewer.reply(request, "501 Not Implemented")
                }
            }
        } catch (error: Exception) {
            if (active.get() && !viewer.closed.get()) log("viewer ${viewer.session} closed: ${error.message}")
        } finally { viewer.close() }
    }

    private fun setup(viewer: Viewer, request: EyevueRtspMessage) {
        val url = request.firstLine.split(' ').getOrNull(1).orEmpty()
        val index = url.substringAfterLast('/').removePrefix("track").toIntOrNull()?.minus(1)
        val track = index?.let { ensureUpstream().tracks.getOrNull(it) }
        if (track == null) { viewer.reply(request, "404 Not Found"); return }
        val transport = request.header("Transport").orEmpty()
        if (viewer.playing) { viewer.reply(request, "455 Method Not Valid in This State"); return }
        val subscription: Subscription
        val replyTransport: String
        if (transport.contains("RTP/AVP/TCP", ignoreCase = true)) {
            val match = Regex("interleaved=(\\d+)-(\\d+)", RegexOption.IGNORE_CASE).find(transport)
            val rtp = match?.groupValues?.get(1)?.toIntOrNull()
            val rtcp = match?.groupValues?.get(2)?.toIntOrNull()
            if (rtp == null || rtcp == null || rtp !in 0..255 || rtcp !in 0..255 || rtp == rtcp) {
                viewer.reply(request, "461 Unsupported Transport"); return
            }
            subscription = Subscription(track, rtp to rtcp)
            replyTransport = "RTP/AVP/TCP;unicast;interleaved=$rtp-$rtcp"
        } else {
            val match = Regex("client_port=(\\d+)-(\\d+)", RegexOption.IGNORE_CASE).find(transport)
            val rtp = match?.groupValues?.get(1)?.toIntOrNull()
            val rtcp = match?.groupValues?.get(2)?.toIntOrNull()
            if (rtp == null || rtcp == null || rtp !in 1..65535 || rtcp !in 1..65535) {
                viewer.reply(request, "461 Unsupported Transport"); return
            }
            val sockets = udpPair()
            sockets.first.connect(loopback, rtp)
            sockets.second.connect(loopback, rtcp)
            subscription = Subscription(track, udp = sockets)
            replyTransport = "RTP/AVP;unicast;client_port=$rtp-$rtcp;server_port=${sockets.first.localPort}-${sockets.second.localPort};source=127.0.0.1"
            // Drain client RTCP on the actual advertised RTCP port, without forwarding BYE.
            worker("udp-rtcp") {
                try {
                    val packet = DatagramPacket(ByteArray(65535), 65535)
                    while (!sockets.second.isClosed) { packet.length = packet.data.size; sockets.second.receive(packet) }
                } catch (_: IOException) { }
            }
        }
        viewer.subscriptions.filter { it.track == track }.forEach { it.close(); viewer.subscriptions.remove(it) }
        viewer.subscriptions.add(subscription)
        viewer.reply(request, headers = listOf("Transport: $replyTransport", "Session: ${viewer.session};timeout=60"))
    }

    private fun udpPair(): Pair<DatagramSocket, DatagramSocket> {
        repeat(64) {
            val rtp = DatagramSocket(InetSocketAddress(loopback, 0))
            if (rtp.localPort % 2 != 0 || rtp.localPort == 65535) { rtp.close(); return@repeat }
            try { return rtp to DatagramSocket(InetSocketAddress(loopback, rtp.localPort + 1)) }
            catch (_: IOException) { rtp.close() }
        }
        throw IOException("Unable to allocate local RTP/RTCP ports")
    }

    private fun worker(name: String, block: () -> Unit): Thread =
        Thread(block, "eyevue-rtsp-$name").apply { isDaemon = true; start() }
}
