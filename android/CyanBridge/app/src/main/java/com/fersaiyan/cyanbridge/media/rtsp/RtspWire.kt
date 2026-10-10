package com.fersaiyan.cyanbridge.media.rtsp

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.URI

/** A complete RTSP message. Its body is consumed exactly once, with its headers. */
internal data class RtspMessage(val lines: List<String>, val body: ByteArray = byteArrayOf()) {
    val firstLine: String get() = lines.first()

    fun header(name: String): String? = lines.drop(1).firstOrNull {
        it.substringBefore(':').equals(name, ignoreCase = true)
    }?.substringAfter(':')?.trim()

    fun requireOk(): RtspMessage {
        if (firstLine.split(' ').getOrNull(1) != "200") throw IOException(firstLine)
        return this
    }

    fun bytes(): ByteArray =
        (lines.joinToString("\r\n") + "\r\n\r\n").toByteArray(Charsets.US_ASCII) + body
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

internal data class RtspTrack(val controlUrl: String, val rtpChannel: Int, val rtcpChannel: Int, val mediaType: String)

internal data class RtspDescription(
    val aggregateUrl: String,
    val localSdp: ByteArray,
    val tracks: List<RtspTrack>,
) {
    companion object {
        fun from(response: RtspMessage, requestedUrl: String): RtspDescription {
            val base = response.header("Content-Base") ?: response.header("Content-Location") ?: requestedUrl
            val tracks = mutableListOf<RtspTrack>()
            var mediaIndex = -1
            var mediaType = ""
            var aggregate = base
            val rewritten = String(response.body, Charsets.US_ASCII).lineSequence()
                .filter { it.isNotBlank() }.map { line ->
                    when {
                        line.startsWith("c=") -> "c=IN IP4 127.0.0.1"
                        line.startsWith("m=") -> {
                            mediaIndex++
                            mediaType = line.substringAfter("m=").substringBefore(' ')
                            line
                        }
                        line.startsWith("a=control:") -> {
                            val control = line.substringAfter("a=control:")
                            if (mediaIndex < 0) {
                                if (control != "*") aggregate = resolveControl(base, control)
                                "a=control:*"
                            } else {
                                if (tracks.size != mediaIndex) throw IOException("Missing SDP track control")
                                tracks.add(RtspTrack(resolveControl(base, control), mediaIndex * 2, mediaIndex * 2 + 1, mediaType))
                                "a=control:track${mediaIndex + 1}"
                            }
                        }
                        else -> line // Preserve audio fmtp, H264 parameter sets and clock rates.
                    }
                }.toList()
            if (tracks.isEmpty() || tracks.size != mediaIndex + 1) throw IOException("Missing SDP tracks")
            return RtspDescription(aggregate, (rewritten.joinToString("\r\n") + "\r\n")
                .toByteArray(Charsets.US_ASCII), tracks)
        }

        private fun resolveControl(base: String, control: String): String =
            if (control.startsWith("rtsp://")) control else URI(base).resolve(control).toString()
    }
}
