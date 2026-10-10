package com.fersaiyan.cyanbridge.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveP2pSyncTest {
    @Test
    fun `new profile tries verified p2p routes before system routing`() {
        assertEquals(
            listOf(
                AdaptiveHttpRoute.P2P_NETWORK,
                AdaptiveHttpRoute.LOCAL_ADDRESS,
                AdaptiveHttpRoute.SYSTEM,
            ),
            AdaptiveP2pProfile().routeOrder(),
        )
    }

    @Test
    fun `successful candidate is preferred immediately and promoted after repetition`() {
        val once = AdaptiveP2pProfile().withSuccessfulSync(AdaptiveHttpRoute.LOCAL_ADDRESS, 2_000L)
        assertEquals(AdaptiveHttpRoute.LOCAL_ADDRESS, once.routeOrder().first())
        assertNull(once.preferredRoute)

        val twice = once.withSuccessfulSync(AdaptiveHttpRoute.LOCAL_ADDRESS, 2_000L)
        assertEquals(AdaptiveHttpRoute.LOCAL_ADDRESS, twice.preferredRoute)
        assertEquals(2_000L, twice.preferredWarmupMs)
        assertEquals(2, twice.completedSyncs)
    }

    @Test
    fun `failed sessions decay an unconfirmed candidate`() {
        val candidate = AdaptiveP2pProfile()
            .withSuccessfulSync(AdaptiveHttpRoute.SYSTEM, 4_000L)
            .withFailedSync()
        assertEquals(AdaptiveHttpRoute.SYSTEM, candidate.candidateRoute)

        val decayed = candidate.withFailedSync()
        assertNull(decayed.candidateRoute)
        assertEquals(0, decayed.candidateSuccesses)
    }

    @Test
    fun `profile key is stable and does not expose the glasses address`() {
        val first = AdaptiveP2pProfileStore.profileKey(
            "Samsung", "SM-S928U", 36, "M01", "AA:BB:CC:DD:EE:FF", "V9", "1.2.3",
        )
        val second = AdaptiveP2pProfileStore.profileKey(
            "samsung", "sm-s928u", 36, "m01", "aa:bb:cc:dd:ee:ff", "v9", "1.2.3",
        )

        assertEquals(first, second)
        assertEquals(24, first.length)
        assertFalse(first.contains("aabbcc", ignoreCase = true))
    }

    @Test
    fun `session keeps bounded ordered checkpoint trace`() {
        var now = 100L
        val session = AdaptiveP2pSyncSession("profile", AdaptiveP2pProfile()) { now }
        session.noteTransferCommand(1)
        now = 250L
        session.mark(AdaptiveSyncCheckpoint.TRANSFER_EVIDENCE, "BLE IP")
        session.noteHttpSuccess(AdaptiveHttpRoute.P2P_NETWORK, 1_000L)

        val trace = session.traceSummary()
        assertTrue(trace.contains("+0ms TRANSFER_COMMAND_SENT attempt=1,send_count=1"))
        assertTrue(trace.contains("+150ms TRANSFER_EVIDENCE BLE IP"))
        assertTrue(trace.contains("MEDIA_CONFIG_COMPLETE route=P2P_NETWORK"))
    }

    @Test
    fun `session atomically enforces transfer command budget`() {
        val session = AdaptiveP2pSyncSession("profile", AdaptiveP2pProfile()) { 0L }

        assertTrue(session.noteTransferCommand(attempt = 1, maxSends = 3))
        assertTrue(session.noteTransferCommand(attempt = 2, maxSends = 3))
        assertTrue(session.noteTransferCommand(attempt = 3, maxSends = 3))
        assertFalse(session.noteTransferCommand(attempt = 3, maxSends = 3))
        assertEquals(3, session.transferCommandSends)
    }

    @Test
    fun `checkpoint ring retains only the newest events`() {
        val session = AdaptiveP2pSyncSession("profile", AdaptiveP2pProfile()) { 0L }
        repeat(80) { index ->
            session.mark(AdaptiveSyncCheckpoint.DEVICE_ERROR, "event=$index")
        }

        val lines = session.traceSummary().lines()
        assertEquals(64, lines.size)
        assertFalse(lines.any { it.endsWith("event=0") })
        assertTrue(lines.last().endsWith("event=79"))
    }
}
