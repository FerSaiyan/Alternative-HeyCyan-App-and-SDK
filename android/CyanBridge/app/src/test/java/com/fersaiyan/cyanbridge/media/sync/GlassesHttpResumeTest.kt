package com.fersaiyan.cyanbridge.media.sync

import com.fersaiyan.cyanbridge.media.AdaptiveHttpRoute
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassesHttpResumeTest {

    private fun chunk(bytes: ByteArray, partial: Boolean): RangedChunk =
        RangedChunk(ByteArrayInputStream(bytes), partial) {}

    // ---- VPN-safe route selection (fix part 1) ----

    @Test
    fun `vpn active forces the vpn-safe local route for a captured p2p route`() {
        assertEquals(
            AdaptiveHttpRoute.LOCAL_ADDRESS,
            vpnSafeCustomRoute(
                requested = AdaptiveHttpRoute.P2P_NETWORK,
                vpnActive = true,
                vpnSafeClientAvailable = true,
            ),
        )
    }

    @Test
    fun `vpn active forces the vpn-safe local route instead of system routing`() {
        assertEquals(
            AdaptiveHttpRoute.LOCAL_ADDRESS,
            vpnSafeCustomRoute(
                requested = AdaptiveHttpRoute.SYSTEM,
                vpnActive = true,
                vpnSafeClientAvailable = true,
            ),
        )
    }

    @Test
    fun `without a vpn the requested route is left untouched`() {
        assertEquals(
            AdaptiveHttpRoute.P2P_NETWORK,
            vpnSafeCustomRoute(
                requested = AdaptiveHttpRoute.P2P_NETWORK,
                vpnActive = false,
                vpnSafeClientAvailable = true,
            ),
        )
    }

    @Test
    fun `vpn active but no vpn-safe client keeps the requested route rather than guaranteeing failure`() {
        assertEquals(
            AdaptiveHttpRoute.SYSTEM,
            vpnSafeCustomRoute(
                requested = AdaptiveHttpRoute.SYSTEM,
                vpnActive = true,
                vpnSafeClientAvailable = false,
            ),
        )
    }

    @Test
    fun `custom transfers keep the v2_3_0 route order with the vpn-safe route first under a vpn`() {
        val p2p = AdaptiveHttpRoute.P2P_NETWORK
        val system = AdaptiveHttpRoute.SYSTEM
        val local = AdaptiveHttpRoute.LOCAL_ADDRESS
        assertEquals(listOf(p2p, system, local), customDownloadRoutes(p2p))
        assertEquals(listOf(local, p2p, system), customDownloadRoutes(local))
    }

    // ---- Range-resume on truncated transfers (fix part 2) ----

    @Test
    fun `truncated transfer resumes with range and reconstructs the full body`() {
        val payload = ByteArray(250) { (it and 0xff).toByte() }
        // The firmware hands out at most 40 bytes per connection before cutting off.
        val opener: (Long) -> RangedChunk? = { start ->
            val s = start.toInt()
            if (s >= payload.size) {
                null
            } else {
                val end = minOf(s + 40, payload.size)
                chunk(payload.copyOfRange(s, end), partial = s > 0)
            }
        }
        val stream = ResumingHttpStream(
            totalExpected = payload.size.toLong(),
            initial = chunk(payload.copyOfRange(0, 40), partial = false),
            openFrom = opener,
        )

        val got = stream.use { it.readBytes() }

        assertArrayEquals(payload, got)
        assertTrue(stream.completedFully())
        assertTrue("expected at least one resume", stream.resumeAttempts >= 1)
    }

    @Test
    fun `a server that ignores range and restarts from zero is de-duplicated`() {
        val payload = ByteArray(200) { (it and 0xff).toByte() }
        // On resume the server ignores Range and returns the whole body again (200, not 206).
        val opener: (Long) -> RangedChunk? = { chunk(payload.copyOf(), partial = false) }
        val stream = ResumingHttpStream(
            totalExpected = payload.size.toLong(),
            initial = chunk(payload.copyOfRange(0, 40), partial = false),
            openFrom = opener,
        )

        val got = stream.use { it.readBytes() }

        assertArrayEquals(payload, got)
        assertTrue(stream.completedFully())
    }

    @Test
    fun `a mid-transfer connection reset triggers a resume`() {
        val payload = ByteArray(80) { (it and 0xff).toByte() }
        val resetStream = object : InputStream() {
            override fun read(): Int = throw IOException("connection reset by peer")
            override fun read(b: ByteArray, off: Int, len: Int): Int =
                throw IOException("connection reset by peer")
        }
        val stream = ResumingHttpStream(
            totalExpected = payload.size.toLong(),
            initial = RangedChunk(resetStream, partial = false) {},
            openFrom = { start -> chunk(payload.copyOfRange(start.toInt(), payload.size), partial = start > 0) },
        )

        val got = stream.use { it.readBytes() }

        assertArrayEquals(payload, got)
        assertEquals(1, stream.resumeAttempts)
    }

    @Test
    fun `resume attempts are bounded so a hostile truncation cannot loop forever`() {
        // Every connection delivers exactly one byte then cuts off.
        val stream = ResumingHttpStream(
            totalExpected = 100L,
            initial = chunk(ByteArray(1) { 7 }, partial = true),
            maxResumeAttempts = 8,
            openFrom = { chunk(ByteArray(1) { 7 }, partial = true) },
        )

        val got = stream.use { it.readBytes() }

        // 1 initial byte + 8 resumed bytes, then it gives up.
        assertEquals(9, got.size)
        assertEquals(8, stream.resumeAttempts)
        assertFalse(stream.completedFully())
    }
}
