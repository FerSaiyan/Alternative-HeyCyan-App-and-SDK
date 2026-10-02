package com.fersaiyan.cyanbridge.ota

/** A disconnected startup broadcast does not cancel an accepted connect request. */
internal class HeyCyanLiveP2pConnectionState {
    private var connectRequested = false
    private var groupFormed = false

    fun beginConnect(): Boolean {
        if (connectRequested || groupFormed) return false
        connectRequested = true
        return true
    }

    fun onConnectionInfo(formed: Boolean) {
        groupFormed = formed
    }

    fun onDisconnected(): Boolean {
        val lostGroup = groupFormed
        groupFormed = false
        if (lostGroup) connectRequested = false
        return lostGroup
    }

    fun reset() {
        connectRequested = false
        groupFormed = false
    }
}
