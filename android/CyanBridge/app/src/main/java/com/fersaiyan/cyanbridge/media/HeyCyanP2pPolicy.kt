package com.fersaiyan.cyanbridge.media

/**
 * Transfer-command rules of the HeyCyan media-sync handshake. Peer identity and network
 * verification moved to the shared `HeyCyanP2pPeerMatching` and `GlassesNetworkAddressing`.
 */
internal object HeyCyanP2pPolicy {
    fun transferCommandTimedOut(callbackReceived: Boolean, transferEvidenceReceived: Boolean): Boolean =
        !callbackReceived && !transferEvidenceReceived

    @Suppress("UNUSED_PARAMETER")
    fun resetCallbackAllowsRetry(callbackReceived: Boolean, parsedErrorCode: Int): Boolean =
        callbackReceived
}
