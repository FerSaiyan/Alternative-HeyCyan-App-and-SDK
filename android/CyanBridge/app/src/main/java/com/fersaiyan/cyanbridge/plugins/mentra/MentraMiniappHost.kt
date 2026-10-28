package com.fersaiyan.cyanbridge.plugins.mentra

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.fersaiyan.cyanbridge.audio.TtsSpeaker
import com.fersaiyan.cyanbridge.audio.TtsUtteranceOutcome
import com.fersaiyan.cyanbridge.bridge.core.BridgeEvent
import com.fersaiyan.cyanbridge.bridge.core.DisplayCommand
import com.fersaiyan.cyanbridge.bridge.core.GestureType
import com.fersaiyan.cyanbridge.bridge.core.GlassesBridge
import com.fersaiyan.cyanbridge.bridge.core.InputEvent
import com.fersaiyan.cyanbridge.devices.DeviceProfileStore
import com.fersaiyan.cyanbridge.shared.devices.GlassesFamilyProfiles
import com.fersaiyan.cyanbridge.shared.plugins.mentra.MiniappApprovals
import com.fersaiyan.cyanbridge.shared.plugins.mentra.MiniappDeviceIo
import com.fersaiyan.cyanbridge.shared.plugins.mentra.MiniappException
import com.fersaiyan.cyanbridge.shared.plugins.mentra.MiniappHardwareSupport
import com.fersaiyan.cyanbridge.shared.plugins.mentra.MiniappHostOutput
import com.fersaiyan.cyanbridge.shared.plugins.mentra.MiniappHostSession
import com.fersaiyan.cyanbridge.shared.plugins.mentra.MiniappHttpRequest
import com.fersaiyan.cyanbridge.shared.plugins.mentra.MiniappHttpResponse
import com.fersaiyan.cyanbridge.shared.plugins.mentra.MiniappPermissionPolicy
import com.fersaiyan.cyanbridge.shared.plugins.mentra.MiniappRuntimeBridge
import com.fersaiyan.cyanbridge.shared.plugins.mentra.MiniappRuntimePlatform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom

/**
 * The miniapps running in this process. A miniapp's background layer keeps running after its
 * UI closes, until the user stops it (or the process ends); the microphone foreground service
 * runs while one of them listens.
 */
object MentraMiniappHost {
    private const val TAG = "MentraMiniapps"
    private val _running = MutableStateFlow<Map<String, RunningMiniapp>>(emptyMap())

    /** Running miniapps by package name. */
    val running: StateFlow<Map<String, RunningMiniapp>> = _running.asStateFlow()

    /** Starts [app] (or returns it when it already runs). Throws [MiniappException]. */
    suspend fun start(context: Context, app: InstalledMiniapp, approvals: MiniappApprovals, store: MiniappStore): RunningMiniapp {
        _running.value[app.manifest.packageName]?.let { return it }
        if (!MiniappPermissionPolicy.canStart(app.manifest, approvals)) throw MiniappException("Permissions are missing")
        val hardware = currentHardware(context)
        val missing = hardware.missing(app.manifest)
        if (missing.isNotEmpty()) {
            throw MiniappException("${app.manifest.name} needs ${missing.joinToString { it.name.lowercase() }}, which ${hardware.modelName} does not have")
        }
        val polyfill = withContext(Dispatchers.IO) { context.assets.open(POLYFILL_ASSET).bufferedReader().use { it.readText() } }
        val bundle = withContext(Dispatchers.IO) { store.readBackground(app) }
        val running = RunningMiniapp(context.applicationContext, app, approvals, store, hardware)
        if (!running.start(polyfill, bundle)) {
            running.close()
            throw MiniappException("${app.manifest.name} failed to start: ${running.logs.value.lastOrNull().orEmpty()}")
        }
        _running.update { it + (app.manifest.packageName to running) }
        return running
    }

    fun stop(context: Context, packageName: String) {
        val running = _running.value[packageName] ?: return
        _running.update { it - packageName }
        running.close()
        transcriptionDemandChanged(context)
    }

    fun stopAll(context: Context) = _running.value.keys.forEach { stop(context, it) }

    /** Recognized speech from [MentraMiniappService], for every miniapp that listens. */
    internal fun onTranscription(text: String, isFinal: Boolean, language: String?) {
        _running.value.values.filter { it.transcriptionActive }.forEach { it.session.onTranscription(text, isFinal, language) }
    }

    /** Starts, retunes or stops the listening service to match what running miniapps subscribe to. */
    internal fun transcriptionDemandChanged(context: Context) {
        val listeners = _running.value.values.filter { it.transcriptionActive }
        if (listeners.isEmpty()) {
            MentraMiniappService.stop(context)
        } else {
            MentraMiniappService.listen(context, listeners.firstNotNullOfOrNull { it.transcriptionLanguage })
        }
    }

    fun currentHardware(context: Context): MiniappHardwareSupport =
        MiniappHardwareSupport.of(GlassesFamilyProfiles.forClass(DeviceProfileStore.selectedClass(context)))

    const val POLYFILL_ASSET = "mentra/startup.js"
    const val UI_SHIM_ASSET = "mentra/ui-shim.js"

    internal fun log(message: String) = Log.i(TAG, message)
}

/**
 * One running miniapp: its QuickJS background, the shared protocol session, and the device glue.
 * Display goes to the active glasses through [GlassesBridge] (latest frame wins, at most one
 * frame per [DISPLAY_INTERVAL_MS]); input comes from the bridge's button events; speech comes
 * from [MentraMiniappService]; `speak` uses the phone's TTS.
 */
class RunningMiniapp internal constructor(
    private val context: Context,
    val app: InstalledMiniapp,
    approvals: MiniappApprovals,
    store: MiniappStore,
    override val hardware: MiniappHardwareSupport,
) : MiniappDeviceIo, MiniappHostOutput {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val random = SecureRandom()
    private val _logs = MutableStateFlow<List<String>>(emptyList())
    private val _ready = MutableStateFlow(false)
    private val displayRequests = MutableStateFlow<DisplayRequest?>(null)
    private var displaySeq = 0L
    private var tts: TtsSpeaker? = null

    /** The last console lines and errors, for the miniapp screen. */
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    /** The background finished starting; its UI may open. */
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    /** Frames for the UI WebView while it is attached. */
    @Volatile var uiSink: ((String) -> Unit)? = null

    @Volatile var transcriptionActive = false
        private set

    @Volatile var transcriptionLanguage: String? = null
        private set

    val session = MiniappHostSession(app.manifest, approvals, this, store.keyValueStore(app), this)
    private val bridge: MiniappRuntimeBridge
    private val engine: QuickJsMiniappEngine

    init {
        engine = QuickJsMiniappEngine(
            packageName = app.manifest.packageName,
            dispatch = { iface, method, args -> bridge.dispatch(iface, method, args) },
            onLog = { level, args -> log("$level ${args.take(500)}") },
            onError = { kind, detail -> log("error($kind) ${detail.take(500)}") },
        )
        bridge = MiniappRuntimeBridge(session, store.keyValueStore(app, MiniappStore.LOCAL_STORAGE), Platform(), scope, engine::deliver)
    }

    private data class DisplayRequest(val seq: Long, val text: String?, val durationMs: Long?)

    internal suspend fun start(polyfill: String, bundle: String): Boolean {
        if (!engine.start(polyfill, bundle)) return false
        bridge.init("${app.manifest.packageName}-${System.currentTimeMillis().toString(36)}")
        scope.launch {
            delay(READY_TIMEOUT_MS)
            session.markReady()
        }
        scope.launch { pumpDisplay() }
        scope.launch {
            GlassesBridge.bridgeEvents.collect { event ->
                val input = (event as? BridgeEvent.InputReceived)?.takeIf { GlassesBridge.isActiveAdapter(it.adapterId) }?.event
                if (input is InputEvent.Button) onButton(input)
            }
        }
        return true
    }

    fun close() {
        session.close()
        engine.close()
        tts?.shutdown()
        tts = null
        uiSink = null
        scope.cancel()
    }

    // region MiniappHostOutput

    override fun toBackground(raw: String) = bridge.bridge(raw)

    override fun toUi(frameJson: String) {
        uiSink?.invoke(frameJson)
    }

    override fun onBackgroundReady() {
        _ready.value = true
    }

    override fun log(message: String) {
        MentraMiniappHost.log("${app.manifest.packageName}: $message")
        _logs.update { (it + message).takeLast(MAX_LOG_LINES) }
    }

    // endregion

    // region MiniappDeviceIo

    override fun showText(text: String, durationMs: Long?): Boolean {
        if (!hardware.display) return false
        displayRequests.value = DisplayRequest(++displaySeq, text, durationMs)
        return true
    }

    override fun clearDisplay() {
        if (hardware.display) displayRequests.value = DisplayRequest(++displaySeq, null, null)
    }

    private suspend fun pumpDisplay() {
        var clearJob: Job? = null
        displayRequests.filterNotNull().collect { request ->
            clearJob?.cancel()
            val result = if (request.text == null) GlassesBridge.clearDisplay()
            else GlassesBridge.showText(DisplayCommand.Text(request.text))
            result.onFailure { log("display: ${it.message}") }
            request.durationMs?.let { ms ->
                clearJob = scope.launch {
                    delay(ms)
                    if (displayRequests.value?.seq == request.seq) GlassesBridge.clearDisplay()
                }
            }
            delay(DISPLAY_INTERVAL_MS)
        }
    }

    override fun setTranscription(active: Boolean, language: String?) {
        transcriptionActive = active
        transcriptionLanguage = language
        MentraMiniappHost.transcriptionDemandChanged(context)
    }

    override fun speak(text: String, onDone: (Boolean) -> Unit) {
        val speaker = tts ?: TtsSpeaker(context, "MentraMiniappTts").also { tts = it }
        speaker.whenReady { ready ->
            ready.speak(text, "miniapp-${random.nextInt()}") { outcome -> onDone(outcome == TtsUtteranceOutcome.DONE) }
        }
    }

    override fun stopSpeaking() {
        tts?.stop()
    }

    override fun openUrl(url: String): Boolean = runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.isSuccess

    // endregion

    private fun onButton(event: InputEvent.Button) {
        when (event.gesture) {
            GestureType.SINGLE_TAP -> { session.onButton(event.button, "short"); session.onTouch("single_tap") }
            GestureType.LONG_PRESS -> { session.onButton(event.button, "long"); session.onTouch("long_press") }
            GestureType.DOUBLE_TAP -> session.onTouch("double_tap")
            GestureType.SWIPE_UP -> session.onTouch("swipe_up")
            GestureType.SWIPE_DOWN -> session.onTouch("swipe_down")
            // Mentra has no sideways swipe: forward/back on a temple maps to up/down, as on the G1.
            GestureType.SWIPE_LEFT -> session.onTouch("swipe_up")
            GestureType.SWIPE_RIGHT -> session.onTouch("swipe_down")
        }
    }

    /** `fetch` for the background (http/https only, capped), and random bytes for `crypto`. */
    private inner class Platform : MiniappRuntimePlatform {
        override fun randomBytes(count: Int): ByteArray = ByteArray(count).also(random::nextBytes)

        override suspend fun http(request: MiniappHttpRequest): MiniappHttpResponse = withContext(Dispatchers.IO) {
            val connection = URL(request.url).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = request.method
                connection.connectTimeout = 30_000
                connection.readTimeout = 60_000
                connection.useCaches = false
                request.headers.forEach { (k, v) -> connection.setRequestProperty(k, v) }
                request.body?.let { body ->
                    connection.doOutput = true
                    connection.outputStream.use { it.write(body) }
                }
                val status = connection.responseCode
                val stream = if (status >= 400) connection.errorStream else connection.inputStream
                val out = ByteArrayOutputStream()
                stream?.use { input ->
                    val buffer = ByteArray(32 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        if (out.size() > MiniappRuntimeBridge.MAX_FETCH_BYTES) throw MiniappException("Response too large")
                    }
                }
                val headers = connection.headerFields.entries.mapNotNull { (k, v) -> k?.let { it to v.joinToString(", ") } }.toMap()
                MiniappHttpResponse(status, connection.responseMessage.orEmpty(), headers, out.toByteArray())
            } finally {
                connection.disconnect()
            }
        }
    }

    companion object {
        /** The Mentra App opens the UI after READY or 10 s. */
        const val READY_TIMEOUT_MS = 10_000L
        /** Glasses displays take a frame about every 300 ms (Mentra's display manager throttle). */
        const val DISPLAY_INTERVAL_MS = 300L
        const val MAX_LOG_LINES = 200
    }
}
