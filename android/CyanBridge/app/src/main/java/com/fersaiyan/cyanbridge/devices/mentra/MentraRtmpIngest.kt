package com.fersaiyan.cyanbridge.devices.mentra

import android.os.SystemClock
import android.util.Log
import com.fersaiyan.cyanbridge.shared.media.rtmp.RtmpProtocolException
import com.fersaiyan.cyanbridge.shared.media.rtmp.RtmpPublishEvent
import com.fersaiyan.cyanbridge.shared.media.rtmp.RtmpPublishSession
import com.fersaiyan.cyanbridge.shared.media.rtmp.RtmpToTsRemuxer
import com.fersaiyan.cyanbridge.shared.media.rtmp.TsChunk
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.ArrayDeque
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The phone's RTMP ingest for the Mentra Live preview: a listener on the phone's address in the
 * glasses' network that accepts one publisher with the session's stream key, and remuxes its
 * H.264 video into MPEG-TS for [LoopbackTsServer]. The protocol work is in `:shared`
 * ([RtmpPublishSession], [RtmpToTsRemuxer]); this class only owns sockets and threads.
 *
 * Other connections (for example the glasses' reachability probe, which connects and closes)
 * never disturb the publisher. A new publisher with the right key replaces the old one, which
 * is what the glasses do when they reconnect after a stall.
 */
internal class MentraRtmpIngest(
    private val bindAddress: InetAddress,
    private val streamKey: String,
    private val output: LoopbackTsServer,
) {
    private val active = AtomicBoolean(false)
    private val remuxer = RtmpToTsRemuxer()
    private val remuxLock = Any()
    private val connections = CopyOnWriteArrayList<Socket>()
    @Volatile private var server: ServerSocket? = null
    @Volatile private var publisher: Socket? = null
    @Volatile private var loggedUnsupported = false
    @Volatile private var lastVideoAt = 0L
    @Volatile private var lastMediaAt = 0L

    private val _publishing = MutableStateFlow(false)

    /** True while a publisher with the right key is connected. */
    val publishing: StateFlow<Boolean> = _publishing.asStateFlow()

    /** Elapsed-realtime of the last H.264 frame handed to the player, or 0. */
    val lastVideoAtMs: Long get() = lastVideoAt

    /** Elapsed-realtime of the last audio or video message, or 0. */
    val lastMediaAtMs: Long get() = lastMediaAt

    /** A codec the glasses sent that the preview cannot show (for example HEVC), or null. */
    val unsupportedCodec: String? get() = synchronized(remuxLock) { remuxer.unsupportedCodec }

    /** Binds the listener ([preferredPort], else any free port) and returns the port. */
    fun start(preferredPort: Int = DEFAULT_PORT): Int {
        check(active.compareAndSet(false, true)) { "Ingest already started" }
        val listener = bind(preferredPort)
        server = listener
        thread("accept") {
            while (active.get()) {
                val socket = try {
                    listener.accept()
                } catch (error: IOException) {
                    if (active.get()) Log.w(TAG, "Ingest accept failed: ${error.message}")
                    break
                }
                connections += socket
                thread("publisher") { serve(socket) }
            }
        }
        Log.i(TAG, "RTMP ingest listening on ${bindAddress.hostAddress}:${listener.localPort}")
        return listener.localPort
    }

    private fun bind(preferredPort: Int): ServerSocket {
        for (port in listOf(preferredPort, 0)) {
            val listener = ServerSocket()
            try {
                listener.reuseAddress = true
                listener.bind(InetSocketAddress(bindAddress, port), BACKLOG)
                return listener
            } catch (error: IOException) {
                runCatching { listener.close() }
                if (port == 0) {
                    active.set(false)
                    throw IOException("Cannot open the stream receiver on ${bindAddress.hostAddress}", error)
                }
            }
        }
        error("unreachable")
    }

    private fun serve(socket: Socket) {
        val peer = socket.remoteSocketAddress?.toString().orEmpty()
        val session = RtmpPublishSession(streamKey, nowMs = { SystemClock.elapsedRealtime() })
        var accepted = false
        try {
            socket.tcpNoDelay = true
            socket.soTimeout = READ_TIMEOUT_MS
            val input: InputStream = socket.getInputStream()
            val out = socket.getOutputStream()
            val buffer = ByteArray(READ_BUFFER_BYTES)
            while (active.get()) {
                val count = input.read(buffer)
                if (count < 0) break
                val result = session.receive(buffer, 0, count)
                if (result.reply.isNotEmpty()) {
                    out.write(result.reply)
                    out.flush()
                }
                for (event in result.events) {
                    when (event) {
                        is RtmpPublishEvent.Connected -> Log.i(TAG, "Publisher $peer connected to app '${event.app}'")
                        is RtmpPublishEvent.PublishStarted -> {
                            accepted = true
                            takeOver(socket)
                            Log.i(TAG, "Glasses publishing from $peer")
                        }
                        is RtmpPublishEvent.Metadata -> Log.i(TAG, "Stream metadata ${describe(event.properties)}")
                        is RtmpPublishEvent.Video -> if (publisher === socket) onVideo(event)
                        is RtmpPublishEvent.Audio -> if (publisher === socket) lastMediaAt = SystemClock.elapsedRealtime()
                        is RtmpPublishEvent.Rejected -> Log.w(TAG, "Refused publisher $peer: ${event.reason}")
                        RtmpPublishEvent.Unpublished -> Log.i(TAG, "Glasses stopped publishing")
                    }
                }
                if (result.shouldClose) break
            }
        } catch (timeout: SocketTimeoutException) {
            Log.w(TAG, "Publisher $peer sent nothing for ${READ_TIMEOUT_MS / 1000} s")
        } catch (error: RtmpProtocolException) {
            Log.w(TAG, "RTMP protocol error from $peer: ${error.message}")
        } catch (error: IOException) {
            if (active.get() && accepted) Log.w(TAG, "Publisher $peer dropped: ${error.message}")
        } finally {
            runCatching { socket.close() }
            connections -= socket
            if (publisher === socket) {
                publisher = null
                _publishing.value = false
            }
            if (!accepted) Log.d(TAG, "Connection from $peer closed before publishing")
        }
    }

    private fun takeOver(socket: Socket) {
        val previous = publisher
        publisher = socket
        synchronized(remuxLock) { remuxer.onPublishStarted() }
        _publishing.value = true
        if (previous != null && previous !== socket) {
            Log.i(TAG, "A new publisher replaced the previous connection")
            runCatching { previous.close() }
        }
    }

    private fun onVideo(event: RtmpPublishEvent.Video) {
        val now = SystemClock.elapsedRealtime()
        lastMediaAt = now
        val chunk = try {
            synchronized(remuxLock) { remuxer.onVideo(event.timestamp, event.payload) }
        } catch (error: RtmpProtocolException) {
            Log.w(TAG, "Dropped a malformed video frame: ${error.message}")
            null
        }
        if (chunk == null) {
            val unsupported = unsupportedCodec
            if (unsupported != null && !loggedUnsupported) {
                loggedUnsupported = true
                Log.w(TAG, "The glasses stream $unsupported, which the preview cannot decode")
            }
            return
        }
        if (lastVideoAt == 0L) {
            Log.i(TAG, "First keyframe received (${synchronized(remuxLock) { remuxer.configuration?.codecString }})")
        }
        lastVideoAt = now
        output.publish(chunk)
    }

    private fun describe(properties: Map<String, Any?>): String =
        listOf("width", "height", "framerate", "videodatarate", "videocodecid", "encoder")
            .mapNotNull { key -> properties[key]?.let { "$key=$it" } }
            .joinToString(" ")

    fun stop() {
        if (!active.getAndSet(false)) return
        runCatching { server?.close() }
        server = null
        connections.forEach { runCatching { it.close() } }
        connections.clear()
        publisher = null
        _publishing.value = false
    }

    private fun thread(name: String, block: () -> Unit) {
        Thread(block, "MentraIngest-$name").apply { isDaemon = true }.start()
    }

    companion object {
        const val TAG = "MentraLiveStream"
        const val DEFAULT_PORT = 1935
        private const val BACKLOG = 4
        private const val READ_TIMEOUT_MS = 15_000
        private const val READ_BUFFER_BYTES = 64 * 1024
    }
}

/**
 * Serves the remuxed MPEG-TS to the local player at `http://127.0.0.1:<port>/<token>.ts`. A new
 * viewer first receives the frames since the latest keyframe, so playback starts at once. A slow
 * viewer never blocks the ingest: its queue is bounded, and on overflow it skips to the next
 * keyframe.
 */
internal class LoopbackTsServer(private val token: String) {
    private inner class Viewer(val socket: Socket) {
        private val lock = ReentrantLock()
        private val available = lock.newCondition()
        private val queue = ArrayDeque<TsChunk>()
        private var queuedBytes = 0
        private var waitingForKeyframe = false
        @Volatile var closed = false
            private set

        fun offer(chunk: TsChunk) = lock.withLock {
            if (waitingForKeyframe && !chunk.keyframe) return
            if (queuedBytes + chunk.bytes.size > MAX_VIEWER_QUEUE_BYTES) {
                queue.clear()
                queuedBytes = 0
                waitingForKeyframe = true
                Log.w(MentraRtmpIngest.TAG, "Player fell behind; skipping to the next keyframe")
                if (!chunk.keyframe) return
            }
            waitingForKeyframe = false
            queue.addLast(chunk)
            queuedBytes += chunk.bytes.size
            available.signalAll()
        }

        fun take(): TsChunk? = lock.withLock {
            if (queue.isEmpty() && !closed) available.await(500, TimeUnit.MILLISECONDS)
            queue.pollFirst()?.also { queuedBytes -= it.bytes.size }
        }

        fun close() {
            closed = true
            lock.withLock { available.signalAll() }
            runCatching { socket.close() }
        }
    }

    private val active = AtomicBoolean(false)
    private val viewers = CopyOnWriteArrayList<Viewer>()
    private val gopLock = Any()
    private val gop = ArrayList<TsChunk>()
    private var gopBytes = 0
    @Volatile private var server: ServerSocket? = null

    val path: String get() = "/$token.ts"

    /** Binds to loopback and returns the playback URL. */
    fun start(): String {
        check(active.compareAndSet(false, true)) { "Loopback server already started" }
        val listener = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        server = listener
        Thread({
            while (active.get()) {
                val socket = try {
                    listener.accept()
                } catch (error: IOException) {
                    break
                }
                Thread({ serve(socket) }, "MentraTs-viewer").apply { isDaemon = true }.start()
            }
        }, "MentraTs-accept").apply { isDaemon = true }.start()
        return "http://127.0.0.1:${listener.localPort}$path"
    }

    fun publish(chunk: TsChunk) {
        synchronized(gopLock) {
            if (chunk.keyframe) {
                gop.clear()
                gopBytes = 0
            }
            if (gopBytes + chunk.bytes.size <= MAX_GOP_CACHE_BYTES) {
                gop += chunk
                gopBytes += chunk.bytes.size
            }
            viewers.forEach { it.offer(chunk) }
        }
    }

    private fun serve(socket: Socket) {
        val viewer = Viewer(socket)
        try {
            socket.soTimeout = 10_000
            socket.tcpNoDelay = true
            val request = readRequestLine(socket.getInputStream())
            val target = request.split(' ').getOrNull(1)?.substringBefore('?')
            val out = socket.getOutputStream()
            if (!request.startsWith("GET ") || target != path) {
                out.write("HTTP/1.0 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                out.flush()
                return
            }
            out.write(
                ("HTTP/1.0 200 OK\r\nContent-Type: video/mp2t\r\nCache-Control: no-cache\r\n" +
                    "Connection: close\r\n\r\n").toByteArray(Charsets.US_ASCII),
            )
            out.flush()
            synchronized(gopLock) {
                gop.forEach(viewer::offer)
                viewers += viewer
            }
            Log.i(MentraRtmpIngest.TAG, "Player attached to the local stream (${viewers.size} viewer(s))")
            while (active.get() && !viewer.closed) {
                val chunk = viewer.take() ?: continue
                out.write(chunk.bytes)
                out.flush()
            }
        } catch (error: IOException) {
            Log.d(MentraRtmpIngest.TAG, "Player detached: ${error.message}")
        } finally {
            viewers -= viewer
            viewer.close()
        }
    }

    /** Reads the request line and drains the headers. */
    private fun readRequestLine(input: InputStream): String {
        val header = StringBuilder()
        var previous = -1
        var blankLine = false
        while (header.length < MAX_REQUEST_BYTES && !blankLine) {
            val next = input.read()
            if (next < 0) break
            header.append(next.toChar())
            blankLine = header.endsWith("\r\n\r\n") || (next == '\n'.code && previous == '\n'.code)
            previous = next
        }
        return header.lineSequence().firstOrNull().orEmpty().trim()
    }

    fun stop() {
        if (!active.getAndSet(false)) return
        runCatching { server?.close() }
        server = null
        viewers.forEach { it.close() }
        viewers.clear()
        synchronized(gopLock) {
            gop.clear()
            gopBytes = 0
        }
    }

    private companion object {
        const val MAX_VIEWER_QUEUE_BYTES = 4 * 1024 * 1024
        const val MAX_GOP_CACHE_BYTES = 6 * 1024 * 1024
        const val MAX_REQUEST_BYTES = 8 * 1024
    }
}
