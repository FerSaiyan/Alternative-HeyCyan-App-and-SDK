package com.fersaiyan.cyanbridge.shared.media.rtsp

/** RTSP or SDP content the relay cannot use. Platform adapters map it to their I/O error. */
class RtspFormatException(message: String) : Exception(message)

/** A complete RTSP message. Its body is consumed exactly once, with its headers. */
data class RtspMessage(val lines: List<String>, val body: ByteArray = byteArrayOf()) {
    val firstLine: String get() = lines.first()

    val isOk: Boolean get() = firstLine.split(' ').getOrNull(1) == "200"

    fun header(name: String): String? = lines.drop(1).firstOrNull {
        it.substringBefore(':').equals(name, ignoreCase = true)
    }?.substringAfter(':')?.trim()

    fun bytes(): ByteArray = rtspAsciiBytes(lines.joinToString("\r\n") + "\r\n\r\n") + body
}

data class RtspTrack(val controlUrl: String, val rtpChannel: Int, val rtcpChannel: Int, val mediaType: String)

data class RtspDescription(
    val aggregateUrl: String,
    val localSdp: ByteArray,
    val tracks: List<RtspTrack>,
) {
    companion object {
        /**
         * Rewrites a DESCRIBE response for local viewers: connection set to 127.0.0.1, track
         * controls renamed `trackN` (interleaved channels 2N-2/2N-1), everything else kept.
         * [resolveControl] resolves a relative control against the base URL like java.net.URI.
         */
        fun parse(
            response: RtspMessage,
            requestedUrl: String,
            resolveControl: (base: String, control: String) -> String,
        ): RtspDescription {
            val base = response.header("Content-Base") ?: response.header("Content-Location") ?: requestedUrl
            fun resolve(control: String) = if (control.startsWith("rtsp://")) control else resolveControl(base, control)
            val tracks = mutableListOf<RtspTrack>()
            var mediaIndex = -1
            var mediaType = ""
            var aggregate = base
            val rewritten = rtspAsciiString(response.body).lineSequence()
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
                                if (control != "*") aggregate = resolve(control)
                                "a=control:*"
                            } else {
                                if (tracks.size != mediaIndex) throw RtspFormatException("Missing SDP track control")
                                tracks.add(RtspTrack(resolve(control), mediaIndex * 2, mediaIndex * 2 + 1, mediaType))
                                "a=control:track${mediaIndex + 1}"
                            }
                        }
                        else -> line // Preserve audio fmtp, H264 parameter sets and clock rates.
                    }
                }.toList()
            if (tracks.isEmpty() || tracks.size != mediaIndex + 1) throw RtspFormatException("Missing SDP tracks")
            return RtspDescription(aggregate, rtspAsciiBytes(rewritten.joinToString("\r\n") + "\r\n"), tracks)
        }
    }
}

/** US-ASCII encoding with '?' for anything else, one per code point (like the JVM encoder). */
fun rtspAsciiBytes(text: String): ByteArray {
    val out = ByteArray(text.length)
    var size = 0
    var i = 0
    while (i < text.length) {
        val c = text[i]
        out[size++] = if (c.code < 0x80) c.code.toByte() else '?'.code.toByte()
        i += if (c.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate()) 2 else 1
    }
    return out.copyOf(size)
}

/** US-ASCII decoding with U+FFFD for bytes above 0x7F (like the JVM decoder). */
fun rtspAsciiString(bytes: ByteArray): String =
    CharArray(bytes.size) { if (bytes[it] >= 0) bytes[it].toInt().toChar() else '�' }.concatToString()
