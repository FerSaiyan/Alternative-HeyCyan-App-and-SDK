package com.fersaiyan.cyanbridge.plugins.meetingsparknotes

import android.app.Service
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MeetingSparkNotesServiceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @After
    fun clearPreferences() {
        context.getSharedPreferences("meeting_spark_notes_prefs", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun enabledPluginDoesNotCaptureWithoutExplicitStartAction() {
        MeetingSparkNotesPreferences.setEnabled(context, true)
        val controller = Robolectric.buildService(MeetingSparkNotesService::class.java).create()
        val service = controller.get()

        val result = service.onStartCommand(
            Intent(context, MeetingSparkNotesService::class.java),
            0,
            1,
        )

        assertEquals(Service.START_NOT_STICKY, result)
        assertTrue(shadowOf(service).isStoppedBySelf)
        assertNull(shadowOf(service).lastForegroundNotification)
        controller.destroy()
    }
}
