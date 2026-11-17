package com.fersaiyan.cyanbridge.media

import android.content.Context
import android.util.Log
import com.fersaiyan.cyanbridge.media.sync.HttpSubnetScanner
import com.fersaiyan.cyanbridge.shared.diagnostics.DiagnosticEvents
import com.fersaiyan.cyanbridge.shared.diagnostics.DiagnosticSubsystem
import com.fersaiyan.cyanbridge.shared.glasses.GlassesSyncFlow
import com.fersaiyan.cyanbridge.shared.network.GlassesNetworkAddressing
import com.fersaiyan.cyanbridge.shared.prefs.MediaPrefs.AdaptiveP2pProfiles as Profiles
import com.fersaiyan.cyanbridge.shared.prefs.edit
import com.fersaiyan.cyanbridge.shared.prefs.get
import com.fersaiyan.cyanbridge.shared.prefs.set
import com.fersaiyan.cyanbridge.shared.prefs.store
import java.security.MessageDigest
import java.util.Locale

internal enum class AdaptiveSyncCheckpoint {
    BLE_READY,
    TRANSFER_COMMAND_SENT,
    TRANSFER_CALLBACK,
    TRANSFER_EVIDENCE,
    DEVICE_ERROR,
    MATCHING_PEER_FOUND,
    CONNECT_REQUEST_ACCEPTED,
    P2P_CONNECT_FAILED,
    P2P_GROUP_FORMED,
    BLE_IP_RECEIVED,
    P2P_NETWORK_IDENTIFIED,
    TCP_80_CONNECTED,
    MEDIA_CONFIG_HEADERS,
    MEDIA_CONFIG_COMPLETE,
    SUBNET_SCAN,
    MEDIA_FILE_RETRY,
    MEDIA_FILE_PROGRESS,
    COMPLETE,
    FAILED,
}

internal enum class AdaptiveHttpRoute {
    P2P_NETWORK,
    LOCAL_ADDRESS,
    SYSTEM,
}

/** Where the glasses HTTP address that answered came from. */
internal enum class AdaptiveIpSource {
    /** Reported by the glasses over BLE (or the BLE IP bridge). */
    REPORTED,
    /** Remembered from an earlier sync whose reported address was wrong. */
    LEARNED,
    /** Found by the subnet scan fallback in this sync. */
    SCAN,
}

internal data class AdaptiveP2pProfile(
    val preferredRoute: AdaptiveHttpRoute? = null,
    val preferredWarmupMs: Long? = null,
    val candidateRoute: AdaptiveHttpRoute? = null,
    val candidateWarmupMs: Long? = null,
    val candidateSuccesses: Int = 0,
    val completedSyncs: Int = 0,
    val consecutiveFailures: Int = 0,
    /**
     * The glasses HTTP address that answered when the reported one did not (found by the subnet
     * scan). Tried first on the next sync; cleared once the reported address answers again.
     */
    val learnedHttpIp: String? = null,
) {
    fun routeOrder(): List<AdaptiveHttpRoute> = buildList {
        candidateRoute?.let(::add)
        preferredRoute?.let(::add)
        add(AdaptiveHttpRoute.P2P_NETWORK)
        add(AdaptiveHttpRoute.LOCAL_ADDRESS)
        add(AdaptiveHttpRoute.SYSTEM)
    }.distinct()

    fun warmupScheduleMs(): List<Long> = buildList {
        candidateWarmupMs?.let(::add)
        preferredWarmupMs?.let(::add)
        add(1_000L)
        add(2_000L)
        add(4_000L)
        add(8_000L)
    }.map { it.coerceIn(0L, MAX_LEARNED_WARMUP_MS) }.distinct()

    fun withSuccessfulSync(
        route: AdaptiveHttpRoute,
        warmupMs: Long,
        learnedHttpIp: String? = this.learnedHttpIp,
    ): AdaptiveP2pProfile {
        val boundedWarmup = warmupMs.coerceIn(0L, MAX_LEARNED_WARMUP_MS)
        val sameCandidate = candidateRoute == route && candidateWarmupMs == boundedWarmup
        val successes = if (sameCandidate) candidateSuccesses + 1 else 1
        val promote = successes >= REQUIRED_SUCCESSES_TO_PROMOTE
        return copy(
            preferredRoute = if (promote) route else preferredRoute,
            preferredWarmupMs = if (promote) boundedWarmup else preferredWarmupMs,
            candidateRoute = route,
            candidateWarmupMs = boundedWarmup,
            candidateSuccesses = successes,
            completedSyncs = completedSyncs + 1,
            consecutiveFailures = 0,
            learnedHttpIp = learnedHttpIp,
        )
    }

    fun withFailedSync(): AdaptiveP2pProfile {
        val failures = consecutiveFailures + 1
        return copy(
            candidateRoute = if (failures >= FAILURES_TO_DECAY_CANDIDATE) null else candidateRoute,
            candidateWarmupMs = if (failures >= FAILURES_TO_DECAY_CANDIDATE) null else candidateWarmupMs,
            candidateSuccesses = if (failures >= FAILURES_TO_DECAY_CANDIDATE) 0 else candidateSuccesses,
            consecutiveFailures = failures,
            learnedHttpIp = if (failures >= FAILURES_TO_DECAY_CANDIDATE) null else learnedHttpIp,
        )
    }

    companion object {
        const val MAX_LEARNED_WARMUP_MS = 8_000L
        const val REQUIRED_SUCCESSES_TO_PROMOTE = 2
        const val FAILURES_TO_DECAY_CANDIDATE = 2
    }
}

internal data class AdaptiveSyncEvent(
    val elapsedMs: Long,
    val checkpoint: AdaptiveSyncCheckpoint,
    val detail: String,
)

internal class AdaptiveP2pSyncSession(
    val profileKey: String,
    val profile: AdaptiveP2pProfile,
    private val clockMs: () -> Long,
) {
    private val startedAtMs = clockMs()
    private val events = ArrayDeque<AdaptiveSyncEvent>()

    @Volatile
    var transferCommandSends: Int = 0
        private set
    @Volatile
    var discoveryRestarts: Int = 0
        private set
    @Volatile
    var deviceResets: Int = 0
        private set
    @Volatile
    var successfulRoute: AdaptiveHttpRoute? = null
        private set
    @Volatile
    var successfulWarmupMs: Long? = null
        private set
    @Volatile
    var successfulIp: String? = null
        private set
    @Volatile
    var successfulIpSource: AdaptiveIpSource? = null
        private set
    @Volatile
    var lastCheckpoint: AdaptiveSyncCheckpoint? = null
        private set

    @Synchronized
    fun mark(checkpoint: AdaptiveSyncCheckpoint, detail: String = "") {
        if (checkpoint == AdaptiveSyncCheckpoint.MEDIA_FILE_PROGRESS &&
            events.any { it.checkpoint == AdaptiveSyncCheckpoint.MEDIA_FILE_PROGRESS }
        ) return
        val event = AdaptiveSyncEvent(
            elapsedMs = (clockMs() - startedAtMs).coerceAtLeast(0L),
            checkpoint = checkpoint,
            detail = detail.take(MAX_EVENT_DETAIL_CHARS),
        )
        if (events.lastOrNull()?.let { it.checkpoint == checkpoint && it.detail == event.detail } == true) return
        if (events.size >= MAX_EVENTS) events.removeFirst()
        events.addLast(event)
        lastCheckpoint = checkpoint
    }

    @Synchronized
    fun noteTransferCommand(attempt: Int, maxSends: Int = Int.MAX_VALUE): Boolean {
        if (transferCommandSends >= maxSends) return false
        transferCommandSends++
        mark(
            AdaptiveSyncCheckpoint.TRANSFER_COMMAND_SENT,
            "attempt=$attempt,send_count=$transferCommandSends",
        )
        return true
    }

    @Synchronized
    fun noteDiscoveryRestart() {
        discoveryRestarts++
    }

    @Synchronized
    fun noteDeviceReset() {
        deviceResets++
    }

    @Synchronized
    fun noteHttpSuccess(
        route: AdaptiveHttpRoute,
        warmupMs: Long,
        ip: String? = null,
        ipSource: AdaptiveIpSource = AdaptiveIpSource.REPORTED,
    ) {
        successfulRoute = route
        successfulWarmupMs = warmupMs
        successfulIp = ip
        successfulIpSource = ipSource
        mark(
            AdaptiveSyncCheckpoint.MEDIA_CONFIG_COMPLETE,
            "route=${route.name},warmup_ms=$warmupMs,ip_source=${ipSource.name}",
        )
    }

    /** The address to remember after this sync: kept only when the reported one did not answer. */
    fun learnedHttpIpAfterSuccess(): String? = when (successfulIpSource) {
        AdaptiveIpSource.SCAN, AdaptiveIpSource.LEARNED -> successfulIp
        AdaptiveIpSource.REPORTED, null -> null
    }

    @Synchronized
    fun traceSummary(): String = events.joinToString(separator = "\n") { event ->
        "+${event.elapsedMs}ms ${event.checkpoint.name}${event.detail.takeIf(String::isNotBlank)?.let { " $it" }.orEmpty()}"
    }

    companion object {
        private const val MAX_EVENTS = 64
        private const val MAX_EVENT_DETAIL_CHARS = 180
    }
}

internal object AdaptiveP2pProfileStore {
    fun profileKey(
        phoneManufacturer: String,
        phoneModel: String,
        androidSdk: Int,
        glassesName: String?,
        glassesAddress: String?,
        hardwareVersion: String?,
        firmwareVersion: String?,
    ): String {
        val identity = listOf(
            phoneManufacturer,
            phoneModel,
            androidSdk.toString(),
            glassesName.orEmpty(),
            glassesAddress.orEmpty(),
            hardwareVersion.orEmpty(),
            firmwareVersion.orEmpty(),
        ).joinToString("|") { it.trim().lowercase(Locale.US) }
        return MessageDigest.getInstance("SHA-256")
            .digest(identity.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(Locale.US, it.toInt() and 0xff) }
            .take(24)
    }

    fun load(context: Context, key: String): AdaptiveP2pProfile {
        val prefs = Profiles.store(context)
        fun route(stored: String?): AdaptiveHttpRoute? = AdaptiveHttpRoute.entries.firstOrNull { it.name == stored }
        return AdaptiveP2pProfile(
            preferredRoute = route(prefs[Profiles.preferredRoute[key]]),
            preferredWarmupMs = prefs[Profiles.preferredWarmupMs[key]],
            candidateRoute = route(prefs[Profiles.candidateRoute[key]]),
            candidateWarmupMs = prefs[Profiles.candidateWarmupMs[key]],
            candidateSuccesses = prefs[Profiles.candidateSuccesses[key]],
            completedSyncs = prefs[Profiles.completedSyncs[key]],
            consecutiveFailures = prefs[Profiles.consecutiveFailures[key]],
            learnedHttpIp = prefs[Profiles.learnedHttpIp[key]],
        )
    }

    fun recordSuccess(
        context: Context,
        key: String,
        route: AdaptiveHttpRoute,
        warmupMs: Long,
        learnedHttpIp: String?,
    ): AdaptiveP2pProfile {
        val updated = load(context, key).withSuccessfulSync(route, warmupMs, learnedHttpIp)
        save(context, key, updated)
        return updated
    }

    fun recordFailure(context: Context, key: String): AdaptiveP2pProfile {
        val updated = load(context, key).withFailedSync()
        save(context, key, updated)
        return updated
    }

    private fun save(context: Context, key: String, profile: AdaptiveP2pProfile) {
        Profiles.edit(context) {
            this[Profiles.preferredRoute[key]] = profile.preferredRoute?.name
            this[Profiles.preferredWarmupMs[key]] = profile.preferredWarmupMs
            this[Profiles.candidateRoute[key]] = profile.candidateRoute?.name
            this[Profiles.candidateWarmupMs[key]] = profile.candidateWarmupMs
            this[Profiles.candidateSuccesses[key]] = profile.candidateSuccesses
            this[Profiles.completedSyncs[key]] = profile.completedSyncs
            this[Profiles.consecutiveFailures[key]] = profile.consecutiveFailures
            this[Profiles.learnedHttpIp[key]] = profile.learnedHttpIp
        }
    }
}

/**
 * The only writer of learned routes for a finished media sync. Only the Adaptive flow learns:
 * the HeyCyan and Custom flows never touch the `adaptive_p2p_sync_profiles` file, so their logs
 * stay comparable with the vendor app and v2.3.0.
 */
internal object AdaptiveProfileLearning {
    private const val TAG = "DataDownload"

    /** Records a successful sync; returns the updated profile, or null when nothing was written. */
    fun recordSuccess(
        context: Context,
        flow: GlassesSyncFlow,
        session: AdaptiveP2pSyncSession?,
        activeRoute: AdaptiveHttpRoute?,
        activeWarmupMs: Long?,
    ): AdaptiveP2pProfile? {
        if (flow != GlassesSyncFlow.ADAPTIVE || session == null) return null
        val route = session.successfulRoute ?: activeRoute ?: return null
        val warmupMs = session.successfulWarmupMs ?: activeWarmupMs ?: 1_000L
        session.mark(AdaptiveSyncCheckpoint.COMPLETE, "route=${route.name},warmup_ms=$warmupMs")
        val learnedIp = session.learnedHttpIpAfterSuccess()
        val updated = AdaptiveP2pProfileStore.recordSuccess(context, session.profileKey, route, warmupMs, learnedIp)
        Log.i(
            TAG,
            "Adaptive profile learned success: route=$route warmupMs=$warmupMs " +
                "candidateSuccesses=${updated.candidateSuccesses} preferred=${updated.preferredRoute} " +
                "learnedHttpIp=${updated.learnedHttpIp} ipSource=${session.successfulIpSource}",
        )
        if (learnedIp != null) {
            DiagnosticEvents.info(
                DiagnosticSubsystem.SYNC,
                "adaptive learned glasses HTTP address",
                mapOf("ip" to learnedIp, "source" to (session.successfulIpSource?.name ?: ""), "route" to route.name),
            )
        }
        return updated
    }

    /** Records a failed sync; returns the updated profile, or null when nothing was written. */
    fun recordFailure(
        context: Context,
        flow: GlassesSyncFlow,
        session: AdaptiveP2pSyncSession?,
        message: String,
    ): AdaptiveP2pProfile? {
        if (flow != GlassesSyncFlow.ADAPTIVE || session == null) return null
        val failedAfter = session.lastCheckpoint
        session.mark(
            AdaptiveSyncCheckpoint.FAILED,
            "after=${failedAfter?.name ?: "NONE"},reason=${message.take(120)}",
        )
        val updated = AdaptiveP2pProfileStore.recordFailure(context, session.profileKey)
        Log.i(
            TAG,
            "Adaptive profile recorded failure: checkpoint=$failedAfter " +
                "consecutiveFailures=${updated.consecutiveFailures}",
        )
        return updated
    }
}

/**
 * The Adaptive flow's fallback for a wrong or stale glasses-reported IP: after the learned
 * address and the reported candidates all fail without reaching an HTTP server, scan the P2P
 * /24 for port 80. Only the Adaptive flow scans; HeyCyan and Custom keep their own resolvers.
 */
internal object AdaptiveSubnetScan {
    /** At most two scans per HTTP attempt: after the first failed round and once more later. */
    const val MAX_SCANS_PER_ATTEMPT = 2
    const val MIN_SCAN_SPACING_MS = 5_000L
    /** Time kept back after a scan to fetch media.config from the address it found. */
    const val RESERVE_AFTER_SCAN_MS = 8_000L
    const val MIN_SCAN_BUDGET_MS = 3_000L
    const val MAX_SCAN_MS = 12_000L
    private const val MAX_SCAN_PREFIXES = 2

    fun limits(budgetMs: Long) = HttpSubnetScanner.Limits(
        concurrency = 32,
        connectTimeoutMs = 300,
        verifyTimeoutMs = 1_500,
        overallTimeoutMs = budgetMs.coerceIn(MIN_SCAN_BUDGET_MS, MAX_SCAN_MS),
        acceptOpenPortOnly = true,
    )

    /**
     * Whether to scan now. [roundReachedServer] is true when some address answered HTTP (the IP
     * is right, the server is only warming up); [remainingMs] is what is left of the flow's
     * initial-phase budget.
     */
    fun shouldScan(
        flow: GlassesSyncFlow,
        p2pConnected: Boolean,
        roundReachedServer: Boolean,
        scansDone: Int,
        msSinceLastScan: Long?,
        remainingMs: Long,
    ): Boolean = flow == GlassesSyncFlow.ADAPTIVE &&
        p2pConnected &&
        !roundReachedServer &&
        scansDone < MAX_SCANS_PER_ATTEMPT &&
        (msSinceLastScan == null || msSinceLastScan >= MIN_SCAN_SPACING_MS) &&
        scanBudgetMs(remainingMs) >= MIN_SCAN_BUDGET_MS

    fun scanBudgetMs(remainingMs: Long): Long =
        (remainingMs - RESERVE_AFTER_SCAN_MS).coerceAtMost(MAX_SCAN_MS)

    /** The phone's own addresses on the group: never probed. */
    fun phoneAddresses(phoneInterfaceIp: String?, phoneIsGroupOwner: Boolean?): Set<String> = buildSet {
        phoneInterfaceIp?.takeIf { it.isNotBlank() }?.let(::add)
        if (phoneIsGroupOwner == true) add(GlassesNetworkAddressing.WIFI_DIRECT_GROUP_OWNER_IP)
    }

    /**
     * Hosts to scan: the group's /24 first (the phone's interface, then the group owner), then
     * the reported address's /24 when it differs, at most two /24s. Skips the phone and [tried].
     */
    fun hosts(
        phoneInterfaceIp: String?,
        groupOwnerIp: String?,
        reportedIps: List<String>,
        phoneIsGroupOwner: Boolean?,
        tried: Set<String>,
    ): List<String> {
        val prefixes = GlassesNetworkAddressing.subnetHints(phoneInterfaceIp, groupOwnerIp, *reportedIps.toTypedArray())
            .take(MAX_SCAN_PREFIXES)
        val excluded = phoneAddresses(phoneInterfaceIp, phoneIsGroupOwner) + tried
        return prefixes.flatMap { HttpSubnetScanner.hostsOf(it, excluded) }
    }

    /**
     * Addresses for the HTTP round, in order: the learned address (when it is on the group's
     * /24 or the subnet is unknown), then the reported ones, never the phone's own.
     */
    fun endpointIps(
        learnedIp: String?,
        reportedIps: List<String>,
        phoneInterfaceIp: String?,
        phoneIsGroupOwner: Boolean?,
    ): List<String> {
        val groupPrefix = GlassesNetworkAddressing.prefix24(phoneInterfaceIp)
        val learned = learnedIp?.takeIf {
            it.isNotBlank() && (groupPrefix == null || GlassesNetworkAddressing.prefix24(it) == groupPrefix)
        }
        val phone = phoneAddresses(phoneInterfaceIp, phoneIsGroupOwner)
        return (listOfNotNull(learned) + reportedIps).filter { it.isNotBlank() && it !in phone }.distinct()
    }
}
