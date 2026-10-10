package com.fersaiyan.cyanbridge.ai.live

import com.fersaiyan.cyanbridge.glasses.HeyCyanPhotoReadyTimeoutException
import com.fersaiyan.cyanbridge.glasses.HeyCyanThumbnailTransferException
import com.fersaiyan.cyanbridge.glasses.GlassesPhotoTransferException
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executors

class GeminiLiveVisionDiagnosticsTest {
    @Test fun `capture failures and rejected sends never count as queued images`() {
        val stats = GeminiLiveVisionDiagnostics()
        stats.captureStarted()
        stats.captureFinished(false)
        assertFalse(stats.send(false, byteArrayOf(1), 10) { error("must not enqueue") })
        assertFalse(stats.send(true, byteArrayOf(), 10) { error("must not enqueue") })
        assertFalse(stats.send(true, byteArrayOf(1), 10) { false })
        assertTrue(stats.send(true, byteArrayOf(1), 10) { true })
        assertEquals(1, stats.snapshot().captureFailures)
        assertEquals(1, stats.snapshot().imagesQueued)
        assertEquals(3, stats.snapshot().sendFailures)
    }

    @Test fun `concurrent callers enforce exact limit without lost increments`() {
        val stats = GeminiLiveVisionDiagnostics()
        val pool = Executors.newFixedThreadPool(4)
        try {
            val jobs = (1..40).map { pool.submit<Boolean> { stats.send(true, byteArrayOf(1), 10) { true } } }
            assertEquals(10, jobs.count { it.get() })
            assertEquals(10, stats.snapshot().imagesQueued)
            assertEquals(30, stats.snapshot().sendFailures)
        } finally { pool.shutdownNow() }
    }

    @Test fun `photo readiness and transfer errors are separate subsets of failed attempts`() {
        val stats = GeminiLiveVisionDiagnostics()
        stats.captureStarted()
        stats.captureFinished(false, HeyCyanPhotoReadyTimeoutException())
        stats.captureStarted()
        stats.captureFinished(false, HeyCyanThumbnailTransferException("invalid image"))
        stats.captureStarted()
        stats.captureFinished(false, IllegalStateException("busy"))
        stats.captureStarted()
        stats.captureFinished(false, GlassesPhotoTransferException("native photo timed out"))
        assertEquals(4, stats.snapshot().captureFailures)
        assertEquals(1, stats.snapshot().photoReadyTimeouts)
        assertEquals(2, stats.snapshot().transferFailures)
        assertEquals(0, stats.snapshot().imagesQueued)
    }
}
