package com.fersaiyan.cyanbridge.media

import android.content.Context
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
    HTTP_WARMUP_WAIT,
    HTTP_ROUTE_TRIAL,
    HTTP_ROUTE_FAILED,
    RECOVERY_STEP,
    MEDIA_CONFIG_COMPLETE,
    MEDIA_FILE_RETRY,
    MEDIA_FILE_PROGRESS,
    COMPLETE,
    FAILED,
    CANCELLED,
}

internal enum class AdaptiveHttpRoute {
    P2P_NETWORK,
    LOCAL_ADDRESS,
    SYSTEM,
}

internal data class AdaptiveP2pProfile(
    val preferredRoute: AdaptiveHttpRoute? = null,
    val preferredWarmupMs: Long? = null,
    val candidateRoute: AdaptiveHttpRoute? = null,
    val candidateWarmupMs: Long? = null,
    val candidateSuccesses: Int = 0,
    val completedSyncs: Int = 0,
    val consecutiveFailures: Int = 0,
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

    fun withSuccessfulSync(route: AdaptiveHttpRoute, warmupMs: Long): AdaptiveP2pProfile {
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
        )
    }

    fun withFailedSync(): AdaptiveP2pProfile {
        val failures = consecutiveFailures + 1
        return copy(
            candidateRoute = if (failures >= FAILURES_TO_DECAY_CANDIDATE) null else candidateRoute,
            candidateWarmupMs = if (failures >= FAILURES_TO_DECAY_CANDIDATE) null else candidateWarmupMs,
            candidateSuccesses = if (failures >= FAILURES_TO_DECAY_CANDIDATE) 0 else candidateSuccesses,
            consecutiveFailures = failures,
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
    private val onUpdate: (() -> Unit)?,
) {
    /** Preserve existing trailing-lambda call sites that supply only the monotonic clock. */
    constructor(profileKey: String, profile: AdaptiveP2pProfile, clockMs: () -> Long) :
        this(profileKey, profile, clockMs, null)

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
        onUpdate?.invoke()
    }

    /** A copy for UI rendering; callers cannot mutate or reorder the diagnostic trace. */
    @Synchronized
    fun snapshotEvents(): List<AdaptiveSyncEvent> = events.toList()

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
    fun noteHttpSuccess(route: AdaptiveHttpRoute, warmupMs: Long) {
        successfulRoute = route
        successfulWarmupMs = warmupMs
        mark(
            AdaptiveSyncCheckpoint.MEDIA_CONFIG_COMPLETE,
            "route=${route.name},warmup_ms=$warmupMs",
        )
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
    private const val PREFS = "adaptive_p2p_sync_profiles"

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
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        fun route(name: String): AdaptiveHttpRoute? = prefs.getString("$key.$name", null)
            ?.let { stored -> AdaptiveHttpRoute.entries.firstOrNull { it.name == stored } }
        fun optionalLong(name: String): Long? = if (prefs.contains("$key.$name")) {
            prefs.getLong("$key.$name", 0L)
        } else {
            null
        }
        return AdaptiveP2pProfile(
            preferredRoute = route("preferred_route"),
            preferredWarmupMs = optionalLong("preferred_warmup_ms"),
            candidateRoute = route("candidate_route"),
            candidateWarmupMs = optionalLong("candidate_warmup_ms"),
            candidateSuccesses = prefs.getInt("$key.candidate_successes", 0),
            completedSyncs = prefs.getInt("$key.completed_syncs", 0),
            consecutiveFailures = prefs.getInt("$key.consecutive_failures", 0),
        )
    }

    fun recordSuccess(
        context: Context,
        key: String,
        route: AdaptiveHttpRoute,
        warmupMs: Long,
    ): AdaptiveP2pProfile {
        val updated = load(context, key).withSuccessfulSync(route, warmupMs)
        save(context, key, updated)
        return updated
    }

    fun recordFailure(context: Context, key: String): AdaptiveP2pProfile {
        val updated = load(context, key).withFailedSync()
        save(context, key, updated)
        return updated
    }

    private fun save(context: Context, key: String, profile: AdaptiveP2pProfile) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            fun putRoute(name: String, route: AdaptiveHttpRoute?) {
                if (route == null) remove("$key.$name") else putString("$key.$name", route.name)
            }
            fun putOptionalLong(name: String, value: Long?) {
                if (value == null) remove("$key.$name") else putLong("$key.$name", value)
            }
            putRoute("preferred_route", profile.preferredRoute)
            putOptionalLong("preferred_warmup_ms", profile.preferredWarmupMs)
            putRoute("candidate_route", profile.candidateRoute)
            putOptionalLong("candidate_warmup_ms", profile.candidateWarmupMs)
            putInt("$key.candidate_successes", profile.candidateSuccesses)
            putInt("$key.completed_syncs", profile.completedSyncs)
            putInt("$key.consecutive_failures", profile.consecutiveFailures)
        }.apply()
    }
}
