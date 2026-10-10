package com.fersaiyan.cyanbridge.devices.metarayban

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.core.types.LinkState
import com.meta.wearable.dat.mockdevice.MockDeviceKit
import com.meta.wearable.dat.mockdevice.api.GlassesModel
import com.meta.wearable.dat.mockdevice.api.MockDeviceKitConfig
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the production manager through Meta's actual MockDeviceKit, not the UI bypass. */
@RunWith(AndroidJUnit4::class)
class MetaDatSdkMockTest {
    @get:Rule val permissions: GrantPermissionRule = GrantPermissionRule.grant(*MetaDatPermissions.required())
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val manager get() = MetaRaybanManager.getInstance(context)
    private val kit get() = MockDeviceKit.getInstance(context)

    @After fun tearDown() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            manager.destroy()
            kit.disable()
        }
    }

    @Test fun realSdkMockConnectsAuthorizesCapturesAndReleasesOneShotSession() = runBlocking {
        withTimeout(30_000) {
            // Both native stacks must load in one process with the packaged shared STL.
            // A UI-only DAT mock would miss a LibVLC/fbjni symbol collision here.
            withContext(Dispatchers.Main) { org.videolan.libvlc.LibVLC(context).release() }
            val glasses = withContext(Dispatchers.Main) {
                kit.enable(MockDeviceKitConfig(initiallyRegistered = true, initialPermissionsGranted = false))
                val paired = kit.pairGlasses(GlassesModel.RAYBAN_META).fold(
                    onSuccess = { it }, onFailure = { error, _ -> throw AssertionError(error.description) },
                )
                paired.powerOn()
                paired.unfold()
                paired.don()
                manager.initialize()
                paired
            }
            assertFalse("Must use the production DAT path", manager.isDebugMockEnabled())
            assertTrue("DAT link should connect", manager.awaitCameraReady())
            assertEquals(MetaRaybanManager.RegistrationState.REGISTERED, manager.registrationState.value)

            val denied = CompletableDeferred<Boolean>()
            withContext(Dispatchers.Main) {
                manager.checkCameraPermission({ denied.complete(false) }, { denied.complete(true) }, { denied.completeExceptionally(AssertionError(it)) })
            }
            assertTrue("Denied camera must request Meta authorization", denied.await())
            kit.permissions.set(Permission.CAMERA, PermissionStatus.Granted)
            val granted = CompletableDeferred<Boolean>()
            withContext(Dispatchers.Main) {
                manager.checkCameraPermission({ granted.complete(true) }, { granted.complete(false) }, { granted.completeExceptionally(AssertionError(it)) })
            }
            assertTrue(granted.await())

            val video = File(context.cacheDir, "meta-sdk-camera-feed.mp4")
            InstrumentationRegistry.getInstrumentation().context.assets.open("meta/camera-feed.mp4").use { input ->
                video.outputStream().use { input.copyTo(it) }
            }
            val image = File(context.cacheDir, "meta-sdk-captured.jpg")
            val bitmap = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(android.graphics.Color.CYAN)
            image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            bitmap.recycle()
            glasses.services.camera.setCameraFeed(Uri.fromFile(video))
            glasses.services.camera.setCapturedImage(Uri.fromFile(image))
            val photo = manager.capturePhotoOnce()
            assertTrue(photo.bytes.isNotEmpty())
            assertNotNull(BitmapFactory.decodeByteArray(photo.bytes, 0, photo.bytes.size)?.also { it.recycle() })
            assertFalse(manager.isStreaming.value)
            manager.deviceSessionState.first { it == MetaRaybanManager.DeviceSessionState.IDLE }

            glasses.powerOff()
            manager.selectedDeviceLinkState.first { it != LinkState.CONNECTED }
            assertFalse("A discovered powered-off device must not be camera ready", manager.isCameraReady())
            assertFalse("Reconnect must refresh camera authorization", manager.cameraPermissionGranted.value)
        }
    }
}
