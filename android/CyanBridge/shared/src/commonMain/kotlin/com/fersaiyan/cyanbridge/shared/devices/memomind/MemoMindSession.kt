package com.fersaiyan.cyanbridge.shared.devices.memomind

import com.fersaiyan.cyanbridge.shared.platform.PlatformLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.concurrent.Volatile
import kotlin.random.Random
import kotlin.time.TimeSource

/**
 * The GM command link a [MemoMindSession] talks over: Classic SPP (Android), iAP2, or BLE GATT
 * (0x2021 write / 0x2022 notify, iOS). The platform feeds received bytes to
 * [MemoMindSession.onCommandBytes] and calls [MemoMindSession.onDisconnected] when the link drops.
 */
interface MemoMindTransport {
    val isConnected: Boolean

    /** Largest physical GM frame one write may carry: 512 on SPP/iAP2, the characteristic limit on BLE. */
    val maxFrameBytes: Int

    /**
     * True on BLE: the firmware forwards each write to its parser, so the next fragment of a
     * message waits for the empty flow-control response of the previous one.
     */
    val awaitsFragmentAcks: Boolean

    /** Writes one complete physical GM frame. */
    suspend fun writeCommand(frame: ByteArray)

    /** The separate recording link (SPP 0x2024 / iAP2 protocol01); null when the platform has none (BLE). */
    val recordingLink: MemoMindRecordingLink?
}

/** The recording link. Its received bytes go to [MemoMindSession.onRecordingBytes]. */
interface MemoMindRecordingLink {
    suspend fun open()
    suspend fun write(bytes: ByteArray)
    suspend fun close()
}

/** Receives microphone audio on the link's reader thread; keep it short. */
fun interface MemoMindAudioListener {
    /** [frames] are complete 40-byte Opus packets of the capture this session started, in order. */
    fun onOpusFrames(frames: List<ByteArray>)
}

enum class MemoMindMicrophoneState { IDLE, STARTING, STREAMING, STOPPING }

data class MemoMindCaptureOptions(
    val noise: MemoMindNoiseMode = MemoMindNoiseMode.NOISE_REDUCTION,
    /** Null keeps the glasses' pickup setting (required for older firmware). */
    val pickup: MemoMindPickupMode? = null,
    /** Request the compact 40-byte record format; the glasses may answer with legacy. */
    val compact: Boolean = true,
)

data class MemoMindSessionState(
    val deviceInfo: MemoMindDeviceInfo? = null,
    val status: MemoMindDeviceStatus? = null,
    val microphone: MemoMindMicrophoneState = MemoMindMicrophoneState.IDLE,
    /** The negotiated record format of the current capture; null before negotiation. */
    val compactRecording: Boolean? = null,
    /** Opus frames delivered in the current capture. */
    val capturedFrames: Long = 0,
    /** True after a Web Bridge plugin answered a ping or sent an event; null when unknown. */
    val webBridgeRunning: Boolean? = null,
    val teleprompterActive: Boolean = false,
    val lastError: String? = null,
) {
    val batteryPercent: Int? get() = status?.batteryPercent
    val isCharging: Boolean get() = status?.isCharging == true
}

class MemoMindCommandException(val request: GmRequest, val status: Int?, message: String) : Exception(message)

/**
 * The MemoMind business session over one command link, independent of the transport: request
 * correlation by event ID, flow-controlled fragmentation, device info/status, HUD output
 * (native notification, teleprompter and translation screens, and Web Bridge drawing),
 * unsolicited events and microphone capture. GMP plugin installation is not part of the
 * published protocol and is not implemented.
 */
class MemoMindSession(private val transport: MemoMindTransport) {
    private val decoder = GmFrameDecoder()
    private val recordingParser = MemoMindRecordingParser()
    private val requestMutex = Mutex()
    private val microphoneMutex = Mutex()
    private val microphoneUsersMutex = Mutex()
    private var microphoneUser: Any? = null
    private var nextEventId = 1
    private var nextNotificationId = 1

    @Volatile
    private var pending: Pending? = null

    @Volatile
    private var formatSelected: CompletableDeferred<Boolean>? = null

    @Volatile
    private var captureStarted: CompletableDeferred<Unit>? = null

    /** The last capture start on this connection failed (no 0x2024 service, no start ACK, …). */
    @Volatile
    private var recordingFailed = false

    private val _state = MutableStateFlow(MemoMindSessionState())
    private val _events = MutableSharedFlow<MemoMindEvent>(extraBufferCapacity = 64)

    val state: StateFlow<MemoMindSessionState> = _state.asStateFlow()

    /** Unsolicited messages and plugin events; responses to requests are not repeated here. */
    val events: SharedFlow<MemoMindEvent> = _events.asSharedFlow()

    /** Receives the frames of the running capture; [joinMicrophone] sets it for its user. */
    var audioListener: MemoMindAudioListener? = null

    /**
     * A capture may be tried now: the link is up, has a recording channel (not BLE, not an iAP2
     * accessory without protocol01), and the last start on this connection did not fail (firmware
     * without the recording service, or no start acknowledgement). A successful start or a new
     * connection clears the failure. Voice questions use HFP while this is false.
     */
    val recordingAvailable: Boolean
        get() = transport.isConnected && transport.recordingLink != null && !recordingFailed

    /** Command-link frames the decoder had to skip since the last [onDisconnected]. */
    val malformedFrameCount: Int get() = decoder.malformedCount

    private class Pending(val eventId: Int, val service: Int, val command: Int) {
        val response = CompletableDeferred<GmMessage>()
        val flowControl = Channel<Unit>(Channel.UNLIMITED)
    }

    /** Feed every chunk the command link receives (one reader thread). */
    fun onCommandBytes(chunk: ByteArray) {
        decoder.append(chunk).forEach(::route)
    }

    /** Feed every chunk the recording link receives. */
    fun onRecordingBytes(chunk: ByteArray) {
        recordingParser.append(chunk).forEach { message ->
            when (message) {
                is MemoMindRecordingMessage.FormatSelected -> formatSelected?.complete(message.compact)
                is MemoMindRecordingMessage.Started -> captureStarted?.complete(Unit)
                is MemoMindRecordingMessage.Audio -> {
                    if (message.streamCommand != MemoMindRecordingParser.STREAM_NORMAL_RECORDING) return@forEach
                    captureStarted?.complete(Unit)
                    if (_state.value.microphone == MemoMindMicrophoneState.IDLE) return@forEach
                    _state.update { it.copy(capturedFrames = it.capturedFrames + message.frames.size) }
                    audioListener?.onOpusFrames(message.frames)
                }
            }
        }
    }

    /** The link dropped: partial frames, pending requests and capture state are discarded. */
    fun onDisconnected() {
        decoder.reset()
        recordingParser.reset()
        pending?.response?.completeExceptionally(IllegalStateException("MemoMind link disconnected"))
        pending = null
        formatSelected?.cancel()
        captureStarted?.cancel()
        recordingFailed = false
        _state.value = MemoMindSessionState()
    }

    /** Shows a failure from outside the session (connection, a dashboard command) in [state]. */
    fun reportError(message: String?) {
        _state.update { it.copy(lastError = message) }
    }

    /** Heartbeat, device information and status: the published bring-up after subscribing. */
    suspend fun initialize() {
        requireSuccess(request(MemoMindCommands.heartbeat()))
        refreshDeviceInfo()
        refreshStatus()
    }

    suspend fun refreshDeviceInfo(): MemoMindDeviceInfo {
        val response = request(MemoMindCommands.deviceInfo())
        val json = MemoMindEvents.jsonObject(response) ?: throw failure(MemoMindCommands.deviceInfo(), response)
        return MemoMindDeviceInfo.parse(json).also { info -> _state.update { it.copy(deviceInfo = info, lastError = null) } }
    }

    /** Battery, charging and display settings. */
    suspend fun refreshStatus(): MemoMindDeviceStatus {
        val response = request(MemoMindCommands.deviceStatus())
        val json = MemoMindEvents.jsonObject(response) ?: throw failure(MemoMindCommands.deviceStatus(), response)
        return MemoMindDeviceStatus.parse(json).also { status -> _state.update { it.copy(status = status, lastError = null) } }
    }

    /** Brightness 0..10; the display task applies it asynchronously. */
    suspend fun setBrightness(level: Int) = command(MemoMindCommands.brightness(level))

    suspend fun setAutoBrightness(enabled: Boolean) = command(MemoMindCommands.autoBrightness(enabled))

    suspend fun setScreenHeight(level: Int) = command(MemoMindCommands.screenHeight(level))

    suspend fun setOpticalDistance(level: Int) = command(MemoMindCommands.opticalDistance(level))

    suspend fun mediaControl(operation: MemoMindMediaOperation, volumePercent: Int? = null) =
        command(MemoMindCommands.mediaControl(operation, volumePercent))

    /** A native notification card; [timestampSeconds] is local wall-clock seconds (see [MemoMindCommands.notification]). */
    suspend fun showNotification(
        title: String,
        content: String,
        appName: String,
        packageName: String,
        timestampSeconds: Long,
        style: MemoMindNotificationStyle = MemoMindNotificationStyle.DETAILED,
    ) {
        val id = nextNotificationId
        nextNotificationId = if (id >= Int.MAX_VALUE) 1 else id + 1
        command(
            MemoMindCommands.notification(
                id = id,
                appName = appName.take(MAX_NOTIFICATION_FIELD),
                title = title.take(MAX_NOTIFICATION_FIELD),
                content = content.take(MAX_NOTIFICATION_CONTENT),
                packageName = packageName,
                timestampSeconds = timestampSeconds,
                style = style,
            ),
        )
    }

    /** Opens the native teleprompter (if needed) and replaces its content with [lines]. */
    suspend fun showTeleprompter(lines: List<String>, layout: MemoMindTeleprompterLayout = MemoMindTeleprompterLayout()) {
        if (!_state.value.teleprompterActive) {
            command(MemoMindCommands.teleprompterStart(layout))
            _state.update { it.copy(teleprompterActive = true) }
        }
        command(MemoMindCommands.teleprompterText(lines.ifEmpty { listOf("") }))
    }

    suspend fun stopTeleprompter() {
        _state.update { it.copy(teleprompterActive = false) }
        command(MemoMindCommands.teleprompterStop())
    }

    suspend fun startTranslation(title: String) = command(MemoMindCommands.translationStart(title))

    suspend fun showTranslation(translated: String, source: String, final: Boolean) =
        command(MemoMindCommands.translationResult(translated, source, final))

    suspend fun stopTranslation() = command(MemoMindCommands.translationStop())

    /**
     * Delivers [data] on [channel] to the running glasses plugin and returns its INT8 delivery
     * status ([MemoMindPluginStatus]; 0 = delivered, not "handled"). Replies arrive on [events].
     */
    suspend fun sendAppMessage(channel: Int, data: ByteArray): Int {
        val response = request(MemoMindCommands.appMessage(channel, data))
        return response.status ?: throw failure(MemoMindCommands.appMessage(channel, data), response)
    }

    /** Draws through the Web Bridge plugin; fails unless it accepted the message. */
    suspend fun sendWebBridge(channel: Int, data: ByteArray) {
        val status = sendAppMessage(channel, data)
        if (status != MemoMindPluginStatus.OK) {
            throw MemoMindCommandException(
                MemoMindCommands.appMessage(channel, data),
                status,
                "MemoMind plugin rejected channel $channel with status $status",
            )
        }
        _state.update { it.copy(webBridgeRunning = true) }
    }

    /** Records that the Web Bridge plugin stopped answering, so output returns to the native screens. */
    fun markWebBridgeStopped() {
        _state.update { it.copy(webBridgeRunning = false) }
    }

    /** Pings a Web Bridge compatible plugin and records whether it echoed in [MemoMindSessionState.webBridgeRunning]. */
    suspend fun probeWebBridge(timeoutMs: Long = WEB_BRIDGE_PING_TIMEOUT_MS): Boolean {
        val token = Random.nextBytes(4)
        val running = coroutineScope {
            val echo = CompletableDeferred<Unit>()
            val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
                events.collect { event ->
                    if (event is MemoMindEvent.AppMessage && event.channel == MemoMindWebBridge.CHANNEL_PING &&
                        event.data.contentEquals(token)
                    ) {
                        echo.complete(Unit)
                    }
                }
            }
            try {
                withTimeoutOrNull(timeoutMs) {
                    sendAppMessage(MemoMindWebBridge.CHANNEL_PING, token) == MemoMindPluginStatus.OK && run {
                        echo.await()
                        true
                    }
                } ?: false
            } catch (error: MemoMindCommandException) {
                false
            } finally {
                watcher.cancel()
            }
        }
        _state.update { it.copy(webBridgeRunning = running) }
        return running
    }

    /**
     * Starts the capture for [user] with [listener], or hands a running capture over to it: the
     * frames go to the user that joined last until it leaves. [leaveMicrophone] stops the capture
     * only for that user, so a user that pauses late (a web plugin suspended by a voice question)
     * cannot stop the capture the newer user took over. A running capture keeps its options.
     */
    suspend fun joinMicrophone(user: Any, listener: MemoMindAudioListener, options: MemoMindCaptureOptions = MemoMindCaptureOptions()) =
        microphoneUsersMutex.withLock {
            microphoneUser = user
            audioListener = listener
            try {
                startMicrophone(options)
            } catch (error: Throwable) {
                if (microphoneUser === user) {
                    microphoneUser = null
                    audioListener = null
                }
                throw error
            }
        }

    /** Stops the capture if [user] is the one receiving it; otherwise does nothing. */
    suspend fun leaveMicrophone(user: Any) = microphoneUsersMutex.withLock {
        if (microphoneUser !== user) return@withLock
        microphoneUser = null
        audioListener = null
        stopMicrophone()
    }

    /**
     * Starts microphone capture: GM 01/2F, then on the recording link the format negotiation
     * (800 ms, legacy when unanswered) and `52 01 00`, waiting up to 3 s for the start.
     * Opus frames of stream 0x92 then go to [audioListener]. Logs the time of each step.
     */
    suspend fun startMicrophone(options: MemoMindCaptureOptions = MemoMindCaptureOptions()) = microphoneMutex.withLock {
        val link = transport.recordingLink ?: error("This MemoMind link has no recording channel")
        if (_state.value.microphone != MemoMindMicrophoneState.IDLE) return@withLock
        _state.update { it.copy(microphone = MemoMindMicrophoneState.STARTING, capturedFrames = 0, compactRecording = null) }
        val begin = TimeSource.Monotonic.markNow()
        val steps = mutableListOf<String>()
        fun step(name: String) {
            steps += "$name=${begin.elapsedNow().inWholeMilliseconds}"
        }
        try {
            requireSuccess(request(MemoMindCommands.recordingProcessing(options.noise, options.pickup)))
            step("processing")
            recordingParser.reset()
            val format = CompletableDeferred<Boolean>().also { formatSelected = it }
            val started = CompletableDeferred<Unit>().also { captureStarted = it }
            link.open()
            step("link")
            link.write(MemoMindRecordingControl.selectFormat(options.compact))
            val compact = withTimeoutOrNull(FORMAT_NEGOTIATION_TIMEOUT_MS) { format.await() } ?: false
            step("format")
            recordingParser.compact = compact
            _state.update { it.copy(compactRecording = compact, microphone = MemoMindMicrophoneState.STREAMING) }
            link.write(MemoMindRecordingControl.start())
            withTimeoutOrNull(CAPTURE_START_TIMEOUT_MS) { started.await() }
                ?: throw IllegalStateException("MemoMind did not start recording within ${CAPTURE_START_TIMEOUT_MS / 1000} s")
            step("started")
            recordingFailed = false
            PlatformLogger.i(TAG, "Microphone streaming compact=$compact ms: ${steps.joinToString(" ")}")
        } catch (error: Throwable) {
            PlatformLogger.w(TAG, "Microphone start failed after ${begin.elapsedNow().inWholeMilliseconds} ms (${steps.joinToString(" ")}): ${error.message}")
            if (error !is CancellationException) recordingFailed = true
            _state.update { it.copy(microphone = MemoMindMicrophoneState.IDLE, lastError = error.message) }
            runCatching { link.close() }
            throw error
        } finally {
            formatSelected = null
            captureStarted = null
        }
    }

    /** Stops capture (no acknowledgement exists), drains queued tail audio, then closes the link. */
    suspend fun stopMicrophone() = microphoneMutex.withLock {
        val link = transport.recordingLink ?: return@withLock
        if (_state.value.microphone == MemoMindMicrophoneState.IDLE) return@withLock
        _state.update { it.copy(microphone = MemoMindMicrophoneState.STOPPING) }
        try {
            runCatching { link.write(MemoMindRecordingControl.stop()) }
            delay(STOP_DRAIN_MS)
        } finally {
            _state.update { it.copy(microphone = MemoMindMicrophoneState.IDLE) }
            runCatching { link.close() }
        }
    }

    /** Sends [request] and requires a successful STATUS (or plugin INT8) acknowledgement. */
    suspend fun command(request: GmRequest) {
        requireSuccess(request(request), request)
    }

    /**
     * Sends [request] and returns its response. Requests are serialized (fragments of two
     * messages never interleave); empty flow-control responses are consumed, and on BLE each
     * fragment waits for one.
     */
    suspend fun request(request: GmRequest, timeoutMs: Long = REQUEST_TIMEOUT_MS): GmMessage = requestMutex.withLock {
        check(transport.isConnected) { "MemoMind is not connected" }
        val eventId = nextEventId
        nextEventId = if (eventId >= 0xFF) 1 else eventId + 1
        val frames = GmCodec.encode(request.withEvent(eventId), transport.maxFrameBytes)
        val waiting = Pending(eventId, request.service, request.command)
        pending = waiting
        try {
            // A timeout is an ordinary failure for callers, not a cancellation of their coroutine.
            withTimeoutOrNull(timeoutMs) {
                frames.forEachIndexed { index, frame ->
                    transport.writeCommand(frame)
                    if (index < frames.lastIndex && transport.awaitsFragmentAcks) waiting.flowControl.receive()
                }
                waiting.response.await()
            } ?: throw MemoMindCommandException(
                request,
                null,
                "MemoMind 0x${request.service.toString(16)}/0x${request.command.toString(16)} timed out",
            )
        } finally {
            pending = null
            waiting.flowControl.close()
        }
    }

    private fun route(message: GmMessage) {
        val waiting = pending
        if (waiting != null && message.eventId == waiting.eventId && message.service == waiting.service &&
            message.command == waiting.command
        ) {
            if (message.isEmpty) waiting.flowControl.trySend(Unit) else waiting.response.complete(message)
            return
        }
        if (message.isEmpty) return
        val events = MemoMindEvents.decode(message)
        events.forEach { event ->
            when (event) {
                is MemoMindEvent.Button, is MemoMindEvent.HeadGesture, is MemoMindEvent.PluginConnection ->
                    if (_state.value.webBridgeRunning != true) _state.update { it.copy(webBridgeRunning = true) }
                is MemoMindEvent.Unsolicited -> PlatformLogger.i(
                    TAG,
                    "Unsolicited service=0x${message.service.toString(16)} command=0x${message.command.toString(16)} " +
                        "tlvs=${message.tlvs.map { it.type }}",
                )
                else -> Unit
            }
            _events.tryEmit(event)
        }
    }

    private fun requireSuccess(response: GmMessage, request: GmRequest? = null) {
        val status = response.status
        if (status != MemoMindStatus.SUCCESS) {
            throw failure(request ?: GmRequest(response.service, response.command), response)
        }
    }

    private fun failure(request: GmRequest, response: GmMessage): MemoMindCommandException {
        val status = response.status
        val label = "0x${request.service.toString(16)}/0x${request.command.toString(16)}"
        val detail = response.first(GmTlvType.STRING)?.text?.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
        return MemoMindCommandException(
            request,
            status,
            if (status == null) "MemoMind $label returned no status" else "MemoMind $label failed: ${MemoMindStatus.describe(status)}$detail",
        )
    }

    companion object {
        private const val TAG = "MemoMindSession"
        const val REQUEST_TIMEOUT_MS = 5_000L
        const val FORMAT_NEGOTIATION_TIMEOUT_MS = 800L
        const val CAPTURE_START_TIMEOUT_MS = 3_000L
        const val STOP_DRAIN_MS = 300L
        const val WEB_BRIDGE_PING_TIMEOUT_MS = 1_500L
        private const val MAX_NOTIFICATION_FIELD = 64
        private const val MAX_NOTIFICATION_CONTENT = 1_000
    }
}
