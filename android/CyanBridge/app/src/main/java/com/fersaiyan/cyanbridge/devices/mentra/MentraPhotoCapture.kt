package com.fersaiyan.cyanbridge.devices.mentra

import android.content.Context
import android.graphics.BitmapFactory
import com.mentra.bluetoothsdk.PhotoCompression
import com.mentra.bluetoothsdk.PhotoRequest
import com.mentra.bluetoothsdk.PhotoSize
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * BLE transfer through the phone to a one-shot loopback photo receiver.
 * Never uploads private photos to an external server.
 */
class MentraPhotoCapture(context: Context) {
    private val appContext = context.applicationContext

    suspend fun capture(): ByteArray = withContext(Dispatchers.IO) {
        val manager = MentraLiveManager.getInstance(appContext)
        check(manager.ready) { "Mentra Live is not ready for photo capture" }
        val requestId = UUID.randomUUID().toString()
        val token = UUID.randomUUID().toString()
        val receiver = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val taskScope = CoroutineScope(Dispatchers.IO)
        val upload = taskScope.async {
            receiver.accept().use { socket ->
                socket.soTimeout = 60_000
                receivePhoto(socket, token, requestId)
            }
        }
        try {
            withTimeout(90_000L) {
                manager.requestPhoto(
                    PhotoRequest(
                        requestId = requestId,
                        size = PhotoSize.MEDIUM,
                        webhookUrl = "http://127.0.0.1:" + receiver.localPort + "/" + token,
                        compress = PhotoCompression.MEDIUM,
                        save = false,
                        transferMethod = "ble",
                    )
                )
                upload.await()
            }
        } finally {
            receiver.close()
            upload.cancel()
            taskScope.cancel()
        }
    }

    private fun receivePhoto(socket: Socket, token: String, expectedId: String): ByteArray {
        val input = BufferedInputStream(socket.getInputStream())
        val output = socket.getOutputStream()
        try {
            val headers = readHeaders(input)
            val request = headers.lineSequence().firstOrNull().orEmpty()
            require(request == "POST /" + token + " HTTP/1.1") { "Unexpected photo upload request" }
            val contentType = headers.lineSequence().firstOrNull {
                it.startsWith("content-type:", ignoreCase = true)
            }?.substringAfter(':')?.trim().orEmpty()
            require(contentType.startsWith("multipart/form-data", ignoreCase = true)) {
                "Expected a multipart photo upload"
            }
            val boundary = contentType.substringAfter("boundary=", "").trim().trim('"')
            require(boundary.isNotEmpty() && boundary.length <= 200) { "Missing multipart boundary" }
            val length = headers.lineSequence().firstOrNull {
                it.startsWith("content-length:", ignoreCase = true)
            }?.substringAfter(':')?.trim()?.toIntOrNull()
                ?: error("Missing photo content length")
            require(length in 1..MAX_UPLOAD_BYTES) { "Photo upload too large" }
            val data = ByteArray(length)
            var position = 0
            while (position < length) {
                val count = input.read(data, position, length - position)
                if (count < 0) throw EOFException("Incomplete photo upload")
                position += count
            }
            val photo = parsePhoto(data, boundary, expectedId)
            val response = """{"ok":true}""".toByteArray(StandardCharsets.UTF_8)
            output.write(
                ("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: " +
                    response.size + "\r\nConnection: close\r\n\r\n")
                    .toByteArray(StandardCharsets.US_ASCII)
            )
            output.write(response)
            output.flush()
            return photo
        } catch (error: Exception) {
            runCatching {
                output.write(
                    "HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                        .toByteArray(StandardCharsets.US_ASCII)
                )
                output.flush()
            }
            throw error
        }
    }

    private fun readHeaders(input: BufferedInputStream): String {
        val output = ByteArrayOutputStream()
        val delimiter = byteArrayOf(13, 10, 13, 10)
        var seen = 0
        while (output.size() < 8_192) {
            val next = input.read()
            if (next < 0) throw EOFException("Photo upload closed before headers")
            output.write(next)
            seen = if (next == delimiter[seen].toInt()) seen + 1
                else if (next == delimiter[0].toInt()) 1 else 0
            if (seen == delimiter.size) return output.toString("ISO-8859-1")
        }
        error("Photo upload headers too large")
    }

    companion object {
        private const val MAX_UPLOAD_BYTES = 6 * 1024 * 1024

        /** Internal parser for JVM tests. Latin-1 preserves every JPEG byte. */
        internal fun parsePhoto(data: ByteArray, boundary: String, expectedId: String): ByteArray {
            val message = String(data, StandardCharsets.ISO_8859_1)
            fun field(name: String): ByteArray {
                val fieldStart = message.indexOf("name=\"" + name + "\"")
                require(fieldStart >= 0) { "Missing " + name + " field" }
                val separator = message.indexOf("\r\n\r\n", fieldStart)
                require(separator >= 0) { "Malformed multipart headers" }
                val first = separator + 4
                val last = message.indexOf("\r\n--" + boundary, first)
                require(last >= first) { "Malformed multipart boundary" }
                return message.substring(first, last).toByteArray(StandardCharsets.ISO_8859_1)
            }
            val receivedId = String(field("requestId"), StandardCharsets.UTF_8)
            require(receivedId == expectedId) { "Photo request ID mismatch" }
            val jpeg = field("photo")
            require(jpeg.size >= 4 && jpeg[0] == 0xff.toByte() &&
                jpeg[1] == 0xd8.toByte() && jpeg[jpeg.size - 2] == 0xff.toByte() &&
                jpeg[jpeg.size - 1] == 0xd9.toByte()
            ) { "Invalid JPEG envelope" }
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, options)
            require(options.outWidth > 0 && options.outHeight > 0) { "Invalid JPEG data" }
            return jpeg
        }
    }
}
