package com.fersaiyan.cyanbridge.devices.mentra

import android.content.Context
import android.util.Log
import com.fersaiyan.cyanbridge.audio.AudioSessionCoordinator
import com.fersaiyan.cyanbridge.audio.AudioSessionHandle
import com.fersaiyan.cyanbridge.glasses.GlassesSession
import com.fersaiyan.cyanbridge.glasses.GlassesSessionCoordinator
import com.fersaiyan.cyanbridge.glasses.GlassesSessionLease
import com.fersaiyan.cyanbridge.shared.audio.AudioRoutePreference
import com.fersaiyan.cyanbridge.shared.audio.AudioSessionRequest
import com.fersaiyan.cyanbridge.shared.devices.mentra.MentraBroadcastPhase
import com.fersaiyan.cyanbridge.shared.devices.mentra.MentraBroadcastUrlCheck
import com.fersaiyan.cyanbridge.shared.devices.mentra.MentraBroadcastUrlProblem
import com.fersaiyan.cyanbridge.shared.devices.mentra.MentraStreamUrls
import com.mentra.bluetoothsdk.StreamRequest
import com.mentra.bluetoothsdk.StreamStatus
import com.mentra.bluetoothsdk.StreamStatusEvent
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/** What the broadcast screen shows. [detail] is the glasses' last status or error text. */
data class MentraBroadcastState(
    val phase: MentraBroadcastPhase = MentraBroadcastPhase.IDLE,
    /** The destination with its stream key masked. */
    val destination: String? = null,
    val detail: String? = null,
    /** Encoded size, frame rate and bitrate the glasses chose, for example "1280x720 30 fps 4.0 Mbit/s". */
    val video: String? = null,
    /** Live encoder numbers while streaming. */
    val stats: String? = null,
    val includesAudio: Boolean = false,
)

/** Why a broadcast could not start. The screen maps each to a message. */
sealed interface MentraBroadcastRefusal {
    data class InvalidUrl(val problem: MentraBroadcastUrlProblem) : MentraBroadcastRefusal
    data object NotReady : MentraBroadcastRefusal
    data object NoWifi : MentraBroadcastRefusal
    data object FirmwareUpdate : MentraBroadcastRefusal
    data class Busy(val session: String) : MentraBroadcastRefusal
    data object AlreadyActive : MentraBroadcastRefusal
}

/**
 * Broadcasts the Mentra Live camera to the user's own RTMP/SRT server: the glasses publish over
 * their own Wi-Fi (`startStream`), the SDK runs the keep-alives, and the phone only shows the
 * `stream_status` events and stops the stream. The glasses need Wi-Fi with internet; the phone's
 * network is not involved.
 *
 * Holds the shared glasses camera lease ([GlassesSession.LIVE_PREVIEW]) so AI photos and the live
 * preview wait, and a [AudioSessionRequest.Recording] session for the glasses microphone (the
 * stream's audio). When the arbiter refuses the recording, the broadcast runs without audio.
 * Lives for the process, so the screen can close while the broadcast runs.
 */
object MentraBroadcastController {
    private const val TAG = "MentraBroadcast"
    private const val AUDIO_OWNER = "MentraBroadcast"
    private const val STOP_TIMEOUT_MS = 15_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(MentraBroadcastState())
    val state: StateFlow<MentraBroadcastState> = _state.asStateFlow()

    private val lock = Any()
    private var lease: GlassesSessionLease? = null
    private var audio: AudioSessionHandle? = null
    private var watchers: Job? = null
    private var startJob: Job? = null
    private var streamId: String? = null

    val isActive: Boolean get() = _state.value.phase.isActive

    /** Starts the broadcast, or returns why it cannot start. Never blocks the caller. */
    fun start(context: Context, rawUrl: String): MentraBroadcastRefusal? {
        val url = when (val check = MentraStreamUrls.checkBroadcastUrl(rawUrl)) {
            is MentraBroadcastUrlCheck.Invalid -> return MentraBroadcastRefusal.InvalidUrl(check.problem)
            is MentraBroadcastUrlCheck.Valid -> check.url
        }
        val manager = MentraLiveManager.instanceOrNull()?.takeIf { it.ready } ?: return MentraBroadcastRefusal.NotReady
        if (manager.firmwareUpdateActive) return MentraBroadcastRefusal.FirmwareUpdate
        if (manager.wifi() == null) return MentraBroadcastRefusal.NoWifi
        synchronized(lock) {
            if (isActive) return MentraBroadcastRefusal.AlreadyActive
            val claimed = GlassesSessionCoordinator.tryAcquireLease(GlassesSession.LIVE_PREVIEW)
                ?: return MentraBroadcastRefusal.Busy(GlassesSessionCoordinator.currentSession()?.label ?: "another glasses task")
            lease = claimed
            audio = AudioSessionCoordinator.tryOpen(
                context,
                AudioSessionRequest.Recording(AUDIO_OWNER, route = AudioRoutePreference.DEVICE_SDK),
            )
            val id = "cyanbridge-broadcast-" + UUID.randomUUID()
            streamId = id
            val withAudio = audio != null
            if (!withAudio) Log.i(TAG, "Another recording holds the microphone; broadcasting video only")
            _state.value = MentraBroadcastState(
                phase = MentraBroadcastPhase.STARTING,
                destination = MentraStreamUrls.redacted(url),
                includesAudio = withAudio,
            )
            watchers = scope.launch { watch(manager, id) }
            startJob = scope.launch { run(manager, url, id, withAudio) }
        }
        return null
    }

    private suspend fun run(manager: MentraLiveManager, url: String, id: String, withAudio: Boolean) {
        Log.i(TAG, "startStream ${MentraStreamUrls.redacted(url)} audio=$withAudio")
        try {
            val started = manager.startStream(StreamRequest(url, id, true, null, null, null, withAudio))
            apply(started)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "Broadcast did not start: ${error.message}")
            // A timed-out start may still have reached the glasses; make sure nothing keeps streaming.
            if (manager.connected) {
                runCatching { withTimeout(STOP_TIMEOUT_MS) { manager.stopStream() } }
            }
            finish(MentraBroadcastPhase.FAILED, error.message ?: "The broadcast did not start")
        }
    }

    private suspend fun watch(manager: MentraLiveManager, id: String): Unit = coroutineScope {
        launch {
            manager.glasses.first { !it.connected }
            finish(MentraBroadcastPhase.FAILED, "Mentra Live disconnected")
        }
        manager.streamStatus.collect { event ->
            if (event.streamId != null && event.streamId != id) return@collect
            apply(event)
        }
    }

    private fun apply(event: StreamStatusEvent) {
        val phase = MentraBroadcastPhase.fromStreamState(event.state?.value, event.willRetry) ?: return
        val video = event.resolvedConfig?.video?.let {
            String.format(Locale.US, "%dx%d %.0f fps %.1f Mbit/s", it.width, it.height, it.fps, it.bitrate / 1_000_000.0)
        }
        val stats = event.stats?.let { stats ->
            listOfNotNull(
                stats.bitrate?.let { String.format(Locale.US, "%.1f Mbit/s", it / 1_000_000.0) },
                stats.fps?.let { String.format(Locale.US, "%.0f fps", it) },
                stats.droppedFrames?.takeIf { it > 0 }?.let { "$it dropped" },
            ).joinToString(" · ").ifEmpty { null }
        }
        val detail = event.errorDetails ?: (event.status as? StreamStatus.Reconnecting)?.let {
            "Reconnecting ${it.attempt}/${it.maxAttempts}" + (it.reason?.let { reason -> ": $reason" } ?: "")
        }
        Log.i(TAG, "Broadcast ${event.state?.value} ${detail.orEmpty()}")
        when (phase) {
            MentraBroadcastPhase.FAILED, MentraBroadcastPhase.STOPPED -> finish(phase, detail)
            else -> if (isActive) {
                _state.value = _state.value.copy(
                    phase = if (_state.value.phase == MentraBroadcastPhase.STOPPING) MentraBroadcastPhase.STOPPING else phase,
                    detail = detail,
                    video = video ?: _state.value.video,
                    stats = stats ?: _state.value.stats,
                )
            }
        }
    }

    /** Stops a running broadcast; the glasses confirm with `stopped`. */
    fun stop() {
        if (!isActive) return
        _state.value = _state.value.copy(phase = MentraBroadcastPhase.STOPPING)
        scope.launch {
            startJob?.cancel()
            val manager = MentraLiveManager.instanceOrNull()
            if (manager?.connected == true) {
                runCatching { withTimeout(STOP_TIMEOUT_MS) { manager.stopStream() } }
                    .onFailure { Log.w(TAG, "stopStream failed: ${it.message}") }
            }
            finish(MentraBroadcastPhase.STOPPED, null)
        }
    }

    private fun finish(phase: MentraBroadcastPhase, detail: String?) {
        val (oldLease, oldAudio, oldWatchers) = synchronized(lock) {
            if (!isActive && lease == null) return
            val released = Triple(lease, audio, watchers)
            lease = null
            audio = null
            watchers = null
            streamId = null
            _state.value = _state.value.copy(phase = phase, detail = detail ?: _state.value.detail, stats = null)
            released
        }
        oldLease?.let(GlassesSessionCoordinator::release)
        runCatching { oldAudio?.close() }
        Log.i(TAG, "Broadcast ended ($phase)")
        // Last, because finish may run inside one of these watchers.
        oldWatchers?.cancel()
    }
}
