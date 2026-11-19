package com.fersaiyan.cyanbridge.ai.speaker

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread
import java.security.MessageDigest

/** The speaker model download installs only a file with the catalog's size and SHA-256. */
class SpeakerModelDownloadTest {
    @get:Rule val tmp = TemporaryFolder()
    private val payload = ByteArray(4_096) { (it * 31).toByte() }
    private lateinit var server: ServerSocket

    /** One-request-per-connection HTTP responder serving [payload]. */
    @Before fun start() {
        server = ServerSocket(0, 4, InetAddress.getLoopbackAddress())
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                socket.use {
                    val reader = it.getInputStream().bufferedReader()
                    while (reader.readLine()?.isNotEmpty() == true) Unit
                    it.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Length: ${payload.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(payload)
                        flush()
                    }
                }
            }
        }
    }

    @After fun stop() = server.close()

    private fun spec(sha256: String) = SpeakerModels.DEFAULT.copy(sizeBytes = payload.size.toLong(), sha256 = sha256)

    private fun download(spec: SpeakerModelSpec) = runBlocking {
        val target = tmp.root.resolve("speaker_models/model.tflite")
        runCatching {
            SpeakerModelStore.downloadTo(target, spec, "http://127.0.0.1:${server.localPort}/model.tflite", OkHttpClient()) {}
        }
        target
    }

    @Test fun `a verified download is installed`() {
        val sha = MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) }
        val target = download(spec(sha))
        assertArrayEquals(payload, target.readBytes())
        assertFalse(target.resolveSibling("model.tflite.part").exists())
    }

    @Test fun `a download with the wrong checksum is discarded`() {
        val target = download(spec("0".repeat(64)))
        assertFalse(target.exists())
        assertTrue(target.parentFile!!.listFiles().orEmpty().isEmpty())
    }
}
