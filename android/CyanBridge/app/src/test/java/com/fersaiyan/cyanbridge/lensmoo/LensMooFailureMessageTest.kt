package com.fersaiyan.cyanbridge.lensmoo

import org.junit.Assert.*
import org.junit.Test

class LensMooFailureMessageTest {
    @Test fun stopConfirmationFailureIsNotReportedAsAiAnswer() {
        val message=LensMooFailureMessage.forFailure(LensMooFailureMessage.Stage.CAPTURE,IllegalStateException("Camera state timeout: 5710"))
        assertTrue(message.contains("не подтвердили выключение камеры"))
        assertTrue(message.contains("AI не запрашивался"))
    }
    @Test fun remoteErrorBodyAndCredentialsAreNotSpoken() {
        val message=LensMooFailureMessage.forFailure(LensMooFailureMessage.Stage.REMOTE,IllegalStateException("HTTP 401 token=private-secret"))
        assertTrue(message.contains("AI-сервера"))
        assertFalse(message.contains("private-secret"))
        assertFalse(message.contains("AI не запрашивался"))
    }
    @Test fun saveFailureIsIdentifiedBeforeNetworkRequest() {
        val message=LensMooFailureMessage.forFailure(LensMooFailureMessage.Stage.SAVE,java.io.IOException("disk full"))
        assertTrue(message.contains("сохранить фото"))
        assertTrue(message.contains("AI не запрашивался"))
    }
}
