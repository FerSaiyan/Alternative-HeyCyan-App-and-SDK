package com.fersaiyan.cyanbridge.plugins.walkingaid

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fersaiyan.cyanbridge.devices.eyevue.EyevueLiveConnection
import com.fersaiyan.cyanbridge.devices.eyevue.EyevueLivePreviewManager
import com.fersaiyan.cyanbridge.glasses.GlassesSession
import com.fersaiyan.cyanbridge.glasses.GlassesSessionCoordinator
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WalkingAidVideoCaptureLifecycleTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Test fun cancellationWhileConnectingClosesTransportAndReleasesLease() = runBlocking {
        val connecting = CompletableDeferred<Unit>()
        var closed = false
        val connection = object : EyevueLiveConnection {
            override suspend fun open(onStatus: (String, String) -> Unit): String {
                connecting.complete(Unit)
                awaitCancellation()
            }
            override suspend fun close() { closed = true }
        }
        val capture = WalkingAidLiveVideoCapture(context) { EyevueLivePreviewManager(context, connection) }
        val job = launch { capture.collect(WalkingAidVideoMode.EYEVUE_VIDEO) { it.bitmap.recycle() } }
        try {
            withTimeout(5_000) { connecting.await() }
            assertEquals(GlassesSession.LIVE_PREVIEW, GlassesSessionCoordinator.currentSession())
        } finally { job.cancelAndJoin() }
        assertTrue(closed)
        assertNull(GlassesSessionCoordinator.currentSession())
    }

    @Test fun transportFailureIsExplicitAndReleasesLease() = runBlocking {
        var closed = false
        val connection = object : EyevueLiveConnection {
            override suspend fun open(onStatus: (String, String) -> Unit): String = throw IOException("Eyevue Wi-Fi permission denied")
            override suspend fun close() { closed = true }
        }
        val capture = WalkingAidLiveVideoCapture(context) { EyevueLivePreviewManager(context, connection) }
        val error = runCatching {
            withTimeout(5_000) { capture.collect(WalkingAidVideoMode.EYEVUE_VIDEO) { it.bitmap.recycle() } }
        }.exceptionOrNull()
        assertTrue("Expected an actionable failure, got $error", error?.message.orEmpty().contains("permission denied"))
        assertTrue(closed)
        assertNull(GlassesSessionCoordinator.currentSession())
    }

    @Test fun walkingAidDoesNotTakeOverAnExistingGlassesTransport() = runBlocking {
        val lease = checkNotNull(GlassesSessionCoordinator.tryAcquireLease(GlassesSession.MEDIA_SYNC))
        var factoryCalled = false
        val capture = WalkingAidLiveVideoCapture(context) { factoryCalled = true; error("Must not create a second transport") }
        try {
            val error = runCatching { capture.collect(WalkingAidVideoMode.EYEVUE_VIDEO) {} }.exceptionOrNull()
            assertTrue(error is IOException)
            assertFalse(factoryCalled)
            assertTrue("Existing owner must keep its lease", GlassesSessionCoordinator.isActive(lease))
        } finally { GlassesSessionCoordinator.release(lease) }
    }
}
