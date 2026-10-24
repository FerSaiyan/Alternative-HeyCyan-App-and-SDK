package com.fersaiyan.cyanbridge.audio

import android.media.AudioDeviceInfo
import android.media.AudioManager
import com.fersaiyan.cyanbridge.shared.audio.AudioRouteWait
import com.fersaiyan.cyanbridge.shared.audio.AudioSessionRequest
import com.fersaiyan.cyanbridge.shared.audio.AudioSessionState
import java.io.Closeable
import java.util.concurrent.CopyOnWriteArrayList

/**
 * One granted audio session. It owns, until [close]: the microphone slot granted by the
 * arbitration policy, the session's audio focus, its share of the Bluetooth communication route,
 * and every recorder opened through [openRecorder]. Closing is idempotent and safe from any thread.
 */
class AudioSessionHandle internal constructor(
    val id: Long,
    val request: AudioSessionRequest,
    private val listener: AudioSessionListener,
    private val audioManager: AudioManager,
    initialState: AudioSessionState,
) : Closeable {
    private val recorders = CopyOnWriteArrayList<PcmRecorder>()

    @Volatile
    private var focus: AudioFocusLease? = null

    @Volatile
    var state: AudioSessionState = initialState
        private set

    @Volatile
    var isClosed: Boolean = false
        private set

    /** True while this session may use the microphone. */
    val isActive: Boolean get() = !isClosed && state == AudioSessionState.ACTIVE

    /**
     * Joins the shared phone route for [request]'s route preference and waits for Bluetooth per
     * [wait] (by default the timing this kind of session has always used).
     */
    suspend fun connectRoute(
        wait: AudioRouteWait = AudioSessionCoordinator.defaultWait(request.kind),
        preferredDeviceName: String? = null,
        requireUnambiguous: Boolean = false,
    ): AudioRouteResult {
        if (isClosed) return AudioRouteResult.PHONE
        return AudioSessionCoordinator.connectRoute(this, AudioRouteOptions(wait, preferredDeviceName, requireUnambiguous))
    }

    /** Joins the shared phone route immediately, without waiting for Bluetooth to come up. */
    fun connectRouteNow(preferredDeviceName: String? = null): AudioRouteResult {
        if (isClosed) return AudioRouteResult.PHONE
        return AudioSessionCoordinator.connectRouteNow(this, AudioRouteOptions(AudioRouteWait.NONE, preferredDeviceName))
    }

    /**
     * Leaves the shared route while keeping the session, for example to play an answer as normal
     * media after listening on the Bluetooth call route. The route is restored if this was its last user.
     */
    fun leaveRoute() {
        if (!isClosed) AudioSessionCoordinator.leaveRoute(this)
    }

    /**
     * Requests the focus mode of [request] (a no-op for NONE). [onChange] receives later focus
     * changes; the request is reused, so calling again re-asserts the same focus client.
     */
    fun requestFocus(onChange: ((AudioFocusChange) -> Unit)? = null): Boolean {
        if (isClosed) return false
        val lease = synchronized(this) {
            focus ?: AudioFocusLease(audioManager, request.focus, onChange).also { focus = it }
        }
        return lease.request()
    }

    val hasFocus: Boolean get() = focus?.isHeld == true

    fun abandonFocus() {
        synchronized(this) { focus.also { focus = null } }?.abandon()
    }

    /** Opens a recorder this session owns; it is closed with the session. */
    fun openRecorder(
        source: Int,
        sampleRateHz: Int,
        bufferBytes: (minBuffer: Int) -> Int,
        preferredDevice: AudioDeviceInfo? = null,
        requirePreferredDevice: Boolean = false,
    ): PcmRecorder {
        check(!isClosed) { "Audio session is closed" }
        val recorder = PcmRecorder.open(source, sampleRateHz, bufferBytes, preferredDevice, requirePreferredDevice)
        recorders += recorder
        if (isClosed) recorder.close()
        return recorder
    }

    /** Closes one recorder early (for example when the session is suspended). */
    fun closeRecorder(recorder: PcmRecorder) {
        recorders.remove(recorder)
        recorder.close()
    }

    override fun close() {
        synchronized(this) {
            if (isClosed) return
            isClosed = true
        }
        recorders.forEach(PcmRecorder::close)
        recorders.clear()
        abandonFocus()
        state = AudioSessionState.ENDED
        AudioSessionCoordinator.release(this)
    }

    internal fun deliver(newState: AudioSessionState) {
        if (isClosed) return
        val previous = state
        state = newState
        when (newState) {
            AudioSessionState.SUSPENDED -> if (previous != AudioSessionState.SUSPENDED) listener.onSuspended()
            AudioSessionState.ACTIVE -> if (previous != AudioSessionState.ACTIVE) listener.onResumed()
            AudioSessionState.ENDED -> listener.onEnded()
        }
    }
}
