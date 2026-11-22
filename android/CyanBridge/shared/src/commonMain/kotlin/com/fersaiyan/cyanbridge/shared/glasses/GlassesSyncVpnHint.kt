package com.fersaiyan.cyanbridge.shared.glasses

/**
 * Whether a VPN carries this app's plain sockets ([coversApp]: the app's default network is a VPN). Android's public
 * API does not say whether the VPN allows bypassing.
 */
data class SyncVpnState(val coversApp: Boolean)

/**
 * Plain explanation for a sync that reached the glasses over Wi-Fi Direct and then failed while a VPN covers the
 * app. A full-tunnel VPN also carries traffic for the glasses' private address: a proxy VPN accepts the request and
 * closes it ("unexpected end of stream") or answers 404/500 itself, and a VPN that does not allow bypassing defeats
 * every flow's routing (field logs mwra0io2 / mwr92qph; firmware_dump add.238 rig reproduction).
 */
object GlassesSyncVpnHint {
    /** Null unless a VPN covers the app and the Wi-Fi Direct link to the glasses had come up ([glassesReached]). */
    fun message(vpn: SyncVpnState, glassesReached: Boolean): String? {
        if (!vpn.coversApp || !glassesReached) return null
        return "Your VPN is most likely blocking the connection to the glasses. Pause the VPN while syncing, or " +
            "exclude CyanBridge (or allow local network access) in the VPN app, then sync again."
    }
}
