package com.fersaiyan.cyanbridge.media.rtsp

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = android.app.Application::class)
class LibVlcLivePreviewLifecycleTest {
    @Test fun immediateStopBeforeCoroutineDispatchStillFinishesTheSession() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val finished = CompletableDeferred<Unit>()
        var closes = 0
        val connection = object : GlassesLiveConnection {
            override suspend fun open(onStatus: (String, String) -> Unit): String = awaitCancellation()
            override suspend fun close() { closes++ }
        }
        val manager = LibVlcLivePreviewManager(ApplicationProvider.getApplicationContext(), connection, "TuneBuds", "Test")
        try {
            manager.start { finished.complete(Unit) }
            manager.stop()
            finished.await()
            assertEquals(1, closes)
            assertFalse(manager.isActive)
        } finally { manager.release(); Dispatchers.resetMain() }
    }

    @Test fun stopDuringHandshakeRetainsSessionUntilTransportCleanupCompletes() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val cleanup = CompletableDeferred<Unit>()
        var closes = 0
        var finishes = 0
        val connection = object : GlassesLiveConnection {
            override suspend fun open(onStatus: (String, String) -> Unit): String {
                onStatus("Connecting", "Waiting for device URL")
                awaitCancellation()
            }
            override suspend fun close() { closes++; cleanup.await() }
        }
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = LibVlcLivePreviewManager(context, connection, "TuneBuds", "Test")
        try {
            manager.start { finishes++ }
            runCurrent()
            assertTrue(manager.uiState.value.canStop)
            manager.stop()
            runCurrent()
            assertEquals(1, closes)
            assertEquals(0, finishes)
            assertTrue(manager.isActive)
            manager.stop() // Repeated Stop must not notify/release during cleanup.
            assertEquals(0, finishes)
            cleanup.complete(Unit)
            manager.stopAndJoin()
            assertEquals(1, finishes)
            assertEquals("Idle", manager.uiState.value.stateLabel)
        } finally { manager.release(); Dispatchers.resetMain() }
    }

    @Test fun startupFailureClosesTransportAndKeepsErrorForTheDashboard() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var closes = 0
        var finishes = 0
        val finished = CompletableDeferred<Unit>()
        val connection = object : GlassesLiveConnection {
            override suspend fun open(onStatus: (String, String) -> Unit): String = throw IOException("Unsupported firmware")
            override suspend fun close() { closes++ }
        }
        val manager = LibVlcLivePreviewManager(ApplicationProvider.getApplicationContext(), connection, "TuneBuds", "Test")
        try {
            manager.start { finishes++; finished.complete(Unit) }
            runCurrent()
            // Native teardown is off the main thread, so await the job.
            finished.await()
            assertEquals(1, closes)
            assertEquals(1, finishes)
            assertEquals("Error", manager.uiState.value.stateLabel)
            assertEquals("Unsupported firmware", manager.uiState.value.detail)
        } finally { manager.release(); Dispatchers.resetMain() }
    }
}
