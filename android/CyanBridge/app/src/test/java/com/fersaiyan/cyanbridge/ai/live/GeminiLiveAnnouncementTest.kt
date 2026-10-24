package com.fersaiyan.cyanbridge.ai.live

import org.junit.Assert.assertEquals
import org.junit.Test

class GeminiLiveAnnouncementTest {

    @Test
    fun `unsupported languages fall back to English`() {
        assertEquals(
            "Live is busy. Please try again in a moment.",
            GeminiLiveAnnouncementMessages.text(GeminiLiveAnnouncement.FREE_BUSY, "ja-JP"),
        )
    }

    @Test
    fun `free proxy rotation is announced only near five minutes`() {
        assertEquals(
            null,
            GeminiLiveReconnectAnnouncementPolicy.resolve(true, true, true, 60_000, "network lost"),
        )
        assertEquals(
            GeminiLiveAnnouncement.FREE_SESSION_RESTARTING,
            GeminiLiveReconnectAnnouncementPolicy.resolve(true, true, true, 285_000, "abnormal close"),
        )
        assertEquals(
            GeminiLiveAnnouncement.FREE_SESSION_RESTARTING,
            GeminiLiveReconnectAnnouncementPolicy.resolve(true, true, true, 60_000, "free_session_rotation"),
        )
    }

    @Test
    fun `image cap announces the automatic replacement session`() {
        assertEquals(
            GeminiLiveAnnouncement.FREE_IMAGE_SESSION_RESTARTING,
            GeminiLiveReconnectAnnouncementPolicy.resolve(true, true, true, 30_000, "Live image limit reached"),
        )
    }
}
