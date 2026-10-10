package com.fersaiyan.cyanbridge.glasses

import com.fersaiyan.cyanbridge.ai.live.GeminiLiveImageButtonRouter
import com.fersaiyan.cyanbridge.ai.live.ManualGlassesPhoto
import com.fersaiyan.cyanbridge.shared.devices.DeviceClass
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class NativeGlassesPhotoCaptureTest {
    private val image = byteArrayOf(1, 2, 3)

    @Before @After fun clear() { GlassesSessionCoordinator.clearForDisconnectedDevice() }

    private fun TestScope.receiver(
        device: DeviceClass = DeviceClass.EYEVUE,
        trigger: suspend () -> Unit = {},
        reset: () -> Unit = {},
        decode: (ByteArray) -> Boolean = { it.contentEquals(image) },
    ) = NativeGlassesPhotoCapture(device, { true }, trigger, reset, decode,
        clock = { testScheduler.currentTime },
        manualScope = CoroutineScope(backgroundScope.coroutineContext + SupervisorJob(backgroundScope.coroutineContext.job)))

    @Test fun `native capture excludes other families and all exclusive operations`() = runTest {
        for (device in listOf(DeviceClass.EYEVUE, DeviceClass.TUNEBUDS)) {
            val receiver = receiver(device)
            val capture = async { receiver.capture(5_000) }
            runCurrent()
            assertEquals(device, GlassesPhotoCapture.currentDevice())
            for (session in GlassesSession.entries) assertNull(GlassesSessionCoordinator.tryAcquireLease(session))
            assertTrue(runCatching { GlassesPhotoCapture.withCapture(DeviceClass.HEY_CYAN) { } }.isFailure)
            assertTrue(receiver.onPhoto(image))
            assertArrayEquals(image, capture.await())
            assertNull(GlassesPhotoCapture.currentDevice())
            assertTrue(GlassesSessionCoordinator.canRunBackgroundCommand())
        }
    }

    @Test fun `deadline includes hung command acknowledgement and next capture recovers`() = runTest {
        var hang = true
        var resets = 0
        val receiver = receiver(trigger = { if (hang) CompletableDeferred<Unit>().await() }, reset = { resets++ })
        val capture = async { runCatching { receiver.capture(100) } }
        runCurrent()
        advanceTimeBy(100); runCurrent()
        assertTrue(receiver.onPhoto(image)) // Late completion during cleanup is dropped.
        assertNull(GlassesSessionCoordinator.tryAcquireLease(GlassesSession.MEDIA_SYNC))
        assertTrue(capture.await().exceptionOrNull() is GlassesPhotoTransferException)
        assertEquals(850, testScheduler.currentTime.toInt())
        assertEquals(2, resets)
        hang = false
        val recovered = async { receiver.capture(1_000) }
        runCurrent()
        receiver.onPhoto(image)
        assertArrayEquals(image, recovered.await())
    }

    @Test fun `invalid jpeg fails acquisition and releases shared ownership`() = runTest {
        val receiver = receiver(decode = { false })
        val capture = async { runCatching { receiver.capture(1_000) } }
        runCurrent()
        receiver.onPhoto(image)
        assertTrue(capture.await().exceptionOrNull() is GlassesPhotoTransferException)
        assertTrue(GlassesSessionCoordinator.canRunBackgroundCommand())
    }

    @Test fun `cancellation keeps lease until stream reset and quiet drain finish`() = runTest {
        var resets = 0
        val receiver = receiver(reset = { resets++ })
        val capture = async { receiver.capture(5_000) }
        runCurrent()
        capture.cancel()
        runCurrent()
        assertTrue(receiver.onPhoto(image))
        assertEquals(1, resets)
        assertNull(GlassesSessionCoordinator.tryAcquireBackgroundCommand())
        capture.cancelAndJoin()
        assertEquals(2, resets)
        assertTrue(GlassesSessionCoordinator.canRunBackgroundCommand())
    }

    @Test fun `wrong transport disconnect cannot cancel or release a native capture`() = runTest {
        val receiver = receiver(DeviceClass.TUNEBUDS)
        val capture = async { receiver.capture(5_000) }
        runCurrent()
        GlassesPhotoCapture.invalidate(DeviceClass.EYEVUE)
        GlassesSessionCoordinator.clearForDisconnectedDevice(DeviceClass.HEY_CYAN)
        runCurrent()
        assertFalse(capture.isCancelled)
        assertNull(GlassesSessionCoordinator.tryAcquireBackgroundCommand())
        assertEquals(DeviceClass.TUNEBUDS, GlassesPhotoCapture.currentDevice())
        GlassesPhotoCapture.invalidate(DeviceClass.TUNEBUDS)
        runCurrent()
        assertNull(GlassesSessionCoordinator.tryAcquireBackgroundCommand())
        capture.cancelAndJoin()
        assertTrue(capture.isCancelled)
        assertTrue(GlassesSessionCoordinator.canRunBackgroundCommand())
    }

    @Test fun `late traffic extends quiet window but draining stays bounded`() = runTest {
        val receiver = receiver()
        val capture = async { runCatching { receiver.capture(100) } }
        runCurrent()
        advanceTimeBy(100); runCurrent()
        repeat(19) {
            advanceTimeBy(100)
            receiver.onPacket()
            runCurrent()
            assertFalse(capture.isCompleted)
        }
        assertTrue(capture.await().isFailure)
        assertEquals(2_100, testScheduler.currentTime.toInt())
        assertTrue(GlassesSessionCoordinator.canRunBackgroundCommand())
    }

    @Test fun `manual stream reserves once and receives image without another shutter`() = runTest {
        var shutters = 0
        var deliveries = 0
        var acquisition: Deferred<ByteArray>? = null
        val receiver = receiver(trigger = { shutters++ })
        receiver.onManualStreamStart(1_000) { acquisition = it; deliveries++ }
        assertNotNull(acquisition)
        assertNull(GlassesSessionCoordinator.tryAcquireLease(GlassesSession.LIVE_PREVIEW))
        receiver.onManualStreamStart(1_000) { deliveries++ }
        assertEquals(1, deliveries)
        receiver.onPhoto(image)
        advanceTimeBy(750); runCurrent()
        assertArrayEquals(image, acquisition!!.await())
        assertEquals(0, shutters)
        assertTrue(GlassesSessionCoordinator.canRunBackgroundCommand())
    }

    @Test fun `manual stream exposes timeout for Live diagnostics`() = runTest {
        var acquisition: Deferred<ByteArray>? = null
        val receiver = receiver()
        receiver.onManualStreamStart(100) { acquisition = it }
        val result = async { runCatching { acquisition!!.await() } }
        advanceTimeBy(850); runCurrent()
        assertTrue(result.await().exceptionOrNull() is GlassesPhotoTransferException)
        assertTrue(GlassesSessionCoordinator.canRunBackgroundCommand())
    }

    @Test fun `native error response fails promptly instead of waiting for timeout`() = runTest {
        val receiver = receiver()
        val capture = async { runCatching { receiver.capture(15_000) } }
        runCurrent()
        receiver.onFailure("camera rejected photo")
        assertEquals("camera rejected photo", capture.await().exceptionOrNull()?.message)
        assertEquals(750, testScheduler.currentTime.toInt())
    }

    @Test fun `shorter nested transport timeout is reported as transfer failure`() = runTest {
        val receiver = receiver(trigger = { withTimeout(100) { CompletableDeferred<Unit>().await() } })
        val result = runCatching { receiver.capture(15_000) }
        assertTrue(result.exceptionOrNull() is GlassesPhotoTransferException)
        assertEquals(850, testScheduler.currentTime.toInt())
        assertTrue(GlassesSessionCoordinator.canRunBackgroundCommand())
    }

    @Test fun `native registration filters family and preserves replacement identity`() = runTest {
        var oldCalls = 0
        var newCalls = 0
        val old: (ManualGlassesPhoto) -> Unit = { oldCalls++ }
        val replacement: (ManualGlassesPhoto) -> Unit = { newCalls++ }
        GeminiLiveImageButtonRouter.register(DeviceClass.EYEVUE, old)
        val oldReceiver = GeminiLiveImageButtonRouter.nativePhotoReceiver(DeviceClass.EYEVUE)!!
        assertNull(GeminiLiveImageButtonRouter.nativePhotoReceiver(DeviceClass.TUNEBUDS))
        assertFalse(GeminiLiveImageButtonRouter.handleImageButton())
        GeminiLiveImageButtonRouter.register(DeviceClass.EYEVUE, replacement)
        GeminiLiveImageButtonRouter.unregister(old)
        try {
            oldReceiver(CompletableDeferred(image))
            assertEquals(0, oldCalls)
            assertEquals(0, newCalls)
            GeminiLiveImageButtonRouter.nativePhotoReceiver(DeviceClass.EYEVUE)!!(CompletableDeferred(image))
            assertEquals(1, newCalls)
        } finally { GeminiLiveImageButtonRouter.unregister(replacement) }
    }
}
