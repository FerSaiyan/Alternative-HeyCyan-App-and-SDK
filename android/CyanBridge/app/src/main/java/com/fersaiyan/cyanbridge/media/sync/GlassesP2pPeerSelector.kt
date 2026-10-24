package com.fersaiyan.cyanbridge.media.sync

import android.net.wifi.p2p.WifiP2pDevice
import com.fersaiyan.cyanbridge.shared.network.HeyCyanP2pPeerMatching
import com.oudmon.ble.base.bluetooth.DeviceManager
import java.util.Locale

/** Picks the glasses among discovered Wi-Fi Direct peers. */
internal object GlassesP2pPeerSelector {
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

    fun selectBestLikelyGlassesPeer(peers: Collection<WifiP2pDevice>): WifiP2pDevice? {
        if (peers.isEmpty()) return null

        val bleMacNoColon = currentBleMacNoColonUpper()
        val scored = peers
            .map { peer -> peer to likelyGlassesPeerStrength(peer, bleMacNoColon) }
            // Never connect the adaptive flow to a merely hex-looking peer. Require either
            // the paired BLE identity or a known glasses naming family.
            .filter { (_, score) -> score >= HeyCyanP2pPeerMatching.ADAPTIVE_MIN_PEER_SCORE }
        if (scored.isEmpty()) return null

        val bestScore = scored.maxOf { it.second }
        val bestPeers = scored.filter { it.second == bestScore }.map { it.first }

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
