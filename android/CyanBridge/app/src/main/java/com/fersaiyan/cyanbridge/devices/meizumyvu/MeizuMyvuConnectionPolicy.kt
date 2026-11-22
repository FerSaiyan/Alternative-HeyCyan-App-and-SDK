package com.fersaiyan.cyanbridge.devices.meizumyvu

import com.myvu.client.service.ConnectionState

internal object MeizuMyvuConnectionPolicy {
    const val EXTERNAL_RETRY_GRACE_MS = 65_000L

    fun isFeatureReady(state: ConnectionState, relayReady: Boolean): Boolean =
        state == ConnectionState.READY && relayReady

    /** HeyCyan's command-channel characteristics (notify de5bf729, write de5bf72a). */
    private val HEYCYAN_CHARACTERISTICS = listOf(
        "de5bf729-d711-4e47-af26-65e3012a5dc7",
        "de5bf72a-d711-4e47-af26-65e3012a5dc7",
    )

    const val HEYCYAN_GLASSES_HINT =
        "These look like HeyCyan glasses: they have HeyCyan's Bluetooth services, not MYVU's. " +
            "In Device Bind, choose HeyCyan for these glasses."

    /**
     * A MYVU connection that failed because the device has no StarryNet channel but does have HeyCyan's
     * characteristics: HeyCyan glasses set to MYVU by hand (log_mwqmez5k, "M02S_CA12").
     */
    fun looksLikeHeyCyan(failureReason: String?): Boolean {
        val reason = failureReason?.lowercase() ?: return false
        return "no known starrynet channel pair" in reason && HEYCYAN_CHARACTERISTICS.any { it in reason }
    }

    /** [wrongGlasses]: the last failure showed these are not MYVU glasses; only the user may retry. */
    fun shouldRequestConnection(
        state: ConnectionState,
        lastFailureElapsedMs: Long,
        nowElapsedMs: Long,
        wrongGlasses: Boolean = false,
    ): Boolean = when (state) {
        ConnectionState.IDLE -> !wrongGlasses
        ConnectionState.FAILED ->
            !wrongGlasses && nowElapsedMs - lastFailureElapsedMs >= EXTERNAL_RETRY_GRACE_MS
        else -> false
    }
}
