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
}
