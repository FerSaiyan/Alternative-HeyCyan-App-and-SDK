package com.fersaiyan.cyanbridge.ota

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException

class MjpegSnapshotCaptureTest {
    @Test
    fun readsTheFirstCompleteJpegFromMultipartStream() {
        val jpeg = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0x01, 0x02, 0xff.toByte(), 0xd9.toByte())
        val body = buildString {
            append("--frame-boundary\r\n")
            append("Content-Type: image/jpeg\r\n")
            append("Content-Length: ${jpeg.size}\r\n\r\n")
        }.toByteArray(Charsets.US_ASCII) + jpeg + "\r\n--frame-boundary\r\n".toByteArray(Charsets.US_ASCII)

        val result = MjpegSnapshotCapture.readFirstJpeg(
            ByteArrayInputStream(body),
            "multipart/x-mixed-replace; boundary=frame-boundary",
        )

        assertArrayEquals(jpeg, result)
    }

    @Test
    fun rejectsIncompleteJpegFrames() {
        val body = "--frame\r\nContent-Type: image/jpeg\r\nContent-Length: 3\r\n\r\nabc"
            .toByteArray(Charsets.US_ASCII)

        assertThrows(IOException::class.java) {
            MjpegSnapshotCapture.readFirstJpeg(
                ByteArrayInputStream(body),
                "multipart/x-mixed-replace; boundary=\"frame\"",
            )
        }
    }
}
