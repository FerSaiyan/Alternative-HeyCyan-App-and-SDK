package com.fersaiyan.cyanbridge.audio

import android.content.Context
import android.media.AudioManager
import android.util.Log
import com.fersaiyan.cyanbridge.shared.audio.AudioArbitration
import com.fersaiyan.cyanbridge.shared.audio.AudioRouteLedger
import com.fersaiyan.cyanbridge.shared.audio.AudioRoutePolicy
import com.fersaiyan.cyanbridge.shared.audio.AudioRouteState
import com.fersaiyan.cyanbridge.shared.audio.AudioRouteWait
import com.fersaiyan.cyanbridge.shared.audio.AudioSessionArbiter
import com.fersaiyan.cyanbridge.shared.audio.AudioSessionKind
import com.fersaiyan.cyanbridge.shared.audio.AudioSessionRequest
import com.fersaiyan.cyanbridge.shared.audio.AudioSessionSnapshot
import com.fersaiyan.cyanbridge.shared.audio.AudioSessionTransition
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

/** Session lifecycle callbacks. They run on the thread that caused the change; post if needed. */
interface AudioSessionListener {
    /** Another session took the microphone; stop capturing but keep the session. */
    fun onSuspended() = Unit

    /** The microphone is yours again (also delivered when a waiting session first gets it). */
    fun onResumed() = Unit

    /** The session was ended by a higher-priority one; stop and close the handle. */
    fun onEnded() = Unit

    companion object {
        val NONE = object : AudioSessionListener {}
    }
}

sealed class AudioSessionGrant {
    data class Granted(val handle: AudioSessionHandle) : AudioSessionGrant()
    data class Denied(val blockingOwner: String, val blockingKind: AudioSessionKind, val reason: String) : AudioSessionGrant()
}

/**
 * The process-wide owner of app audio sessions. Features request a typed session
 * ([AudioSessionRequest]) and receive an [AudioSessionHandle] that owns the session's microphone
 * slot, audio focus, shared Bluetooth route and recorders. Arbitration and routing policy live in
 * `:shared` ([AudioSessionArbiter], [AudioRoutePolicy]); this object executes them on Android.
 *
 * It also keeps the process-wide "speech is playing" flag that spoken prompts wait on before
 * listening (formerly `localagent.AudioSessionCoordinator`).
 */
object AudioSessionCoordinator {
    internal const val TAG = "AudioSession"

    private val lock = Any()
    private val arbiter = AudioSessionArbiter()
    private val ledger = AudioRouteLedger()
    private val handles = mutableMapOf<Long, AudioSessionHandle>()
    private var route: BluetoothCommunicationRoute? = null

    fun open(
        context: Context,
        request: AudioSessionRequest,
        listener: AudioSessionListener = AudioSessionListener.NONE,
    ): AudioSessionGrant {
        val appContext = context.applicationContext
        val (grant, transitions) = synchronized(lock) {
            if (route == null) route = BluetoothCommunicationRoute(appContext)
            when (val result = arbiter.request(request)) {
                is AudioArbitration.Denied -> {
                    Log.i(TAG, "${request.owner} ${request.kind} denied: ${result.reason}")
                    AudioSessionGrant.Denied(result.blockingOwner, result.blockingKind, result.reason) to emptyList()
                }
                is AudioArbitration.Granted -> {
                    val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    val handle = AudioSessionHandle(result.sessionId, request, listener, audioManager, result.state)
                    handles[result.sessionId] = handle
                    Log.i(TAG, "${request.owner} ${request.kind} granted state=${result.state} active=${describeLocked()}")
                    AudioSessionGrant.Granted(handle) to result.transitions
                }
            }
        }
        deliver(transitions)
        return grant
    }

    /** [open] that returns null when denied. */
    fun tryOpen(
        context: Context,
        request: AudioSessionRequest,
        listener: AudioSessionListener = AudioSessionListener.NONE,
    ): AudioSessionHandle? = (open(context, request, listener) as? AudioSessionGrant.Granted)?.handle

    fun snapshot(): List<AudioSessionSnapshot> = synchronized(lock) { arbiter.snapshot() }

    internal fun leaveRoute(handle: AudioSessionHandle) {
        synchronized(lock) {
            val undo = ledger.leave(handle.id)
            if (undo != AudioRouteState.IDLE) route?.restore(undo, handle.request.owner)
        }
    }

    internal fun release(handle: AudioSessionHandle) {
        val (undo, transitions) = synchronized(lock) {
            handles.remove(handle.id)
            val undo = ledger.leave(handle.id)
            if (undo != AudioRouteState.IDLE) route?.restore(undo, handle.request.owner)
            undo to arbiter.release(handle.id)
        }
        Log.i(TAG, "${handle.request.owner} ${handle.request.kind} released restoredRoute=${undo != AudioRouteState.IDLE}")
        deliver(transitions)
    }

    /** Joins the shared route without waiting for Bluetooth to come up. */
    internal fun connectRouteNow(handle: AudioSessionHandle, options: AudioRouteOptions): AudioRouteResult {
        return synchronized(lock) {
            val routeExecutor = route ?: return AudioRouteResult.PHONE
            val outcome = planAndApplyLocked(handle, routeExecutor, options)
            outcome.toResult(routeExecutor, confirmed = outcome.added.bluetooth)
        }
    }

    /** Joins the shared route and waits for the Bluetooth link per [AudioRouteOptions.wait]. */
    internal suspend fun connectRoute(handle: AudioSessionHandle, options: AudioRouteOptions): AudioRouteResult {
        val routeExecutor = synchronized(lock) { route } ?: return AudioRouteResult.PHONE
        var outcome: RouteJoin? = null
        val (_, confirmed) = routeExecutor.applyAndAwait(options.wait, handle.request.owner) {
            synchronized(lock) { planAndApplyLocked(handle, routeExecutor, options).also { outcome = it }.added }
        }
        val joined = outcome ?: return AudioRouteResult.PHONE
        return synchronized(lock) { joined.toResult(routeExecutor, confirmed) }
    }

    private class RouteJoin(
        val added: AudioRouteState,
        val previous: AudioRouteState,
        val candidatesPresent: Boolean,
        val ambiguous: Boolean,
    )

    private fun planAndApplyLocked(
        handle: AudioSessionHandle,
        routeExecutor: BluetoothCommunicationRoute,
        options: AudioRouteOptions,
    ): RouteJoin {
        val previous = ledger.applied
        // A handle closed while it was connecting must not rejoin (and so pin) the route.
        if (handle.isClosed) return RouteJoin(AudioRouteState.IDLE, previous, candidatesPresent = false, ambiguous = false)
        val snapshot = routeExecutor.snapshot()
        val plan = AudioRoutePolicy.plan(
            preference = handle.request.route,
            environment = snapshot.environment,
            current = previous,
            preferredName = options.preferredDeviceName,
            requireUnambiguous = options.requireUnambiguous,
        )
        val added = if (plan.changesRoute) routeExecutor.apply(plan, snapshot, handle.request.owner) else AudioRouteState.IDLE
        ledger.join(handle.id, added)
        Log.i(
            TAG,
            "${handle.request.owner} route plan=$plan users=${ledger.userCount} ${routeExecutor.summary()}",
        )
        return RouteJoin(added, previous, snapshot.environment.communicationCandidates.isNotEmpty(), plan.ambiguous)
    }

    private fun RouteJoin.toResult(routeExecutor: BluetoothCommunicationRoute, confirmed: Boolean): AudioRouteResult {
        // Joining a route another session already brought to Bluetooth reuses it as-is.
        val bluetooth = (added.bluetooth && confirmed) || previous.bluetooth
        return AudioRouteResult(
            bluetoothActive = bluetooth,
            inputDevice = if (bluetooth) routeExecutor.bluetoothInputDevice(ledger.applied) else null,
            bluetoothCandidatesPresent = candidatesPresent,
            ambiguous = ambiguous,
        )
    }

    private fun deliver(transitions: List<AudioSessionTransition>) {
        transitions.forEach { transition ->
            val handle = synchronized(lock) { handles[transition.sessionId] } ?: return@forEach
            Log.i(TAG, "${transition.owner} ${transition.kind} -> ${transition.state}")
            handle.deliver(transition.state)
        }
    }

    private fun describeLocked(): String =
        arbiter.snapshot().joinToString(prefix = "[", postfix = "]") { "${it.owner}:${it.kind}:${it.state}" }

    // ---------------------------------------------------------------------
    // Speech playback flag
    // ---------------------------------------------------------------------

    private val speechBusy = AtomicBoolean(false)

    @Volatile
    private var speechIdle: CompletableDeferred<Unit>? = null

    /** True while app speech that spoken prompts should not talk over is playing. */
    fun isSpeechBusy(): Boolean = speechBusy.get()

    fun markSpeechBusy() {
        speechBusy.set(true)
        speechIdle = CompletableDeferred()
    }

    fun markSpeechIdle() {
        speechIdle?.complete(Unit)
        speechIdle = null
        speechBusy.set(false)
    }

    /** Waits (bounded) for [markSpeechIdle] so listening does not start over our own speech. */
    suspend fun awaitSpeechIdle(timeoutMs: Long = 5_000L) {
        if (!speechBusy.get()) return
        val deferred = speechIdle ?: return
        withTimeoutOrNull(timeoutMs) { deferred.await() }
    }

    /** Default route waits for each kind of session, matching their previous timing. */
    internal fun defaultWait(kind: AudioSessionKind): AudioRouteWait = when (kind) {
        AudioSessionKind.VOICE_COMMAND -> AudioRouteWait.CONFIRMED
        AudioSessionKind.RECORDING, AudioSessionKind.AMBIENT_LISTENING -> AudioRouteWait.RECORDING
        else -> AudioRouteWait.NONE
    }
}

/** Options for joining the shared route. */
data class AudioRouteOptions(
    val wait: AudioRouteWait,
    /** Prefer the headset with this name (for example the connected glasses). */
    val preferredDeviceName: String? = null,
    /** With several headsets and no name match, select none instead of the first. */
    val requireUnambiguous: Boolean = false,
)
