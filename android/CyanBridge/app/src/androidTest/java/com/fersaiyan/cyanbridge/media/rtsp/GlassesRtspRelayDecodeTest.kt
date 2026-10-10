package com.fersaiyan.cyanbridge.media.rtsp

import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fersaiyan.cyanbridge.devices.eyevue.EyevueVideoFrameOutput
import com.fersaiyan.cyanbridge.devices.heycyan.HeyCyanVideoFrameOutput
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer

/** Actual H.264 over RTSP, including HeyCyan's missing video fmtp/in-band SPS/PPS. */
@RunWith(AndroidJUnit4::class)
class GlassesRtspRelayDecodeTest {
    @Test
    fun bothGlassesUseSameEndpointAndTwoDecodersShareOneUpstream() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val fixture = File(context.cacheDir, "relay-scenes.mp4")
        InstrumentationRegistry.getInstrumentation().context.assets.open("walkingaid/eyevue-scenes.mp4").use { input ->
            fixture.outputStream().use { input.copyTo(it) }
        }
        try {
            for (path in listOf("/xxx.mov", "/ch0")) {
                FixtureGlasses(fixture, path).use { source ->
                    val relay = GlassesRtspRelay("127.0.0.1", source.port, streamPath = path,
                        upstreamPlayRange = if (path == "/ch0") null else "npt=0.000-")
                    assertEquals(8554, relay.start())
                    val failure = AtomicReference<Throwable?>()
                    val firstFrames = CountDownLatch(3)
                    val secondFrames = CountDownLatch(3)
                    val continuedFrames = AtomicReference<CountDownLatch?>()
                    val vlcs = mutableListOf<LibVLC>()
                    val players = mutableListOf<MediaPlayer>()
                    var eyevueOutput: EyevueVideoFrameOutput? = null
                    var heycyanOutput: HeyCyanVideoFrameOutput? = null
                    try {
                        InstrumentationRegistry.getInstrumentation().runOnMainSync {
                            repeat(2) { index ->
                                val vlc = LibVLC(context)
                                vlcs.add(vlc)
                                val player = MediaPlayer(vlc)
                                players.add(player)
                                val media = Media(vlc, Uri.parse(GlassesRtspRelay.URL))
                                listOf(":codec=avcodec", ":rtsp-tcp", ":network-caching=300", ":live-caching=100", ":no-audio").forEach(media::addOption)
                                player.media = media
                                media.release()
                                if (index == 0) {
                                    eyevueOutput = EyevueVideoFrameOutput(onFrame = { bitmap ->
                                        if (bitmap.width != 480 || bitmap.height != 640) failure.compareAndSet(null, AssertionError("EyeVue rotation changed"))
                                        bitmap.recycle()
                                        firstFrames.countDown()
                                    }, onError = { failure.compareAndSet(null, it) }).also { it.attach(player) }
                                } else {
                                    heycyanOutput = HeyCyanVideoFrameOutput(onFrame = { bitmap ->
                                        if (bitmap.width != 640 || bitmap.height != 480) failure.compareAndSet(null, AssertionError("HeyCyan output size changed"))
                                        bitmap.recycle()
                                        secondFrames.countDown()
                                        continuedFrames.get()?.countDown()
                                    }, onError = { failure.compareAndSet(null, it) }).also { it.attach(player) }
                                }
                                player.play()
                            }
                        }
                        assertTrue("First decoder received no video for $path", firstFrames.await(20, TimeUnit.SECONDS))
                        assertTrue("Second decoder received no video for $path", secondFrames.await(20, TimeUnit.SECONDS))
                        failure.get()?.let { throw AssertionError("Frame output failed", it) }
                        assertEquals("External viewer opened another glasses connection", 1, source.connections.get())
                        assertEquals(1, source.methods.count { it == "PLAY" })
                        InstrumentationRegistry.getInstrumentation().runOnMainSync { eyevueOutput?.close(players[0]); eyevueOutput = null }
                        players[0].stop()
                        players[0].release()
                        val afterStop = CountDownLatch(3)
                        continuedFrames.set(afterStop)
                        assertTrue("One viewer stopped the shared upstream", afterStop.await(10, TimeUnit.SECONDS))
                        assertFalse(source.methods.contains("TEARDOWN"))
                    } finally {
                        relay.stop()
                        InstrumentationRegistry.getInstrumentation().runOnMainSync {
                            eyevueOutput?.close(players.getOrNull(0))
                            heycyanOutput?.close(players.getOrNull(1))
                        }
                        players.filterNot { it.isReleased }.forEach { it.stop(); it.release() }
                        vlcs.forEach { it.release() }
                    }
                }
            }
        } finally { fixture.delete() }
    }

    /** One TCP RTSP source serving MP4 H.264 NALs as RTP, without SDP parameter sets. */
    private class FixtureGlasses(file: File, private val path: String) : AutoCloseable {
        private data class Sample(val timeUs: Long, val nals: List<ByteArray>)
        private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val port = server.localPort
        val connections = AtomicInteger()
        val methods = CopyOnWriteArrayList<String>()
        @Volatile private var peer: Socket? = null
        @Volatile private var stopped = false
        private val writeLock = Any()
        private val samples = mutableListOf<Sample>()
        private val parameters = mutableListOf<ByteArray>()
        private var sequence = 0
        private var sender: Thread? = null

        init {
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(file.absolutePath)
                val track = (0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME) == "video/avc" }
                val format = extractor.getTrackFormat(track)
                for (key in listOf("csd-0", "csd-1")) {
                    format.getByteBuffer(key)?.let { buffer ->
                        val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
                        parameters.addAll(nals(bytes))
                    }
                }
                extractor.selectTrack(track)
                val buffer = ByteBuffer.allocate(2 * 1024 * 1024)
                while (true) {
                    buffer.clear()
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) break
                    val bytes = ByteArray(size)
                    buffer.position(0)
                    buffer.get(bytes)
                    samples.add(Sample(extractor.sampleTime, nals(bytes)))
                    extractor.advance()
                }
                check(samples.isNotEmpty() && parameters.isNotEmpty())
            } finally { extractor.release() }
        }

        private val reader = thread(isDaemon = true, name = "fixture-rtsp") {
            try {
                val socket = server.accept()
                peer = socket
                connections.incrementAndGet()
                while (!stopped) {
                    val request = readRtspMessage(socket.getInputStream()) ?: break
                    val method = request.firstLine.substringBefore(' ')
                    methods.add(method)
                    var headers = listOf("CSeq: ${request.header("CSeq")}")
                    var body = byteArrayOf()
                    when (method) {
                        "OPTIONS" -> headers += "Public: OPTIONS, DESCRIBE, SETUP, PLAY, GET_PARAMETER, TEARDOWN"
                        "DESCRIBE" -> {
                            headers += listOf("Content-Type: application/sdp", "Content-Base: rtsp://127.0.0.1:$port$path/")
                            body = "v=0\r\no=- 1 1 IN IP4 127.0.0.1\r\ns=testH264VideoStreamer\r\nt=0 0\r\na=control:*\r\nm=video 0 RTP/AVP 96\r\na=rtpmap:96 H264/90000\r\na=control:track1\r\n".toByteArray()
                        }
                        "SETUP" -> headers += listOf("Session: fixture;timeout=65", "Transport: ${request.header("Transport")}")
                        else -> headers += "Session: fixture"
                    }
                    write(RtspMessage(listOf("RTSP/1.0 200 OK") + headers + "Content-Length: ${body.size}", body).bytes())
                    if (method == "PLAY" && sender == null) sender = thread(isDaemon = true, name = "fixture-rtp") {
                        try {
                            var baseUs = 0L
                            while (!stopped) {
                                val started = System.nanoTime()
                                samples.forEach { sample ->
                                    if (stopped) return@thread
                                    val waitNs = sample.timeUs * 1_000 - (System.nanoTime() - started)
                                    if (waitNs > 0) TimeUnit.NANOSECONDS.sleep(waitNs)
                                    val nals = if (sample.nals.any { it[0].toInt() and 31 == 5 }) parameters + sample.nals else sample.nals
                                    nals.forEachIndexed { index, nal -> sendNal(nal, (baseUs + sample.timeUs) * 90 / 1_000, index == nals.lastIndex) }
                                }
                                baseUs += samples.last().timeUs + 33_333
                            }
                        } catch (_: Exception) { }
                    }
                }
            } catch (_: Exception) { }
        }

        private fun sendNal(nal: ByteArray, timestamp: Long, marker: Boolean) {
            if (nal.size <= 1_200) sendRtp(nal, timestamp, marker)
            else {
                var offset = 1
                while (offset < nal.size) {
                    val end = minOf(offset + 1_198, nal.size)
                    val fuIndicator = (nal[0].toInt() and 0xe0) or 28
                    val fuHeader = (nal[0].toInt() and 31) or (if (offset == 1) 128 else 0) or (if (end == nal.size) 64 else 0)
                    sendRtp(byteArrayOf(fuIndicator.toByte(), fuHeader.toByte()) + nal.copyOfRange(offset, end), timestamp, marker && end == nal.size)
                    offset = end
                }
            }
        }

        private fun sendRtp(payload: ByteArray, timestamp: Long, marker: Boolean) {
            val rtp = ByteBuffer.allocate(12 + payload.size).put(0x80.toByte()).put((96 or if (marker) 128 else 0).toByte())
                .putShort((sequence++).toShort()).putInt(timestamp.toInt()).putInt(0x12345678).put(payload).array()
            write(byteArrayOf('$'.code.toByte(), 0, (rtp.size shr 8).toByte(), rtp.size.toByte()) + rtp)
        }

        private fun write(bytes: ByteArray) = synchronized(writeLock) {
            peer!!.getOutputStream().apply { write(bytes); flush() }
        }

        override fun close() {
            stopped = true
            server.close()
            peer?.close()
            sender?.interrupt()
            reader.join(1_000)
            sender?.join(1_000)
        }

        companion object {
            private fun nals(bytes: ByteArray): List<ByteArray> {
                val starts = mutableListOf<Pair<Int, Int>>()
                var i = 0
                while (i < bytes.size - 2) {
                    val size = when {
                        i + 3 < bytes.size && bytes[i] == 0.toByte() && bytes[i + 1] == 0.toByte() && bytes[i + 2] == 0.toByte() && bytes[i + 3] == 1.toByte() -> 4
                        bytes[i] == 0.toByte() && bytes[i + 1] == 0.toByte() && bytes[i + 2] == 1.toByte() -> 3
                        else -> 0
                    }
                    if (size > 0) { starts.add(i to size); i += size } else i++
                }
                if (starts.firstOrNull()?.first == 0) return starts.mapIndexed { index, (start, size) ->
                    bytes.copyOfRange(start + size, starts.getOrNull(index + 1)?.first ?: bytes.size)
                }.filter { it.isNotEmpty() }
                val result = mutableListOf<ByteArray>()
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.remaining() > 0) {
                    val size = buffer.int
                    require(size in 1..buffer.remaining())
                    result.add(ByteArray(size).also { buffer.get(it) })
                }
                return result
            }
        }
    }
}
