package com.fersaiyan.cyanbridge.ai

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class AiQuestionForegroundServiceTest {
    private val context: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun `a question that ends before the service starts stops it once it is in the foreground`() {
        AiQuestionForegroundService.start(context, "Listening")
        val startIntent = shadowOf(context).nextStartedService
        AiQuestionForegroundService.stop(context)

        // Stopping it now would make Android kill the app: it has not called startForeground yet.
        assertNull(shadowOf(context).nextStoppedService)

        val controller = Robolectric.buildService(AiQuestionForegroundService::class.java, startIntent).create().startCommand(0, 1)
        val service = shadowOf(controller.get())
        assertNotEquals(0, service.lastForegroundNotificationId)
        assertTrue(service.isStoppedBySelf)
        controller.destroy()
    }
}
