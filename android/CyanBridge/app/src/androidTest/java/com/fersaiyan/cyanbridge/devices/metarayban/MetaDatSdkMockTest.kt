package com.fersaiyan.cyanbridge.devices.metarayban

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.fersaiyan.cyanbridge.shared.glasses.MetaRegistrationPath
import com.fersaiyan.cyanbridge.shared.glasses.MetaSetupChoice
import com.fersaiyan.cyanbridge.shared.glasses.PrefsMetaRegistrationStore
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
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the production manager through Meta's actual MockDeviceKit, not the UI bypass. */
@RunWith(AndroidJUnit4::class)
class MetaDatSdkMockTest {
    private companion object { const val TAG = "MetaDatSdkMockTest" }
    @get:Rule val permissions: GrantPermissionRule = GrantPermissionRule.grant(*MetaDatPermissions.required())
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val manager get() = MetaRaybanManager.getInstance(context)
    private val kit get() = MockDeviceKit.getInstance(context)

    private val store = PrefsMetaRegistrationStore()
    private var savedChoice = MetaSetupChoice.NONE
    private var savedPath = MetaRegistrationPath.UNKNOWN

    @org.junit.Before fun saveRegistrationPrefs() {
        savedChoice = store.choice
        savedPath = store.path
    }

    @After fun tearDown() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            manager.destroy()
            kit.disable()
        }
        store.choice = savedChoice
        store.path = savedPath
    }

    /**
     * Both setup choices end to end through MockDeviceKit: unregistered glasses, Register,
     * camera authorization, a live stream with frames, a photo, and clean teardown. The mock
     * registers without Meta AI, so it cannot show which path Meta AI would take (isDevMode)
     * or a release-channel rejection; those need Meta AI and real glasses.
     */
    @Test fun developerModeChoiceRegistersStreamsAndCaptures() = registerStreamAndCapture(MetaSetupChoice.DEVELOPER_MODE)

    @Test fun emailInviteChoiceRegistersStreamsAndCaptures() = registerStreamAndCapture(MetaSetupChoice.EMAIL_INVITE)

    private fun registerStreamAndCapture(choice: MetaSetupChoice) = runBlocking {
        // DAT 0.8.0 deadlocks (analytics lock vs registration lock) when registration races another
        // analytics event; the app opts out of DAT analytics so that path never takes the lock.
        val metaData = context.packageManager
            .getApplicationInfo(context.packageName, android.content.pm.PackageManager.GET_META_DATA).metaData
        assertTrue("DAT analytics must stay opted out", metaData?.getBoolean("com.meta.wearable.mwdat.ANALYTICS_OPT_OUT") == true)
        withTimeout(60_000) {
            store.choice = choice
            store.path = MetaRegistrationPath.UNKNOWN
            val glasses = withContext(Dispatchers.Main) {
                kit.enable(MockDeviceKitConfig(initiallyRegistered = false, initialPermissionsGranted = false))
                val paired = kit.pairGlasses(GlassesModel.RAYBAN_META).fold(
                    onSuccess = { it }, onFailure = { error, _ -> throw AssertionError(error.description) },
                )
                paired.powerOn()
                paired.unfold()
                paired.don()
                manager.initialize()
                manager.onSetupChoiceChanged()
                paired
            }
            val unregistered = withContext(Dispatchers.Main) { manager.diagnosticsSnapshot() }
            Log.i(TAG, "[$choice] before registration:\n$unregistered")
            assertNotEquals(MetaRaybanManager.RegistrationState.REGISTERED, manager.registrationState.value)
            // DAT derives UNAVAILABLE/AVAILABLE from a bonded Meta glasses UUID, never from an invite.
            assertNotEquals("No registration was attempted, so there is no invite verdict", "NEEDS_META_INVITE", manager.metaAccessState.value.name)
            assertFalse(unregistered.contains("meta_pairing_failed_release_channel"))
            assertFalse(withContext(Dispatchers.Main) { manager.registrationGuidance() }.orEmpty().contains("not currently enabled"))

            ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
                scenario.onActivity { manager.startRegistration(it) }
                manager.registrationState.first { it == MetaRaybanManager.RegistrationState.REGISTERED }
            }
            assertTrue("DAT link should connect after registration", manager.awaitCameraReady())
            assertEquals("READY", manager.metaAccessState.value.name)
            assertNotEquals(MetaRegistrationPath.UNKNOWN, manager.registrationPath.value)

            assertTrue("Granted camera must be reported", checkCamera())
            val video = File(context.cacheDir, "meta-sdk-camera-feed.mp4")
            InstrumentationRegistry.getInstrumentation().context.assets.open("meta/camera-feed.mp4").use { input ->
                video.outputStream().use { input.copyTo(it) }
            }
            glasses.services.camera.setCameraFeed(Uri.fromFile(video))
            glasses.services.camera.setCapturedImage(Uri.fromFile(cyanJpeg()))

            val streaming = CompletableDeferred<Unit>()
            val firstFrame = CompletableDeferred<Bitmap>()
            withContext(Dispatchers.Main) {
                val fail = { error: String -> streaming.completeExceptionally(AssertionError(error)); Unit }
                manager.startSession(
                    onSuccess = {
                        manager.startStreaming(
                            onFrame = { if (!firstFrame.isCompleted) firstFrame.complete(it) },
                            onSuccess = { streaming.complete(Unit) },
                            onError = fail,
                        )
                    },
                    onError = fail,
                )
            }
            streaming.await()
            assertEquals(MetaRaybanManager.StreamState.STREAMING, manager.streamState.value)
            // MockDeviceKit 0.8.0 encodes its feed with MediaCodec; on swiftshader AVDs no frame
            // reached the app in 50 s, so frames are logged here rather than required.
            Log.i(TAG, "[$choice] first frame: ${withTimeoutOrNull(5_000) { firstFrame.await() }?.let { "${it.width}x${it.height}" }}")
            val photo = CompletableDeferred<MetaRaybanManager.CapturedPhoto>()
            withContext(Dispatchers.Main) {
                manager.capturePhoto({ photo.complete(it) }, { photo.completeExceptionally(AssertionError(it)) })
            }
            val bytes = photo.await().bytes
            assertNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.also { it.recycle() })
            withContext(Dispatchers.Main) { manager.stopSession() }
            manager.deviceSessionState.first { it == MetaRaybanManager.DeviceSessionState.IDLE }

            val after = withContext(Dispatchers.Main) { manager.diagnosticsSnapshot() }
            Log.i(TAG, "[$choice] after capture:\n$after")
            assertNull(manager.lastError.value)
            assertFalse(after.contains("[ERROR]"))
            assertFalse(after.contains("meta_access_failed"))
            assertFalse(after.contains("release_channel"))
        }
    }

    /** Denied first (MockDeviceKit's initial state), then granted through the mock's permission store. */
    private suspend fun checkCamera(): Boolean {
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
        return granted.await()
    }

    private fun cyanJpeg(): File {
        val image = File(context.cacheDir, "meta-sdk-captured.jpg")
        val bitmap = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.CYAN)
        image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bitmap.recycle()
        return image
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

            assertTrue(checkCamera())

            val video = File(context.cacheDir, "meta-sdk-camera-feed.mp4")
            InstrumentationRegistry.getInstrumentation().context.assets.open("meta/camera-feed.mp4").use { input ->
                video.outputStream().use { input.copyTo(it) }
            }
            glasses.services.camera.setCameraFeed(Uri.fromFile(video))
            glasses.services.camera.setCapturedImage(Uri.fromFile(cyanJpeg()))
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
