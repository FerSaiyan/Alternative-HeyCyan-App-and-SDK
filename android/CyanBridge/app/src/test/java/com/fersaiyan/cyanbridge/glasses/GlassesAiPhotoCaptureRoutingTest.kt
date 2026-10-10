package com.fersaiyan.cyanbridge.glasses

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.fersaiyan.cyanbridge.ai.image.ImageThumbnailQuality
import com.fersaiyan.cyanbridge.ai.live.GeminiLiveGlassesImageCapture
import com.fersaiyan.cyanbridge.devices.DeviceProfileStore
import com.fersaiyan.cyanbridge.plugins.walkingaid.WalkingAidImageCapture
import com.fersaiyan.cyanbridge.shared.devices.DeviceClass
import com.fersaiyan.cyanbridge.shared.devices.DeviceProfile
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class GlassesAiPhotoCaptureRoutingTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun select(device: DeviceClass) {
        DeviceProfileStore.saveLastSelected(context, DeviceProfile("00:11:22:33:44:55", "Test", device, device, true))
    }

    @After fun clear() {
        context.getSharedPreferences("device_profile", Context.MODE_PRIVATE).edit().clear().commit()
        GlassesSessionCoordinator.clearForDisconnectedDevice()
    }

    @Test fun `Live Walking Aid and visual notes fail on their selected native transport without HeyCyan fallback`() = runTest {
        for (device in listOf(DeviceClass.EYEVUE, DeviceClass.TUNEBUDS)) {
            select(device)
            val failures = listOf(
                runCatching { GlassesAiPhotoCapture(context).capture(2) }.exceptionOrNull(),
                runCatching { GeminiLiveGlassesImageCapture(context).capture(ImageThumbnailQuality.CLEARER) }.exceptionOrNull(),
                runCatching { WalkingAidImageCapture(context).captureFreshThumbnailWithQuality("native_route", 3) }.exceptionOrNull(),
            )
            for (error in failures) {
                assertNotNull(error)
                assertTrue(error!!.message.orEmpty(), error.message.orEmpty().contains(device.name))
            }
            assertFalse(HeyCyanPhotoCapture.isActive())
            assertNull(GlassesPhotoCapture.currentDevice())
            assertTrue(GlassesSessionCoordinator.canRunBackgroundCommand())
        }
    }

    @Test fun `unsupported selection never tries another glasses camera`() = runTest {
        select(DeviceClass.UNKNOWN)
        val error = runCatching { GlassesAiPhotoCapture(context).capture(2) }.exceptionOrNull()
        assertEquals("Selected glasses do not support native AI-photo capture", error?.message)
        assertTrue(GlassesSessionCoordinator.canRunBackgroundCommand())
    }
}
