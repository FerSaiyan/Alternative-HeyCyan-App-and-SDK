package com.fersaiyan.cyanbridge.shared.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HeyCyanP2pPeerMatchingTest {
    @Test
    fun buildsTheOfficialWifiDirectName() {
        assertEquals("M01_C4E3BFC3A402", HeyCyanP2pPeerMatching.officialWifiDirectName("M01", "C4:E3:BF:C3:A4:02"))
        assertEquals(
            "M01_C4E3BFC3A402",
            HeyCyanP2pPeerMatching.officialWifiDirectName("Hey_Cyan_M01", "C4:E3:BF:C3:A4:02"),
        )
        assertNull(HeyCyanP2pPeerMatching.officialWifiDirectName("M01", null))
    }

    @Test
    fun matchesOfficialExactNameAndFullMacSuffix() {
        assertTrue(HeyCyanP2pPeerMatching.matchesOfficialPeer("M01_C4E3BFC3A402", "M01", "C4:E3:BF:C3:A4:02"))
        assertTrue(HeyCyanP2pPeerMatching.matchesOfficialPeer("M01_C4E3BFC3A402", "A03", "C4:E3:BF:C3:A4:02"))
        assertTrue(HeyCyanP2pPeerMatching.matchesOfficialPeer("AIMB-G3_C4E3BFC3A402", "AIMB-G3_A402", "C4:E3:BF:C3:A4:02"))
        assertTrue(HeyCyanP2pPeerMatching.matchesOfficialPeer("M02S_c4e3bfc3a402", "M02S_A402", "C4:E3:BF:C3:A4:02"))
        assertFalse(HeyCyanP2pPeerMatching.matchesOfficialPeer("[TV] Samsung 4 Series", "M01", "C4:E3:BF:C3:A4:02"))
        // A shared short suffix or model family is not the official identity.
        assertFalse(
            HeyCyanP2pPeerMatching.matchesOfficialPeer("AIMB-G3_11223344A402", "AIMB-G3_A402", "C4:E3:BF:C3:A4:02"),
        )
        assertFalse(HeyCyanP2pPeerMatching.matchesOfficialPeer(null, null, null))
    }

    @Test
    fun pairedPeerRuleCombinesTheFirmwareAndPreviewMatchers() {
        val mac = "C4:E3:BF:C3:A4:02"
        assertTrue(HeyCyanP2pPeerMatching.matchesPairedGlassesPeer("M01_C4E3BFC3A402", "aa:bb", "M01", mac))
        assertTrue(HeyCyanP2pPeerMatching.matchesPairedGlassesPeer("m01", "aa:bb", "M01", mac))
        assertTrue(HeyCyanP2pPeerMatching.matchesPairedGlassesPeer("x", "c4:e3:bf:c3:a4:02", "M01", mac))
        assertTrue(HeyCyanP2pPeerMatching.matchesPairedGlassesPeer("AIMB-G3_A402", "aa:bb", null, mac))
        assertFalse(HeyCyanP2pPeerMatching.matchesPairedGlassesPeer("AIMB-G3_A403", "aa:bb", "M01", mac))
        assertFalse(HeyCyanP2pPeerMatching.matchesPairedGlassesPeer("DIRECT-TV", "aa:bb", null, null))
    }

    @Test
    fun selectionPrefersAnAvailableMatch() {
        data class Peer(val name: String, val available: Boolean)
        val peers = listOf(Peer("tv", true), Peer("glasses-busy", false), Peer("glasses", true))
        val selected = HeyCyanP2pPeerMatching.selectPreferringAvailable(peers, Peer::available) {
            it.name.startsWith("glasses")
        }
        assertEquals("glasses", selected?.name)
        assertEquals(
            "glasses-busy",
            HeyCyanP2pPeerMatching.selectPreferringAvailable(peers.take(2), Peer::available) {
                it.name.startsWith("glasses")
            }?.name,
        )
    }

    @Test
    fun scoresLikelyGlassesPeerNames() {
        val score = HeyCyanP2pPeerMatching::likelyGlassesPeerNameStrength
        assertEquals(-1, score(null, "C4E3BFC3A402"))
        assertEquals(-1, score("", null))
        assertEquals(100, score("m01_c4e3bfc3a402", "C4E3BFC3A402"))
        assertEquals(80, score("HeyCyan", null))
        assertEquals(80, score("o_glasses", null))
        assertEquals(80, score("Q_1234", null))
        assertEquals(70, score("AIMB-12", null))
        assertEquals(70, score("My Smart Glass", null))
        assertEquals(70, score("Ray SG2", null))
        assertEquals(30, score("DIRECT-AB C4E3BFC3A402", null))
        assertEquals(-1, score("[TV] Living room", null))
    }
}
