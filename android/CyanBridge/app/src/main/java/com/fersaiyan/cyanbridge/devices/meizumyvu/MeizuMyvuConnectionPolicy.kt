package com.fersaiyan.cyanbridge.devices.meizumyvu

import com.myvu.client.service.ConnectionState

internal object MeizuMyvuConnectionPolicy {
    const val EXTERNAL_RETRY_GRACE_MS = 65_000L

    fun isFeatureReady(state: ConnectionState, relayReady: Boolean): Boolean =
        state == ConnectionState.READY && relayReady

    fun shouldRequestConnection(
        state: ConnectionState,
        lastFailureElapsedMs: Long,
        nowElapsedMs: Long,
    ): Boolean = when (state) {
        ConnectionState.IDLE -> true
        ConnectionState.FAILED ->
            nowElapsedMs - lastFailureElapsedMs >= EXTERNAL_RETRY_GRACE_MS
        else -> false
    }
}
