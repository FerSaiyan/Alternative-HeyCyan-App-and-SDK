package com.fersaiyan.cyanbridge.glasses

import android.graphics.Bitmap
import com.oudmon.ble.base.communication.ILargeDataResponse
import com.oudmon.ble.base.communication.LargeDataHandler
import com.oudmon.ble.base.communication.bigData.resp.PictureThumbnailsResponse
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HeyCyanThumbnailReceiverTest {
    private class Wire : HeyCyanThumbnailReceiver.Transport {
        lateinit var callback: (ByteArray) -> Unit
        val requests = mutableListOf<Int>()
        var detached = false
        var onRequest: (Int) -> Unit = {}
        override fun attach(callback: (ByteArray) -> Unit) { this.callback = callback }
        override fun request(index: Int) { requests += index; onRequest(index) }
        override fun detach() { detached = true }
        fun packet(total: Int, index: Int, vararg bytes: Int) {
            val frame = ByteArray(11 + bytes.size)
            frame[7] = total.toByte(); frame[8] = (total shr 8).toByte()
            frame[9] = index.toByte(); frame[10] = (index shr 8).toByte()
            bytes.forEachIndexed { i, b -> frame[11 + i] = b.toByte() }
            callback(frame)
        }
    }

    @Test fun `ordered transfer ignores duplicate and freezes after completion`() = runTest {
        val wire = Wire()
        wire.onRequest = { index ->
            if (index == 0) wire.packet(2, 0, 1, 2)
            else { wire.packet(2, 0, 1, 2); wire.packet(2, 1, 3); wire.packet(2, 1, 99) }
        }
        val receiver = HeyCyanThumbnailReceiver(wire, { true }, 100, 10, 30, { testScheduler.currentTime })
        assertArrayEquals(byteArrayOf(1, 2, 3), receiver.receive(0))
        assertEquals(listOf(0, 1), wire.requests)
        assertTrue(wire.detached)
        wire.packet(2, 1, 88)
        assertEquals(listOf(0, 1), wire.requests)
    }

    @Test fun `zero count retries once and does not duplicate bytes`() = runTest {
        val wire = Wire()
        wire.onRequest = {
            if (wire.requests.size == 1) wire.packet(0, 0)
            else wire.packet(1, 0, 9)
        }
        val receiver = HeyCyanThumbnailReceiver(wire, { true }, 100, 10, 30, { testScheduler.currentTime })
        assertArrayEquals(byteArrayOf(9), receiver.receive(0))
        assertEquals(listOf(0, 0), wire.requests)
        assertTrue(wire.detached)
    }

    @Test fun `partial timeout discards old tail during retry before fresh packet zero`() = runTest {
        val wire = Wire()
        var starts = 0
        wire.onRequest = { index ->
            if (index == 0 && ++starts == 1) wire.packet(2, 0, 1)
            else if (index == 0) { wire.packet(2, 1, 2); wire.packet(1, 0, 7) }
        }
        val receiver = HeyCyanThumbnailReceiver(wire, { true }, 100, 10, 30, { testScheduler.currentTime })
        assertArrayEquals(byteArrayOf(7), receiver.receive(0))
        assertEquals(listOf(0, 1, 0), wire.requests)
    }

    @Test fun `gap and changed count fail boundedly and detach`() = runTest {
        for (changedCount in listOf(false, true)) {
            val wire = Wire()
            wire.onRequest = { index ->
                if (index == 0) wire.packet(3, 0, 1)
                else wire.packet(if (changedCount) 4 else 3, if (changedCount) 1 else 2, 2)
            }
            val receiver = HeyCyanThumbnailReceiver(wire, { true }, 100, 10, 30, { testScheduler.currentTime })
            assertTrue(runCatching { receiver.receive(0) }.isFailure)
            assertEquals(listOf(0, 1, 0, 1), wire.requests)
            assertTrue(wire.detached)
        }
    }

    @Test fun `invalid decode retries and never returns invalid bytes`() = runTest {
        val wire = Wire()
        wire.onRequest = { wire.packet(1, 0, 4) }
        val receiver = HeyCyanThumbnailReceiver(wire, { false }, 100, 10, 30, { testScheduler.currentTime })
        assertTrue(runCatching { receiver.receive(0) }.isFailure)
        assertEquals(listOf(0, 0), wire.requests)
        assertTrue(wire.detached)
    }

    @Test fun `cancellation detaches and late callback cannot chain requests`() = runTest {
        val wire = Wire()
        val receiver = HeyCyanThumbnailReceiver(wire, { true }, 100, 10, 30, { testScheduler.currentTime })
        val job = async { receiver.receive(0) }
        runCurrent()
        job.cancelAndJoin()
        assertTrue(wire.detached)
        wire.packet(3, 0, 1)
        assertEquals(listOf(0), wire.requests)
    }

    @Test fun `request exception still detaches owned handler`() = runTest {
        val wire = Wire()
        wire.onRequest = { error("write failed") }
        val receiver = HeyCyanThumbnailReceiver(wire, { true }, 100, 10, 30, { testScheduler.currentTime })
        assertTrue(runCatching { receiver.receive(0) }.isFailure)
        assertTrue(wire.detached)
    }

    @Test fun `real JPEG decodes but missing end marker cannot pass`() = runTest {
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        val jpeg = ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
            out.toByteArray()
        }
        bitmap.recycle()
        for (truncated in listOf(false, true)) {
            val bytes = if (truncated) jpeg.dropLast(2).toByteArray() else jpeg
            val wire = Wire()
            wire.onRequest = { wire.packet(1, 0, *bytes.map { it.toInt() }.toIntArray()) }
            val receiver = HeyCyanThumbnailReceiver(transport = wire, timeoutMs = 100,
                quietMs = 10, drainLimitMs = 30, clock = { testScheduler.currentTime })
            val result = runCatching { receiver.receive(0) }
            assertEquals(!truncated, result.isSuccess)
        }
    }

    @Test fun `invalidated permit prevents late callback from requesting another packet`() = runTest {
        val wire = Wire()
        var valid = true
        val receiver = HeyCyanThumbnailReceiver(wire, { true }, 100, 10, 30,
            { testScheduler.currentTime }, { valid })
        val job = async { runCatching { receiver.receive(0) } }
        runCurrent()
        valid = false
        wire.packet(3, 0, 1)
        assertTrue(job.await().isFailure)
        assertEquals(listOf(0), wire.requests)
        assertTrue(wire.detached)
    }

    @Test fun `actual AAR adapter exposes raw count zero and removes only its owned slot`() {
        val sdk = LargeDataHandler.getInstance()
        val before = sdk.respMap.remove(-3)
        val transport = HeyCyanThumbnailReceiver.SdkTransport()
        try {
            var frames = 0
            transport.attach { frames++ }
            @Suppress("UNCHECKED_CAST")
            val owned = sdk.respMap[-3] as ILargeDataResponse<PictureThumbnailsResponse>
            val zeroCount = PictureThumbnailsResponse().apply { acceptData(ByteArray(11)) }
            owned.parseData(-3, zeroCount)
            assertEquals(1, frames) // Vendor convenience helper suppresses this reply.
            val replacement = ILargeDataResponse<PictureThumbnailsResponse> { _, _ -> }
            sdk.respMap[-3] = replacement
            transport.detach()
            assertSame(replacement, sdk.respMap[-3])
        } finally {
            sdk.respMap.remove(-3)
            if (before != null) sdk.respMap[-3] = before
        }
    }
}
