package com.fersaiyan.cyanbridge.media.sync

import android.util.Log
import com.fersaiyan.cyanbridge.shared.network.GlassesNetworkAddressing
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** What the Custom resolver knows about the glasses link at one moment. */
internal data class CustomResolverInputs(
    val bleIp: String?,
    val bridgeIp: String?,
    val groupOwnerIp: String?,
    val phoneIsGroupOwner: Boolean?,
    val p2pConnected: Boolean,
)

/**
 * The v2.3.0 "Custom flow" HTTP resolver (MainActivity.maybeStartCustomHttpDownload in tag
 * v2.3.0), restored for [com.fersaiyan.cyanbridge.shared.glasses.GlassesSyncFlow.CUSTOM] on the
 * shared glasses network session: the probes go through [GlassesP2pNetwork], so they keep the
 * process network binding and fall back to VPN-safe P2P-local sockets.
 *
 * Every 1.5 s for up to 45 s it probes `media.config` on the candidates (the BLE-reported IP
 * with 3 tries 2 s apart, then the bridge IP, the group-owner IP and .1/.79/.2/.3 of the
 * subnet), never the phone's own group-owner address. When no glasses IP is known it scans the
 * /24 once (see [shouldScanSubnet]). It never learns or stores anything.
 */
internal class CustomHttpResolver(
    private val portOpen: (ip: String, timeoutMs: Int) -> Boolean,
    private val mediaConfigReachable: (ip: String, timeoutMs: Int) -> Boolean,
    private val clockMs: () -> Long = System::currentTimeMillis,
) {
    sealed interface Result {
        data class Resolved(val ip: String, val viaScan: Boolean) : Result
        data object TimedOut : Result
    }

    /**
     * Resolves the glasses HTTP server. [inputs] is read again on every round, because BLE and
     * P2P callbacks keep updating it. Returns null when [stillWanted] turns false.
     */
    suspend fun resolve(
        inputs: () -> CustomResolverInputs,
        interfaceIpv4: () -> String?,
        stillWanted: () -> Boolean,
    ): Result? {
        val startMs = clockMs()
        var lastStatusLogMs = 0L
        var didSubnetScan = false

        while (coroutineContext.isActive && clockMs() - startMs < OVERALL_TIMEOUT_MS) {
            if (!stillWanted()) return null
            val now = clockMs()
            val current = inputs()
            if (now - lastStatusLogMs > STATUS_LOG_INTERVAL_MS) {
                lastStatusLogMs = now
                Log.i(
                    TAG,
                    "Resolving glasses HTTP IP... p2p=${current.p2pConnected}, bleIp=${current.bleIp}, groupOwnerIp=${current.groupOwnerIp}",
                )
            }

            // 1) Try known candidates first.
            val prefix = subnetPrefix(current, interfaceIpv4)
            for (candidate in probeTargets(current, prefix)) {
                if (!coroutineContext.isActive || !stillWanted()) return null
                // The glasses' HTTP server may need a few seconds after P2P connects, so the
                // BLE-reported IP gets retries; the guesses get one quick probe.
                val ok = if (candidate == current.bleIp) {
                    mediaConfigOkWithRetry(candidate)
                } else {
                    mediaConfigOk(candidate, PROBE_TIMEOUT_MS, logFailures = false)
                }
                if (ok) {
                    Log.i(TAG, "Resolved glasses HTTP IP via candidate list: $candidate")
                    return Result.Resolved(candidate, viaScan = false)
                }
            }

            // 2) With no device IP at all, scan the /24 from the best available hint.
            val latest = inputs()
            if (shouldScanSubnet(didSubnetScan, latest)) {
                val scanPrefix = subnetPrefix(latest, interfaceIpv4)
                if (!scanPrefix.isNullOrBlank()) {
                    didSubnetScan = true
                    Log.i(TAG, "Candidate IPs failed; scanning ${scanPrefix}0/24 for HTTP server...")
                    val found = discoverByScan(scanPrefix, latest.phoneIsGroupOwner)
                    if (!found.isNullOrBlank()) {
                        Log.i(TAG, "Resolved glasses HTTP IP via scan: $found")
                        return Result.Resolved(found, viaScan = true)
                    }
                }
            }

            delay(ROUND_DELAY_MS)
        }
        return if (coroutineContext.isActive && stillWanted()) Result.TimedOut else null
    }

    private fun mediaConfigOk(ip: String, timeoutMs: Int, logFailures: Boolean): Boolean {
        if (!portOpen(ip, (timeoutMs / 2).coerceAtLeast(400))) {
            if (logFailures) Log.w(TAG, "media.config probe skipped for $ip (port 80 closed/unreachable)")
            return false
        }
        val ok = mediaConfigReachable(ip, timeoutMs)
        if (!ok && logFailures) Log.w(TAG, "media.config probe failed for $ip")
        return ok
    }

    private suspend fun mediaConfigOkWithRetry(ip: String): Boolean {
        for (attempt in 1..BLE_PROBE_ATTEMPTS) {
            if (mediaConfigOk(ip, PROBE_TIMEOUT_MS, logFailures = attempt == BLE_PROBE_ATTEMPTS)) return true
            if (attempt < BLE_PROBE_ATTEMPTS) {
                Log.i(TAG, "media.config probe attempt $attempt/$BLE_PROBE_ATTEMPTS failed for $ip, retrying in ${BLE_PROBE_RETRY_DELAY_MS}ms")
                delay(BLE_PROBE_RETRY_DELAY_MS)
            }
        }
        return false
    }

    private val scanner = HttpSubnetScanner(
        portOpen = portOpen,
        mediaConfigOk = { ip, timeoutMs -> mediaConfigOk(ip, timeoutMs, logFailures = false) },
    )

    /** Port-80 scan of the /24 (32 at a time); prefers a host that serves media.config. */
    private suspend fun discoverByScan(prefix: String, phoneIsGroupOwner: Boolean?): String? =
        scanner.scan(scanHosts(prefix, phoneIsGroupOwner), SCAN_LIMITS)

    companion object {
        private const val TAG = "DataDownload"
        const val OVERALL_TIMEOUT_MS = 45_000L

        /** v2.3.0 waited this long after the P2P + BLE IP signals, like the vendor app. */
        const val START_DELAY_MS = 1_000L
        const val ROUND_DELAY_MS = 1_500L
        private const val STATUS_LOG_INTERVAL_MS = 5_000L
        private const val PROBE_TIMEOUT_MS = 2_000
        private const val BLE_PROBE_ATTEMPTS = 3
        private const val BLE_PROBE_RETRY_DELAY_MS = 2_000L
        private const val SCAN_CONCURRENCY = 32
        private const val SCAN_CONNECT_TIMEOUT_MS = 300
        private const val SCAN_VERIFY_TIMEOUT_MS = 1_200
        private const val SCAN_TIMEOUT_MS = 20_000L
        private val SCAN_LIMITS = HttpSubnetScanner.Limits(
            concurrency = SCAN_CONCURRENCY,
            connectTimeoutMs = SCAN_CONNECT_TIMEOUT_MS,
            verifyTimeoutMs = SCAN_VERIFY_TIMEOUT_MS,
            overallTimeoutMs = SCAN_TIMEOUT_MS,
            acceptOpenPortOnly = true,
        )

        /** Last octets probed on the guessed subnet: the glasses may be the group owner (.1). */
        private val SUBNET_GUESSES = listOf(1, 79, 2, 3)

        /**
         * The /24 to search: from the BLE IP, the bridge IP, the group-owner IP, then the
         * phone's own Wi-Fi Direct interface address.
         */
        fun subnetPrefix(inputs: CustomResolverInputs, interfaceIpv4: () -> String?): String? =
            GlassesNetworkAddressing.prefix24(inputs.bleIp)
                ?: GlassesNetworkAddressing.prefix24(inputs.bridgeIp)
                ?: GlassesNetworkAddressing.prefix24(inputs.groupOwnerIp)
                ?: GlassesNetworkAddressing.prefix24(interfaceIpv4())

        /** v2.3.0 buildCandidateIps(): reported addresses first, then subnet guesses. */
        fun candidateIps(inputs: CustomResolverInputs, subnetPrefix: String?): List<String> {
            val ordered = LinkedHashSet<String>()
            inputs.bleIp?.let(ordered::add)
            inputs.bridgeIp?.let(ordered::add)
            inputs.groupOwnerIp?.let(ordered::add)
            subnetPrefix?.let { prefix -> SUBNET_GUESSES.forEach { ordered.add("$prefix$it") } }
            return ordered.toList()
        }

        /** [candidateIps] without blanks and without the phone's own group-owner address. */
        fun probeTargets(inputs: CustomResolverInputs, subnetPrefix: String?): List<String> =
            candidateIps(inputs, subnetPrefix).filter {
                it.isNotBlank() && !GlassesNetworkAddressing.isPhoneGroupOwnerAddress(it, inputs.phoneIsGroupOwner)
            }

        /**
         * The one /24 scan runs on a formed group only while no glasses IP is known. This is the
         * v2.3.0 condition, kept for comparable logs: the coordinator starts the resolver only
         * after an IP was reported, and neither the BLE IP nor the bridge IP is cleared during a
         * session, so in practice the scan does not run.
         */
        fun shouldScanSubnet(alreadyScanned: Boolean, inputs: CustomResolverInputs): Boolean =
            !alreadyScanned &&
                inputs.p2pConnected &&
                inputs.bleIp == null &&
                inputs.bridgeIp == null

        fun scanHosts(prefix: String, phoneIsGroupOwner: Boolean?): List<String> =
            HttpSubnetScanner.hostsOf(prefix)
                .filterNot { GlassesNetworkAddressing.isPhoneGroupOwnerAddress(it, phoneIsGroupOwner) }
    }
}
