package com.fersaiyan.cyanbridge.shared.media.rtsp

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RtspWireTest {
    private val sdp = listOf(
        "v=0", "o=- 1 1 IN IP4 0.0.0.0", "s=Nvt RTSP", "c=IN IP4 192.168.1.254", "t=0 0", "a=control:*",
        "m=video 0 RTP/AVP 96", "a=rtpmap:96 H264/90000", "a=control:trackID=1",
        "", "m=audio 0 RTP/AVP 97", "a=rtpmap:97 MPEG4-GENERIC/16000", "a=control:rtsp://10.0.0.1/a",
    ).joinToString("\r\n", postfix = "\r\n")

    private fun join(base: String, control: String) = base.substringBeforeLast('/') + "/" + control

    @Test
    fun messageSerializesHeadersAsAsciiAndFindsHeadersCaseInsensitively() {
        val message = RtspMessage(listOf("RTSP/1.0 200 OK", "cseq: 4", "Session: ab;timeout=60", "X: é😀"), byteArrayOf(1, 2))
        assertTrue(message.isOk)
        assertEquals("4", message.header("CSeq"))
        assertEquals("ab;timeout=60", message.header("session"))
        assertContentEquals(
            "RTSP/1.0 200 OK\r\ncseq: 4\r\nSession: ab;timeout=60\r\nX: ??\r\n\r\n".encodeToByteArray() + byteArrayOf(1, 2),
            message.bytes(),
        )
        assertFalse(RtspMessage(listOf("RTSP/1.0 454 Session Not Found")).isOk)
        assertEquals("a�b", rtspAsciiString(byteArrayOf('a'.code.toByte(), 0xC3.toByte(), 'b'.code.toByte())))
    }

    @Test
    fun describeIsRewrittenForLocalViewers() {
        val parsed = RtspDescription.parse(
            RtspMessage(listOf("RTSP/1.0 200 OK", "Content-Base: rtsp://192.168.1.254/live/"), sdp.encodeToByteArray()),
            "rtsp://192.168.1.254/xxx.mov",
            ::join,
        )
        assertEquals("rtsp://192.168.1.254/live/", parsed.aggregateUrl)
        assertEquals(
            listOf(
                RtspTrack("rtsp://192.168.1.254/live/trackID=1", 0, 1, "video"),
                RtspTrack("rtsp://10.0.0.1/a", 2, 3, "audio"),
            ),
            parsed.tracks,
        )
        assertEquals(
            listOf(
                "v=0", "o=- 1 1 IN IP4 0.0.0.0", "s=Nvt RTSP", "c=IN IP4 127.0.0.1", "t=0 0", "a=control:*",
                "m=video 0 RTP/AVP 96", "a=rtpmap:96 H264/90000", "a=control:track1",
                "m=audio 0 RTP/AVP 97", "a=rtpmap:97 MPEG4-GENERIC/16000", "a=control:track2",
            ).joinToString("\r\n", postfix = "\r\n"),
            parsed.localSdp.decodeToString(),
        )
    }

    @Test
    fun missingTrackControlsAreRejected() {
        val noControl = sdp.replace("a=control:trackID=1\r\n", "")
        assertFailsWith<RtspFormatException> {
            RtspDescription.parse(RtspMessage(listOf("RTSP/1.0 200 OK"), noControl.encodeToByteArray()), "rtsp://h/x", ::join)
        }
        assertFailsWith<RtspFormatException> {
            RtspDescription.parse(RtspMessage(listOf("RTSP/1.0 200 OK"), "v=0\r\n".encodeToByteArray()), "rtsp://h/x", ::join)
        }
    }
}
