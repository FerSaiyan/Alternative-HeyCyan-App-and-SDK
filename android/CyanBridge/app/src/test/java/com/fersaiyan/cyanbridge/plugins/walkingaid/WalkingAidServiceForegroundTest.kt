package com.fersaiyan.cyanbridge.plugins.walkingaid

import android.app.Service
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class WalkingAidServiceForegroundTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @After
    fun cleanUp() {
        WalkingAidPreferences.setEnabled(context, false)
        File(context.filesDir, "yolo11n_float16.tflite").delete()
    }

    @Test
    fun startActionPromotesBeforeRejectingAnUnreadyService() {
        File(context.filesDir, "yolo11n_float16.tflite").delete()
        val controller = Robolectric.buildService(WalkingAidService::class.java).create()
        val service = controller.get()

        val result = service.onStartCommand(
            Intent(context, WalkingAidService::class.java).setAction(WalkingAidService.ACTION_START),
            0,
            42,
        )

        val shadowService = shadowOf(service)
        assertEquals(
            WalkingAidNotificationHelper.NOTIFICATION_ID,
            shadowService.lastForegroundNotificationId,
        )
        assertTrue(shadowService.isForegroundStopped)
        assertTrue(shadowService.isStoppedBySelf)
        assertEquals(42, shadowService.stopSelfId)
        assertEquals(Service.START_NOT_STICKY, result)
        controller.destroy()
    }
}
