package com.fersaiyan.cyanbridge.media.sync

import android.net.wifi.p2p.WifiP2pDevice
import android.util.Log
import com.fersaiyan.cyanbridge.shared.network.HeyCyanP2pPeerMatching
import com.oudmon.ble.base.bluetooth.DeviceManager
import java.util.Locale

/** Picks the glasses among discovered Wi-Fi Direct peers. */
internal object GlassesP2pPeerSelector {
    /** The score of a name that only contains a 12-digit hex identifier. */
    private const val WEAK_HEX_PEER_SCORE = 30

    fun currentBleMacNoColonUpper(): String? {
        return try {
            DeviceManager.getInstance().deviceAddress
                ?.replace(":", "")
                ?.uppercase(Locale.US)
                ?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    fun likelyGlassesPeerStrength(device: WifiP2pDevice, bleMacNoColon: String?): Int =
        HeyCyanP2pPeerMatching.likelyGlassesPeerNameStrength(device.deviceName, bleMacNoColon)

    /**
     * The Adaptive flow ([customFlow] false) needs the paired BLE identity or a known glasses
     * naming family. The Custom flow keeps v2.3.0's rule: any match, including a hex-only name,
     * unless several weak hex-only names tie.
     */
    fun selectBestLikelyGlassesPeer(peers: Collection<WifiP2pDevice>, customFlow: Boolean = false): WifiP2pDevice? {
        if (peers.isEmpty()) return null

        val bleMacNoColon = currentBleMacNoColonUpper()
        val minScore = if (customFlow) 0 else HeyCyanP2pPeerMatching.ADAPTIVE_MIN_PEER_SCORE
        val scored = peers
            .map { peer -> peer to likelyGlassesPeerStrength(peer, bleMacNoColon) }
            .filter { (_, score) -> score >= minScore }
        if (scored.isEmpty()) return null

        val bestScore = scored.maxOf { it.second }
        val bestPeers = scored.filter { it.second == bestScore }.map { it.first }

        // Do not guess among multiple weak hex-only matches; keep waiting for a stronger signal.
        if (customFlow && bestScore <= WEAK_HEX_PEER_SCORE && bestPeers.size > 1) {
            Log.i(
                "DataDownload",
                "Ambiguous weak glasses peer candidates; waiting for a stronger match: ${bestPeers.map { "${it.deviceName}/${it.deviceAddress}" }}"
            )
            return null
        }

        return bestPeers.firstOrNull { it.status == WifiP2pDevice.AVAILABLE }
            ?: bestPeers.firstOrNull()
    }

    fun selectOfficialLikelyGlassesPeer(peers: Collection<WifiP2pDevice>): WifiP2pDevice? {
        if (peers.isEmpty()) return null

        val pairedName = try {
            DeviceManager.getInstance().deviceName
        } catch (_: Exception) {
            null
        }
        val pairedAddress = try {
            DeviceManager.getInstance().deviceAddress
        } catch (_: Exception) {
            null
        }

        return HeyCyanP2pPeerMatching.selectPreferringAvailable(
            peers,
            isAvailable = { it.status == WifiP2pDevice.AVAILABLE },
        ) { peer -> HeyCyanP2pPeerMatching.matchesOfficialPeer(peer.deviceName, pairedName, pairedAddress) }
    }

    fun expectedOfficialP2pName(): String {
        return try {
            HeyCyanP2pPeerMatching.officialWifiDirectName(
                DeviceManager.getInstance().deviceName,
                DeviceManager.getInstance().deviceAddress,
            ).orEmpty()
        } catch (_: Exception) {
            ""
        }
    }
}
