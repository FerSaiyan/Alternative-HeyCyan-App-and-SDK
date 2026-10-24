package com.fersaiyan.cyanbridge.localagent

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.fersaiyan.cyanbridge.agent.LocalAgentPrefs
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LocalAgentPrefsTest {
    @Test
    fun `WhatsApp notification read aloud remains opt in`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        LocalAgentPrefs.setWhatsAppNotificationReadAloudEnabled(context, false)
        assertFalse(LocalAgentPrefs.isWhatsAppNotificationReadAloudEnabled(context))

        LocalAgentPrefs.setWhatsAppNotificationReadAloudEnabled(context, true)
        assertTrue(LocalAgentPrefs.isWhatsAppNotificationReadAloudEnabled(context))

        LocalAgentPrefs.setWhatsAppNotificationReadAloudEnabled(context, false)
    }

    @Test
    fun `remote screenshot upload requires a separate capture opt in`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        LocalAgentPrefs.setScreenshotPlanningEnabled(context, false)
        LocalAgentPrefs.setRemoteScreenshotUploadEnabled(context, true)
        assertFalse(LocalAgentPrefs.isRemoteScreenshotUploadEnabled(context))

        LocalAgentPrefs.setScreenshotPlanningEnabled(context, true)
        LocalAgentPrefs.setRemoteScreenshotUploadEnabled(context, true)
        assertTrue(LocalAgentPrefs.isScreenshotPlanningEnabled(context))
        assertTrue(LocalAgentPrefs.isRemoteScreenshotUploadEnabled(context))

        LocalAgentPrefs.setScreenshotPlanningEnabled(context, false)
        assertFalse(LocalAgentPrefs.isRemoteScreenshotUploadEnabled(context))
    }

    @Test
    fun `Telegram allowed chat id is normalized and validated`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertTrue(LocalAgentPrefs.setTelegramAllowedChatId(context, "-1001234567890"))
        assertTrue(LocalAgentPrefs.getTelegramAllowedChatId(context) == "-1001234567890")
        assertFalse(LocalAgentPrefs.setTelegramAllowedChatId(context, "any-chat"))
    }
}
