package com.fersaiyan.cyanbridge.glasses

import com.fersaiyan.cyanbridge.ai.live.GeminiLiveImageButtonRouter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HeyCyanCaptureOwnerTest {
    @Before @After fun clear() { GlassesSessionCoordinator.clearForDisconnectedDevice() }

    @Test fun `automatic capture waits for photo and blocks main manual and exclusive consumers`() = runTest {
        var ready: ((Long) -> Unit)? = null
        var reads = 0
        var removed = false
        val transfer = CompletableDeferred<ByteArray>()
        val owner = HeyCyanCaptureOwner({ true }, {}, {}, { ready = it }, { removed = true }, {
            reads++; transfer.await()
        })
        val capture = async { owner.capture(3, 0, false) }
        runCurrent()
        assertTrue(owner.isActive())
        assertEquals(0, reads)
        assertNull(GlassesSessionCoordinator.tryAcquireBackgroundCommand())
        assertNull(GlassesSessionCoordinator.tryAcquireLease(GlassesSession.MEDIA_SYNC))
        assertTrue(runCatching { owner.receiveHardwarePhoto() }.isFailure)
        var manualEvents = 0
        val handler = { manualEvents++; Unit }
        GeminiLiveImageButtonRouter.register(handler)
        try {
            assertTrue(GeminiLiveImageButtonRouter.handleImageButton())
            assertEquals(0, manualEvents)
            ready!!(123)
            runCurrent()
            assertEquals(1, reads)
            assertNull(GlassesSessionCoordinator.tryAcquireBackgroundCommand())
            transfer.complete(byteArrayOf(7))
            assertEquals(123L, capture.await().photoReadyAtMs)
            assertTrue(removed)
            assertFalse(owner.isActive())
            assertTrue(GlassesSessionCoordinator.canRunBackgroundCommand())
            assertTrue(GeminiLiveImageButtonRouter.handleImageButton())
            assertEquals(1, manualEvents)
        } finally { GeminiLiveImageButtonRouter.unregister(handler) }
    }

    @Test fun `missing fresh notification never reads stale thumbnail and releases permit`() = runTest {
        var reads = 0
        var removed = false
        val owner = HeyCyanCaptureOwner({ true }, {}, {}, {}, { removed = true }, { reads++; byteArrayOf(1) }, 100)
        assertTrue(runCatching { owner.capture(3, 0, false) }.isFailure)
        assertEquals(0, reads)
        assertTrue(removed)
        assertTrue(GlassesSessionCoordinator.canRunBackgroundCommand())
    }

    @Test fun `failed transfer releases permit and next capture succeeds`() = runTest {
        var fail = true
        val owner = HeyCyanCaptureOwner({ true }, {}, {}, { it(456) }, {}, {
            if (fail) error("transfer failed") else byteArrayOf(9)
        })
        assertTrue(runCatching { owner.capture(3, 0, false) }.isFailure)
        assertTrue(GlassesSessionCoordinator.canRunBackgroundCommand())
        fail = false
        assertArrayEquals(byteArrayOf(9), owner.capture(3, 0, false).jpeg)
    }

    @Test fun `cancelled capture releases notification owner and command permit`() = runTest {
        var removed = false
        val owner = HeyCyanCaptureOwner({ true }, {}, {}, {}, { removed = true }, { byteArrayOf(1) })
        val job = async { owner.capture(3, 0, false) }
        runCurrent()
        job.cancelAndJoin()
        assertTrue(removed)
        assertFalse(owner.isActive())
        assertTrue(GlassesSessionCoordinator.canRunBackgroundCommand())
    }

    @Test fun `disconnect invalidates capture before thumbnail request`() = runTest {
        var ready: ((Long) -> Unit)? = null
        var reads = 0
        val owner = HeyCyanCaptureOwner({ true }, {}, {}, { ready = it }, {}, { reads++; byteArrayOf(1) })
        val job = async { runCatching { owner.capture(3, 0, false) } }
        runCurrent()
        GlassesSessionCoordinator.clearForDisconnectedDevice()
        ready!!(123)
        assertTrue(job.await().isFailure)
        assertEquals(0, reads)
        assertFalse(owner.isActive())
    }

    @Test fun `unregistering old Live handler preserves replacement`() {
        var oldCalls = 0
        var newCalls = 0
        val old = { oldCalls++; Unit }
        val replacement = { newCalls++; Unit }
        GeminiLiveImageButtonRouter.register(old)
        GeminiLiveImageButtonRouter.register(replacement)
        GeminiLiveImageButtonRouter.unregister(old)
        try {
            assertTrue(GeminiLiveImageButtonRouter.handleImageButton())
            assertEquals(0, oldCalls)
            assertEquals(1, newCalls)
        } finally { GeminiLiveImageButtonRouter.unregister(replacement) }
        assertFalse(GeminiLiveImageButtonRouter.isRegistered())
    }
}
