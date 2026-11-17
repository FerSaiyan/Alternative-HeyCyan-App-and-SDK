package com.fersaiyan.cyanbridge.media

import com.fersaiyan.cyanbridge.media.sync.CustomHttpResolver
import com.fersaiyan.cyanbridge.media.sync.CustomResolverInputs
import com.fersaiyan.cyanbridge.media.sync.HttpSubnetScanner
import com.fersaiyan.cyanbridge.shared.glasses.GlassesSyncFlow
import java.util.Collections
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The Adaptive flow's wrong-IP subnet scan: when it runs, what it probes, and that Custom/HeyCyan never scan. */
@RunWith(RobolectricTestRunner::class)
class AdaptiveSubnetScanTest {
    @Test
    fun `only the adaptive flow scans, after a round that reached no server, within budget`() {
        data class Case(
            val flow: GlassesSyncFlow = GlassesSyncFlow.ADAPTIVE,
            val p2p: Boolean = true,
            val reached: Boolean = false,
            val scans: Int = 0,
            val sinceLast: Long? = null,
            val remainingMs: Long = 60_000L,
            val expected: Boolean,
        )
        listOf(
            Case(expected = true),
            Case(flow = GlassesSyncFlow.CUSTOM, expected = false),
            Case(flow = GlassesSyncFlow.OFFICIAL_HEYCYAN, expected = false),
            Case(p2p = false, expected = false),
            Case(reached = true, expected = false),
            Case(scans = 1, sinceLast = 1_000L, expected = false),
            Case(scans = 1, sinceLast = 6_000L, expected = true),
            Case(scans = AdaptiveSubnetScan.MAX_SCANS_PER_ATTEMPT, sinceLast = 60_000L, expected = false),
            Case(remainingMs = AdaptiveSubnetScan.RESERVE_AFTER_SCAN_MS + 2_000L, expected = false),
        ).forEach { c ->
            assertEquals(
                c.toString(),
                c.expected,
                AdaptiveSubnetScan.shouldScan(c.flow, c.p2p, c.reached, c.scans, c.sinceLast, c.remainingMs),
            )
        }
        assertEquals(AdaptiveSubnetScan.MAX_SCAN_MS, AdaptiveSubnetScan.scanBudgetMs(80_000L))
    }

    @Test
    fun `scan covers the group subnet first and never probes the phone or already-tried addresses`() {
        // Phone owns the group (192.168.49.1); the glasses reported a stale address on another subnet.
        val hosts = AdaptiveSubnetScan.hosts(
            phoneInterfaceIp = "192.168.49.1",
            groupOwnerIp = "192.168.49.1",
            reportedIps = listOf("10.9.9.9"),
            phoneIsGroupOwner = true,
            tried = setOf("10.9.9.9"),
        )
        assertEquals("192.168.49.2", hosts.first())
        assertFalse("192.168.49.1" in hosts)
        assertFalse("10.9.9.9" in hosts)
        assertEquals(253 + 253, hosts.size)

        // Phone is a client (.57): its own address is skipped, the glasses' .1 is scanned.
        val clientHosts = AdaptiveSubnetScan.hosts("192.168.49.57", "192.168.49.1", listOf("192.168.49.79"), false, emptySet())
        assertTrue("192.168.49.1" in clientHosts)
        assertFalse("192.168.49.57" in clientHosts)
        assertEquals(253, clientHosts.size)

        // The scanner finds the server and never touches excluded hosts.
        val probed = Collections.synchronizedSet(HashSet<String>())
        val scanner = HttpSubnetScanner(
            portOpen = { ip, _ -> probed += ip; ip == "192.168.49.77" },
            mediaConfigOk = { ip, _ -> ip == "192.168.49.77" },
        )
        val found = runBlocking { scanner.scan(clientHosts, AdaptiveSubnetScan.limits(5_000L)) }
        assertEquals("192.168.49.77", found)
        assertFalse("192.168.49.57" in probed)
    }

    @Test
    fun `a learned address is tried first on the group subnet and never when it is the phone`() {
        assertEquals(
            listOf("192.168.49.77", "192.168.49.79"),
            AdaptiveSubnetScan.endpointIps("192.168.49.77", listOf("192.168.49.79"), "192.168.49.1", true),
        )
        // Learned on another group subnet: skipped this time.
        assertEquals(
            listOf("192.168.49.79"),
            AdaptiveSubnetScan.endpointIps("10.0.0.7", listOf("192.168.49.79"), "192.168.49.1", true),
        )
        // Never the phone's own group-owner address.
        assertEquals(
            listOf("192.168.49.79"),
            AdaptiveSubnetScan.endpointIps("192.168.49.1", listOf("192.168.49.79"), "192.168.49.1", true),
        )
    }

    @Test
    fun `custom resolver with a wrong reported IP times out without scanning`() = runTest {
        val probed = Collections.synchronizedList(ArrayList<String>())
        val resolver = CustomHttpResolver(
            portOpen = { ip, _ -> probed += ip; false },
            mediaConfigReachable = { _, _ -> false },
            clockMs = { testScheduler.currentTime },
        )
        val result = resolver.resolve(
            inputs = { CustomResolverInputs("192.168.49.250", null, "192.168.49.1", true, true) },
            interfaceIpv4 = { "192.168.49.1" },
            stillWanted = { true },
        )
        assertEquals(CustomHttpResolver.Result.TimedOut, result)
        assertEquals(setOf("192.168.49.250", "192.168.49.79", "192.168.49.2", "192.168.49.3"), probed.toSet())
    }
}
