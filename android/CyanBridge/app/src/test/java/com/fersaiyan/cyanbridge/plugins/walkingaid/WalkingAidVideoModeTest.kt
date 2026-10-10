package com.fersaiyan.cyanbridge.plugins.walkingaid

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import com.fersaiyan.cyanbridge.plugins.walkingaid.vision.VisionFrame
import com.fersaiyan.cyanbridge.shared.devices.DeviceClass
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WalkingAidVideoModeTest {
    @Test fun liveSourcesAreGatedToTheirOwnDeviceAndSupportedAndroidVersion() {
        DeviceClass.entries.forEach { device ->
            assertEquals(device == DeviceClass.HEY_CYAN, WalkingAidVideoMode.HEYCYAN_VIDEO.supports(device, 34))
            assertEquals(device == DeviceClass.META_RAYBAN, WalkingAidVideoMode.META_VIDEO.supports(device, 34))
            assertEquals(device == DeviceClass.EYEVUE, WalkingAidVideoMode.EYEVUE_VIDEO.supports(device, 34))
        }
        assertFalse(WalkingAidVideoMode.EYEVUE_VIDEO.supports(DeviceClass.EYEVUE, 28))
        assertTrue(WalkingAidVideoMode.EYEVUE_VIDEO.supports(DeviceClass.EYEVUE, 29))
    }

    @Test fun photosRemainDefaultAndVideoChoicePersists() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("walking_aid_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        assertEquals(WalkingAidVideoMode.PERIODIC_PHOTOS, WalkingAidPreferences.getVideoMode(context))
        WalkingAidPreferences.setVideoMode(context, WalkingAidVideoMode.META_VIDEO)
        assertEquals(WalkingAidVideoMode.META_VIDEO, WalkingAidPreferences.getVideoMode(context))
    }

    @Test fun slowDetectorGetsLatestFrameAndDroppedBitmapsAreReleased() {
        val queue = WalkingAidFrameQueue()
        val inFlight = VisionFrame(Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888), captureIndex = 1)
        val stale = VisionFrame(Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888), captureIndex = 2)
        val latest = VisionFrame(Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888), captureIndex = 3)
        queue.offer(inFlight)
        assertSame(inFlight, queue.frames.tryReceive().getOrThrow())
        queue.offer(stale)
        queue.offer(latest)
        assertTrue(stale.bitmap.isRecycled)
        assertFalse("In-flight inference must retain its bitmap", inFlight.bitmap.isRecycled)
        assertSame(latest, queue.frames.tryReceive().getOrThrow())
        inFlight.bitmap.recycle()
        latest.bitmap.recycle()
        val pending = VisionFrame(Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888))
        queue.offer(pending)
        queue.clear()
        assertTrue("Shutdown must release pending frames", pending.bitmap.isRecycled)
    }
}
