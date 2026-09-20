package com.fersaiyan.cyanbridge.devices.mentra

import java.nio.charset.StandardCharsets
import org.junit.Assert.assertThrows
import org.junit.Test

/** Guardrails only; live camera transfer requires a physical Mentra Live. */
class MentraPhotoCaptureTest {
    @Test fun rejectsMissingRequestId() {
        val bytes = ("--b\r\nContent-Disposition: form-data; name=\"photo\"\r\n\r\n" +
            "image\r\n--b--\r\n").toByteArray(StandardCharsets.ISO_8859_1)
        assertThrows(IllegalArgumentException::class.java) {
            MentraPhotoCapture.parsePhoto(bytes, "b", "expected")
        }
    }
    @Test fun rejectsAnotherRequestsImage() {
        val bytes = ("--b\r\nContent-Disposition: form-data; name=\"requestId\"\r\n\r\n" +
            "other\r\n--b\r\nContent-Disposition: form-data; name=\"photo\"\r\n\r\n" +
            "image\r\n--b--\r\n").toByteArray(StandardCharsets.ISO_8859_1)
        assertThrows(IllegalArgumentException::class.java) {
            MentraPhotoCapture.parsePhoto(bytes, "b", "expected")
        }
    }
}
