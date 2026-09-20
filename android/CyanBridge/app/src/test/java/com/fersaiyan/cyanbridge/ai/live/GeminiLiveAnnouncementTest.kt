package com.fersaiyan.cyanbridge.ai.live

import org.junit.Assert.assertEquals
import org.junit.Test

class GeminiLiveAnnouncementTest {
    @Test
    fun `free renewal describes the automatic new session`() {
        assertEquals(
            "Your free Live session has ended. Starting a new free Gemini Live session.",
            GeminiLiveAnnouncementMessages.text(GeminiLiveAnnouncement.FREE_SESSION_RESTARTING, "en-US"),
        )
    }

    @Test
    fun `terminal image message says trigger rather than say`() {
        assertEquals(
            "Image limit reached for this session. Trigger Live again to start a new one.",
            GeminiLiveAnnouncementMessages.text(GeminiLiveAnnouncement.IMAGE_LIMIT_REACHED, "en"),
        )
    }

    @Test
    fun `terminal session message says trigger rather than say`() {
        assertEquals(
            "Your Live session has ended. Trigger Live again to start a new one.",
            GeminiLiveAnnouncementMessages.text(GeminiLiveAnnouncement.SESSION_ENDED, "en"),
        )
    }

    @Test
    fun `unsupported languages fall back to English`() {
        assertEquals(
            "Live is busy. Please try again in a moment.",
            GeminiLiveAnnouncementMessages.text(GeminiLiveAnnouncement.FREE_BUSY, "ja-JP"),
        )
    }

    @Test
    fun `Private active-session error is distinct from a generic Google failure`() {
        assertEquals(
            "Another Live session is still active. End it before starting Private Live.",
            GeminiLiveAnnouncementMessages.text(GeminiLiveAnnouncement.PRIVATE_SESSION_BUSY, "en-US"),
        )
        assertEquals(
            "Too many Live session starts. Wait a few minutes before trying Private Live again.",
            GeminiLiveAnnouncementMessages.text(GeminiLiveAnnouncement.PRIVATE_RATE_LIMITED, "en-US"),
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
