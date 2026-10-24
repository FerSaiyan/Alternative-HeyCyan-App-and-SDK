package com.fersaiyan.cyanbridge.media.rtsp

import com.fersaiyan.cyanbridge.shared.media.rtsp.RtspDescription
import com.fersaiyan.cyanbridge.shared.media.rtsp.RtspFormatException
import com.fersaiyan.cyanbridge.shared.media.rtsp.RtspMessage
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.URI

// Message format and SDP rewriting live in shared code; this file adds the blocking socket I/O.

internal fun RtspMessage.requireOk(): RtspMessage = if (isOk) this else throw IOException(firstLine)

internal fun RtspDescription.Companion.from(response: RtspMessage, requestedUrl: String): RtspDescription = try {
    parse(response, requestedUrl) { base, control -> URI(base).resolve(control).toString() }
} catch (error: RtspFormatException) {
    throw IOException(error.message)
}

internal fun readRtspMessage(input: InputStream, first: Int = input.read()): RtspMessage? {
    if (first < 0) return null
    val lines = mutableListOf<String>()
    val line = StringBuilder().append(first.toChar())
    var total = 1
    while (true) {
        val byte = input.read()
        if (byte < 0) throw EOFException("Incomplete RTSP headers")
        if (++total > 64 * 1024) throw IOException("RTSP headers too large")
        if (byte == '\n'.code) {
            val text = line.toString().removeSuffix("\r")
            line.clear()
            if (text.isEmpty()) break
            lines.add(text)
        } else {
            line.append(byte.toChar())
        }
    }
    val headers = RtspMessage(lines)
    val size = headers.header("Content-Length")?.let {
        it.toIntOrNull()?.takeIf { count -> count in 0..1_048_576 }
            ?: throw IOException("Invalid RTSP Content-Length: $it")
    } ?: 0
    return headers.copy(body = readRtspBytes(input, size))
}

internal fun readRtspBytes(input: InputStream, count: Int): ByteArray {
    val result = ByteArray(count)
    var offset = 0
    while (offset < count) {
        val size = input.read(result, offset, count - offset)
        if (size < 0) throw EOFException("Incomplete RTSP body/packet ($offset/$count)")
        offset += size
    }
    return result
}
