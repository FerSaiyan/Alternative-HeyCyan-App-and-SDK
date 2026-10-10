package com.fersaiyan.cyanbridge.devices.tunebuds

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class TuneBudsMediaSyncTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun sync(): TuneBudsMediaSync {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return TuneBudsMediaSync(TuneBudsManager.getInstance(context), TuneBudsLocalHotspot(context), temporary.root)
    }

    @Test fun allVendorManifestFormsPreserveAudioPhotoAndExtensionlessVideoNames() {
        val importer = sync()
        val names = listOf("20250102155119977.JPG", "video-123", "Audio/recording.opus")
        val json = "[\"${names.joinToString("\",\"")}\"]"
        for (body in listOf(names.joinToString("\r\n"), json, "{\"files\":$json}",
            "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nConnection: close\r\n\r\n$json")) {
            val items = importer.parseManifest(body)
            assertEquals(names, items.map { it.remoteName })
            assertEquals(listOf(TuneBudsMediaType.PHOTO, TuneBudsMediaType.VIDEO, TuneBudsMediaType.AUDIO), items.map { it.type })
            assertEquals("video-123.mp4", items[1].fileName)
        }
    }

    @Test fun portFallbackPreservesExplicit80AndVendorPathPrefixOnRepeatedNormalization() {
        val importer = sync()
        for ((source, expected) in listOf(
            "http://192.168.49.82/vendor/media" to "http://192.168.49.82:8080/vendor/media/",
            "http://10.230.84.141:80/files/" to "http://10.230.84.141:80/files/",
            "http://192.168.49.82:8090/custom/" to "http://192.168.49.82:8090/custom/")) {
            assertEquals(expected, importer.normalizeBaseUrl(source))
            assertEquals(expected, importer.normalizeBaseUrl(importer.normalizeBaseUrl(source)))
        }
    }

    @Test fun manifestAndEachMediaTypeUseVendorHeadersAndExactEncodedRemotePath() = runBlocking {
        val payload = byteArrayOf(0, 1, 2, 3, 0xFF.toByte())
        Server { socket, request ->
            val body = if (request.startsWith("GET /custom/media/media.config")) {
                "[\"photo 1.JPG\",\"video-123\",\"Audio/recording.opus\"]".toByteArray()
            } else payload
            socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray() + body)
        }.use { server ->
            val importer = sync()
            val base = "http://127.0.0.1:${server.port}/custom/media/?key=123"
            val items = importer.fetchManifest(base)
            assertEquals(3, items.size)
            for (item in items) assertArrayEquals(payload, importer.downloadSingle(base, item).readBytes())
            assertEquals(listOf("media.config", "photo%201.JPG", "video-123", "Audio/recording.opus"),
                server.requests.map { it.lineSequence().first().substringAfter("/custom/media/").substringBefore("?key=123") })
            server.requests.forEach {
                assertTrue(it.contains("Connection: close", ignoreCase = true))
                assertTrue(it.contains("User-Agent: TSClient/1.0", ignoreCase = true))
            }
        }
    }

    @Test fun truncatedOrEmptyMediaIsRejectedAndPartialFileIsRemoved() = runBlocking {
        for (length in listOf(0, 100)) {
            Server { socket, _ -> socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: $length\r\n\r\n".toByteArray()) }.use { server ->
                val importer = sync()
                try {
                    importer.downloadSingle("http://127.0.0.1:${server.port}/files/", TuneBudsMediaItem("bad.mp4", "bad.mp4", TuneBudsMediaType.VIDEO))
                    fail("Must reject incomplete video")
                } catch (expected: IOException) { }
                assertFalse(File(temporary.root, "bad.mp4").exists())
            }
        }
    }

    @Test fun stopCancelsBlockedHttpReadPromptlyAndRemovesPartialRecording(): Unit = runBlocking {
        val started = CountDownLatch(1)
        Server { socket, _ ->
            socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 100\r\n\r\n".toByteArray())
            socket.getOutputStream().flush()
            started.countDown()
            socket.getInputStream().read() // Client Stop closes the connection.
        }.use { server ->
            val task = async(Dispatchers.IO) {
                sync().downloadSingle("http://127.0.0.1:${server.port}/files/", TuneBudsMediaItem("recording.opus", "recording.opus", TuneBudsMediaType.AUDIO))
            }
            assertTrue(withContext(Dispatchers.IO) { started.await(5, TimeUnit.SECONDS) })
            withTimeout(5_000L) { task.cancelAndJoin() }
            assertFalse(File(temporary.root, "recording.opus").exists())
        }
    }

    private class Server(private val handle: (Socket, String) -> Unit) : AutoCloseable {
        private val server = ServerSocket(0)
        val port = server.localPort
        val requests = CopyOnWriteArrayList<String>()
        private val worker = thread(isDaemon = true) {
            try {
                while (!server.isClosed) server.accept().use { socket ->
                    socket.soTimeout = 5_000
                    val reader = socket.getInputStream().bufferedReader()
                    val request = buildString {
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) break
                            appendLine(line)
                        }
                    }
                    requests += request
                    handle(socket, request)
                }
            } catch (_: IOException) { }
        }
        override fun close() { server.close(); worker.join(6_000) }
    }
}
