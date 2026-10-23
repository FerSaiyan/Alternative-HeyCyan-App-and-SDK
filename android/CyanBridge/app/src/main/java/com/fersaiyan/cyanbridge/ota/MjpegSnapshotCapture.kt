package com.fersaiyan.cyanbridge.ota

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

internal object MjpegSnapshotCapture {
    private const val MAX_HEADER_BYTES = 8 * 1024
    private const val MAX_JPEG_BYTES = 4 * 1024 * 1024

    suspend fun capture(streamUrl: String): ByteArray = withContext(Dispatchers.IO) {
        val url = URL(streamUrl)
        require(
            url.protocol == "http" &&
                url.host == "127.0.0.1" &&
                url.port == 8080 &&
                url.path == "/mjpeg" &&
                url.userInfo == null &&
                url.query == null,
        ) { "Live Preview AI only accepts the local MJPEG stream." }

        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            instanceFollowRedirects = false
            connectTimeout = 3_000
            readTimeout = 8_000
            useCaches = false
        }
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("MJPEG snapshot request returned HTTP ${connection.responseCode}.")
            }
            readFirstJpeg(connection.inputStream, connection.contentType.orEmpty())
        } finally {
            connection.disconnect()
        }
    }

    internal fun readFirstJpeg(input: InputStream, contentType: String): ByteArray {
        val boundaryMatch = BOUNDARY_REGEX.find(contentType)
            ?: throw IOException("MJPEG response did not include a multipart boundary.")
        val boundary = boundaryMatch.groupValues.drop(1).firstOrNull(String::isNotEmpty)
            ?: throw IOException("MJPEG response included an empty multipart boundary.")
        val buffered = BufferedInputStream(input)
        if (readAsciiLine(buffered) != "--$boundary") {
            throw IOException("MJPEG response did not start with a frame boundary.")
        }

        var jpegContentType = false
        var contentLength: Int? = null
        var headerBytes = 0
        while (true) {
            val line = readAsciiLine(buffered)
            headerBytes += line.length + 2
            if (headerBytes > MAX_HEADER_BYTES) {
                throw IOException("MJPEG frame headers exceed the configured limit.")
            }
            if (line.isEmpty()) break
            val separator = line.indexOf(':')
            if (separator <= 0) continue
            when (line.substring(0, separator).trim().lowercase()) {
                "content-type" -> jpegContentType =
                    line.substring(separator + 1).trim().startsWith("image/jpeg", ignoreCase = true)
                "content-length" -> contentLength =
                    line.substring(separator + 1).trim().toIntOrNull()
            }
        }
        val length = contentLength
            ?: throw IOException("MJPEG frame did not include a content length.")
        if (!jpegContentType || length !in 1..MAX_JPEG_BYTES) {
            throw IOException("MJPEG frame content type or size is invalid.")
        }

        val jpeg = ByteArray(length)
        var offset = 0
        while (offset < jpeg.size) {
            val read = buffered.read(jpeg, offset, jpeg.size - offset)
            if (read < 0) throw EOFException("MJPEG frame ended before its declared content length.")
            offset += read
        }
        if (jpeg.size < 4 ||
            jpeg[0] != 0xff.toByte() ||
            jpeg[1] != 0xd8.toByte() ||
            jpeg[jpeg.lastIndex - 1] != 0xff.toByte() ||
            jpeg.last() != 0xd9.toByte()
        ) {
            throw IOException("MJPEG frame is not a complete JPEG.")
        }
        return jpeg
    }

    private fun readAsciiLine(input: InputStream): String {
        val line = ByteArrayOutputStream()
        var previous = -1
        while (line.size() <= MAX_HEADER_BYTES) {
            val value = input.read()
            if (value < 0) throw EOFException("MJPEG response ended before the frame was complete.")
            if (previous == '\r'.code && value == '\n'.code) {
                val bytes = line.toByteArray()
                return String(bytes, 0, (bytes.size - 1).coerceAtLeast(0), Charsets.US_ASCII)
            }
            line.write(value)
            previous = value
        }
        throw IOException("MJPEG line exceeds the configured limit.")
    }

    private val BOUNDARY_REGEX =
        Regex("""(?:^|;)\s*boundary=(?:"([^"]+)"|([^;\s]+))""", RegexOption.IGNORE_CASE)
}
