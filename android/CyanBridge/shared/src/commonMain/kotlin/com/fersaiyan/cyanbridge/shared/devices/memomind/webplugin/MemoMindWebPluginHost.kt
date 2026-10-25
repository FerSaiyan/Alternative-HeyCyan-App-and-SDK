package com.fersaiyan.cyanbridge.shared.devices.memomind.webplugin

import com.fersaiyan.cyanbridge.shared.devices.memomind.MemoMindAudioListener
import com.fersaiyan.cyanbridge.shared.devices.memomind.MemoMindButton
import com.fersaiyan.cyanbridge.shared.devices.memomind.MemoMindButtonAction
import com.fersaiyan.cyanbridge.shared.devices.memomind.MemoMindCommandException
import com.fersaiyan.cyanbridge.shared.devices.memomind.MemoMindEvent
import com.fersaiyan.cyanbridge.shared.devices.memomind.MemoMindGesture
import com.fersaiyan.cyanbridge.shared.devices.memomind.MemoMindPluginStatus
import com.fersaiyan.cyanbridge.shared.devices.memomind.MemoMindWebBridge
import com.fersaiyan.cyanbridge.shared.devices.memomind.webplugin.WebPluginContract.Permission
import com.fersaiyan.cyanbridge.shared.platform.PlatformLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/** The platform WebView side of one plugin page; every call comes from the host's dispatcher. */
interface WebPluginPage {
    /** Calls `window.__memoPluginBootstrap(sessionToken, runtimeGeneration)` in the main frame. */
    fun bootstrap(sessionToken: String, runtimeGeneration: Long)

    /** Calls `window.__memoPluginResolve(response)`. */
    fun resolve(responseJson: String)

    /** Calls `window.__memoPluginEmit(event)`. */
    fun emit(eventJson: String)

    /**
     * Creates a message channel, posts `{type: "gm-plugin:stream-port", descriptor}` with one
     * end to the page origin and returns the other; null when binary ports are unsupported.
     * [onMessage] receives the page's text messages (`pull`, `cancel`).
     */
    fun openStreamPort(announcementJson: String, onMessage: (String) -> Unit): WebPluginStreamPort?
}

enum class WebPluginLifecycle(val wire: String) {
    STARTING("starting"),
    RUNNING("running"),
    SUSPENDED("suspended"),
    STOPPED("stopped"),
    FAILED("failed"),
}

/**
 * The App side of Bridge 2.0 for one running Web plugin, over a [WebPluginDevice] (MemoMind).
 *
 * The page talks JSON (`MemoPluginBridge.postMessage(request)`); the host validates the
 * envelope (size, version, session token, runtime generation), authorizes the method against
 * the manifest declarations and the user's [approvals], validates every argument, and maps
 * the call onto the glasses: display calls to Web Bridge scene channels 1/2/6/7/8/9 (or the
 * native teleprompter when no Web Bridge plugin runs), `plugin.sendMessage` to GM application
 * messages, `audio.openCapture` to the Opus microphone over a binary stream port, and glasses
 * input and plugin messages back as events. `files.*` and `location.*` are registered but
 * unavailable on this host.
 *
 * Not thread-safe: [scope] must be single-threaded (the UI dispatcher), and the platform must
 * call every entry point from it.
 */
class MemoMindWebPluginHost(
    private val manifest: MmpkgManifest,
    private val approvals: WebPluginApprovals,
    private val device: WebPluginDevice,
    private val storage: WebPluginStorage,
    private val page: WebPluginPage,
    private val scope: CoroutineScope,
    /** At least 128 random bits from a cryptographic source, as text. */
    private val secureToken: () -> String,
    private val clock: () -> Long,
    /** The WebView can transfer binary message ports (needed for audio). */
    private val audioPortsSupported: Boolean,
) {
    private val declarations = manifest.permissions
    private var sessionToken: String? = null
    private var generation = 0L
    private var foreground = true
    var lifecycle = WebPluginLifecycle.STARTING
        private set

    private val _microphoneActive = MutableStateFlow(false)

    /** The plugin holds the glasses microphone (the platform shows a recording indicator). */
    val microphoneActive: StateFlow<Boolean> = _microphoneActive.asStateFlow()

    private val subscriptions = linkedMapOf<String, Set<String>>()
    private var subscriptionSequence = 0
    private var eventSequence = 0L
    private var frame: ActiveFrame? = null
    private var pendingFrameAck: PendingFrameAck? = null
    private val fallbackTexts = mutableMapOf<Int, Pair<Int, String>>()
    private var capture: WebPluginAudioCapture? = null
    private var microphone: WebPluginMicrophone? = null
    private var captureSequence = 0
    private val audioFrames = Channel<Pair<String, List<ByteArray>>>(Channel.UNLIMITED)
    private var inFlight = 0
    private var windowStartMs = 0L
    private var windowCount = 0
    private val jobs = mutableListOf<Job>()

    private class ActiveFrame(val frameId: Long, val tileCount: Int, var nextIndex: Int)
    private class PendingFrameAck(val frameId: Long, val tileIndex: Int) {
        val status = CompletableDeferred<MemoMindEvent.FrameStatus>()
    }

    /** Starts listening to the glasses; probes the Web Bridge plugin on start and reconnect while its state is unknown. */
    fun start() {
        if (jobs.isNotEmpty()) return
        jobs += scope.launch { device.events.collect(::onDeviceEvent) }
        jobs += scope.launch {
            device.connected.drop(1).collect { connected ->
                if (!connected) failFrame()
                if (connected && device.webBridgeRunning != true) launch { probeWebBridge() }
                emitDevice("device.connection", "connection", buildJsonObject { put("connected", connected); put("source", "link") })
            }
        }
        jobs += scope.launch {
            for ((sessionId, frames) in audioFrames) onAudioFrames(sessionId, frames)
        }
        if (device.connected.value && device.webBridgeRunning != true) {
            jobs += scope.launch { probeWebBridge() }
        }
    }

    private suspend fun probeWebBridge() {
        runCatching { device.probeWebBridge() }.onFailure(::rethrowCancellation)
    }

    /**
     * A new document started loading: the previous runtime and everything it held is dropped,
     * and the new one runs at once (page scripts may call the Bridge before the load event).
     */
    fun onPageStarted() {
        endRuntime(WebPluginErrorCode.RUNTIME_REPLACED)
        generation++
        sessionToken = secureToken()
        lifecycle = WebPluginLifecycle.STARTING
        setLifecycle(if (foreground) WebPluginLifecycle.RUNNING else WebPluginLifecycle.SUSPENDED)
    }

    /** The document finished loading: deliver the bootstrap in case the SDK did not ask for it. */
    fun onPageLoaded() {
        deliverBootstrap()
    }

    /** Text from `MemoPluginBridge.postMessage` of the plugin's main frame. */
    fun onMessage(raw: String) {
        if (raw.length <= BOOTSTRAP_REQUEST.length + 8 && raw.replace(" ", "") == BOOTSTRAP_REQUEST) {
            deliverBootstrap()
            return
        }
        val token = sessionToken ?: return
        scope.launch {
            val response = handle(raw)
            if (sessionToken == token) page.resolve(response)
        }
    }

    /** The screen went to the background or came back; background suspends events and stops audio. */
    fun setForeground(visible: Boolean) {
        foreground = visible
        if (lifecycle == WebPluginLifecycle.STOPPED) return
        if (!visible) {
            capture?.let { finishCapture(it, emitError = WebPluginErrorCode.RUNTIME_CLOSED, reason = "suspended") }
            failFrame()
            setLifecycle(WebPluginLifecycle.SUSPENDED)
        } else if (sessionToken != null) {
            setLifecycle(WebPluginLifecycle.RUNNING)
        }
    }

    /** The runtime closes (screen finished); nothing is delivered afterwards. */
    fun close() {
        endRuntime(WebPluginErrorCode.RUNTIME_CLOSED)
        setLifecycle(WebPluginLifecycle.STOPPED)
        sessionToken = null
        jobs.forEach { it.cancel() }
        jobs.clear()
        audioFrames.close()
    }

    /** Handles one Bridge request and returns the response JSON (also used directly by tests). */
    suspend fun handle(raw: String): String {
        val generationAtStart = generation
        var requestId: String = INVALID_REQUEST_ID
        return try {
            val request = parseEnvelope(raw)
            requestId = request.requestId
            if (!admit()) bridgeError(WebPluginErrorCode.RATE_LIMITED, "Too many Bridge requests")
            inFlight++
            try {
                val result = dispatch(request.method, request.params)
                if (generation != generationAtStart) bridgeError(WebPluginErrorCode.STALE_RUNTIME, "Runtime generation is stale")
                response(requestId, generationAtStart) { put("ok", true); put("result", result) }
            } finally {
                inFlight--
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            val (code, message) = describe(error)
            response(requestId, generationAtStart) {
                put("ok", false)
                put("error", buildJsonObject { put("code", code.name); put("message", message) })
            }
        }
    }

    private class Request(val requestId: String, val method: String, val params: JsonObject)

    private fun parseEnvelope(raw: String): Request {
        if (raw.length > WebPluginContract.MAX_REQUEST_BYTES || raw.encodeToByteArray().size > WebPluginContract.MAX_REQUEST_BYTES) {
            bridgeError(WebPluginErrorCode.PAYLOAD_TOO_LARGE, "Bridge request exceeds ${WebPluginContract.MAX_REQUEST_BYTES} bytes")
        }
        val envelope = runCatching { json.parseToJsonElement(raw) }.getOrNull() as? JsonObject
            ?: invalidRequest("Bridge request fields are invalid")
        val requestId = envelope.string("requestId")?.takeIf { it.length in 1..128 }
        val method = envelope.string("method")?.takeIf { it.length in 1..128 }
        val params = envelope["params"] as? JsonObject
        if (envelope.string("version") != WebPluginContract.BRIDGE_VERSION || requestId == null || method == null || params == null) {
            invalidRequest("Bridge request fields are invalid")
        }
        val token = sessionToken
        if (token == null || envelope.string("sessionToken") != token) bridgeError(WebPluginErrorCode.UNAUTHORIZED, "Session token does not match")
        if ((envelope["runtimeGeneration"] as? JsonPrimitive)?.longOrNull != generation) {
            bridgeError(WebPluginErrorCode.STALE_RUNTIME, "Runtime generation is stale")
        }
        if (method !in WebPluginContract.METHOD_PERMISSIONS) bridgeError(WebPluginErrorCode.METHOD_NOT_FOUND, "METHOD_NOT_FOUND")
        return Request(requestId, method, params)
    }

    private fun admit(): Boolean {
        val now = clock()
        if (now - windowStartMs >= RATE_WINDOW_MS) {
            windowStartMs = now
            windowCount = 0
        }
        windowCount++
        return windowCount <= MAX_REQUESTS_PER_WINDOW && inFlight < MAX_IN_FLIGHT
    }

    private suspend fun dispatch(method: String, params: JsonObject): JsonElement {
        WebPluginPolicy.authorize(method, declarations, approvals)
        if (lifecycle != WebPluginLifecycle.RUNNING && !method.startsWith("runtime.")) {
            bridgeError(WebPluginErrorCode.PERMISSION_DENIED, "NOT_FOREGROUND")
        }
        if (method in WebPluginContract.UNSUPPORTED_METHODS) {
            bridgeError(WebPluginErrorCode.CAPABILITY_UNAVAILABLE, "$method is not available in CyanBridge")
        }
        return when (method) {
            "runtime.ready", "runtime.ping" -> buildJsonObject { put("ready", true); put("generation", generation) }
            "runtime.getBridgeVersion" -> buildJsonObject { put("version", WebPluginContract.BRIDGE_VERSION) }
            "runtime.getCapabilities" -> capabilities()
            "runtime.getLifecycleState" -> buildJsonObject { put("state", lifecycle.wire) }
            "storage.get" -> buildJsonObject { put("value", storage.get(params.requireString("key")) ?: JsonNull) }
            "storage.set" -> {
                storage.set(params.requireString("key"), params["value"] ?: JsonNull)
                buildJsonObject { put("stored", true) }
            }
            "storage.remove" -> {
                storage.remove(params.requireString("key"))
                buildJsonObject { put("removed", true) }
            }
            "storage.clear" -> {
                storage.clear()
                buildJsonObject { put("cleared", true) }
            }
            "display.createPage" -> {
                requireConnected()
                buildJsonObject { put("created", true) }
            }
            "display.closePage" -> {
                requireConnected()
                clearPage()
                buildJsonObject { put("closed", true) }
            }
            "display.updateText" -> presentText(TextElement.parse(params))
            "display.updateImage" -> presentImage(params)
            "display.updateImageLz4" -> presentImageLz4(params)
            "display.rebuildPage" -> rebuildPage(params)
            "display.beginFrame" -> beginFrame(params)
            "display.updateFrameImageLz4" -> presentFrameTile(params)
            "device.getInfo" -> deviceInfo()
            "device.subscribeEvents" -> subscribe(params)
            "device.unsubscribeEvents" -> buildJsonObject {
                put("removed", subscriptions.remove(params.requireString("subscriptionId")) != null)
            }
            "plugin.sendMessage" -> sendPluginMessage(params)
            "audio.openCapture" -> openCapture(params)
            "audio.stopCapture" -> stopCapture(params)
            else -> bridgeError(WebPluginErrorCode.METHOD_NOT_FOUND, "Bridge method is not supported")
        }
    }

    // region Runtime

    private fun capabilities(): JsonObject = buildJsonObject {
        val audio = audioAvailable()
        put(
            "methods",
            buildJsonArray {
                WebPluginPolicy.grantedMethods(approvals)
                    .filter { it !in WebPluginContract.UNSUPPORTED_METHODS && (audio || !it.startsWith("audio.")) }
                    .forEach { add(JsonPrimitive(it)) }
            },
        )
        put("storage", approvals.has(Permission.STORAGE))
        put(
            "display",
            buildJsonArray {
                if (approvals.has(Permission.DISPLAY)) {
                    val modes = if (device.webBridgeRunning == true) WEB_BRIDGE_DISPLAY else FALLBACK_DISPLAY
                    modes.forEach { add(JsonPrimitive(it)) }
                }
            },
        )
        put("events", buildJsonArray { approvals.scope(Permission.DEVICE_EVENTS).forEach { add(JsonPrimitive(it)) } })
        put("rawImuDefaultEnabled", false)
        put("audioPlayback", approvals.has(Permission.AUDIO_PLAYBACK))
        put("network", approvals.has(Permission.NETWORK))
        if (approvals.has(Permission.DEVICE_MESSAGING)) {
            put(
                "pluginMessaging",
                buildJsonObject {
                    put("maxPayloadBytes", WebPluginContract.MAX_PAYLOAD_BYTES)
                    put("uplinkEvent", "plugin.message")
                    put(
                        "channels",
                        buildJsonArray {
                            approvals.scope(Permission.DEVICE_MESSAGING).mapNotNull(String::toIntOrNull)
                                .filter { WebPluginPolicy.channelAllowed(it, declarations, approvals) }
                                .forEach { add(JsonPrimitive(it)) }
                        },
                    )
                },
            )
        }
        if (audio) put("audio", WebPluginAudioOptions.capability())
        put(
            "device",
            buildJsonObject {
                put("profile", WebPluginContract.DEVICE_PROFILE_ID)
                put("width", WebPluginContract.DISPLAY_WIDTH)
                put("height", WebPluginContract.DISPLAY_HEIGHT)
                put("refreshHz", 30)
                put("pixelFormat", "GRAY_4")
                put("webBridgeRunning", device.webBridgeRunning == true)
            },
        )
    }

    private fun audioAvailable() = audioPortsSupported && approvals.has(Permission.AUDIO_CAPTURE)

    private fun deliverBootstrap() {
        val token = sessionToken ?: return
        page.bootstrap(token, generation)
    }

    private fun setLifecycle(state: WebPluginLifecycle) {
        if (lifecycle == state) return
        lifecycle = state
        emit("runtime.lifecycleChanged", buildJsonObject { put("state", state.wire) })
    }

    private fun endRuntime(code: WebPluginErrorCode) {
        capture?.let { finishCapture(it, emitError = code, reason = "runtime") }
        failFrame()
        subscriptions.clear()
        fallbackTexts.clear()
    }

    // endregion

    // region Display

    private class TextElement(val id: Int, val region: Region, val border: Int, val radius: Int, val text: String) {
        companion object {
            fun parse(params: JsonObject): TextElement {
                val text = params.requireString("text")
                return TextElement(
                    id = params.requireInt("id").also { if (it !in 0..255) invalidRequest("id must be 0..255") },
                    region = Region.parse(params),
                    border = params.optionalInt("border", 0).also { if (it !in 0..255) invalidRequest("border must be 0..255") },
                    radius = params.optionalInt("radius", 0).also { if (it !in 0..255) invalidRequest("radius must be 0..255") },
                    text = text,
                )
            }
        }
    }

    private class Region(val x: Int, val y: Int, val width: Int, val height: Int) {
        companion object {
            fun parse(params: JsonObject): Region {
                val region = Region(params.requireInt("x"), params.requireInt("y"), params.requireInt("width"), params.requireInt("height"))
                if (region.x < 0 || region.y < 0 || region.width < 1 || region.height < 1 ||
                    region.x + region.width > WebPluginContract.DISPLAY_WIDTH || region.y + region.height > WebPluginContract.DISPLAY_HEIGHT
                ) {
                    invalidRequest("The region must lie inside the 600x350 display")
                }
                return region
            }
        }
    }

    private class Bitmap(val region: Region, val stride: Int, val data: ByteArray, val decodedSize: Int)

    private suspend fun presentText(element: TextElement): JsonObject {
        requireConnected()
        val payload = MemoMindWebBridge.text(
            element.id, element.region.x, element.region.y, element.region.width, element.region.height,
            element.text, element.border, element.radius,
        )
        requirePayload(payload.size, "Channel 2 text")
        if (device.webBridgeRunning != true) {
            fallbackTexts[element.id] = element.region.y to element.text
            device.showFallbackText(fallbackLines())
            return buildJsonObject { put("presented", true); put("fallback", "teleprompter") }
        }
        sendScene(MemoMindWebBridge.CHANNEL_TEXT, payload)
        return presented(MemoMindWebBridge.CHANNEL_TEXT, "text", payload.size, payload.size - TEXT_HEADER)
    }

    private suspend fun presentImage(params: JsonObject): JsonObject {
        val bitmap = rawBitmap(params)
        requireWebBridge()
        val payload = MemoMindWebBridge.bitmap(bitmap.region.x, bitmap.region.y, bitmap.region.width, bitmap.region.height, bitmap.stride, bitmap.data)
        sendScene(MemoMindWebBridge.CHANNEL_BITMAP, payload)
        return presented(MemoMindWebBridge.CHANNEL_BITMAP, "gray4", payload.size, bitmap.data.size)
    }

    private fun rawBitmap(params: JsonObject): Bitmap {
        val region = Region.parse(params)
        val stride = params.requireInt("stride")
        if (stride < (region.width + 1) / 2 || stride > 0xFFFF) invalidRequest("stride must be at least ${(region.width + 1) / 2}")
        val pixels = decodeImage(params)
        if (pixels.size != stride * region.height) invalidRequest("pixel length must equal stride * height (${stride * region.height})")
        requirePayload(pixels.size + BITMAP_HEADER, "Channel 6 GRAY_4")
        return Bitmap(region, stride, pixels, pixels.size)
    }

    private fun lz4Bitmap(params: JsonObject, header: Int): Bitmap {
        val region = Region.parse(params)
        val stride = params.requireInt("stride")
        if (stride != (region.width + 1) / 2) invalidRequest("stride must equal ${(region.width + 1) / 2} for LZ4 width ${region.width}")
        val decodedSize = params.requireInt("decodedSize")
        if (decodedSize != stride * region.height) invalidRequest("decodedSize must equal stride * height (${stride * region.height})")
        if (decodedSize > WebPluginContract.MAX_PAYLOAD_BYTES) {
            bridgeError(WebPluginErrorCode.PAYLOAD_TOO_LARGE, "Decoded LZ4 bitmap exceeds ${WebPluginContract.MAX_PAYLOAD_BYTES} bytes; split it into tiles")
        }
        val compressed = decodeImage(params)
        if (compressed.isEmpty()) invalidRequest("compressed LZ4 block must not be empty")
        requirePayload(compressed.size + header, "LZ4 bitmap")
        return Bitmap(region, stride, compressed, decodedSize)
    }

    private suspend fun presentImageLz4(params: JsonObject): JsonObject {
        val bitmap = lz4Bitmap(params, LZ4_HEADER)
        requireWebBridge()
        val payload = geometry(bitmap) + u32(bitmap.decodedSize.toLong()) + bitmap.data
        sendScene(MemoMindWebBridge.CHANNEL_BITMAP_LZ4, payload)
        return presented(MemoMindWebBridge.CHANNEL_BITMAP_LZ4, "gray4-lz4", payload.size, bitmap.decodedSize)
    }

    private suspend fun beginFrame(params: JsonObject): JsonObject {
        val frameId = params.requireUInt32("frameId")
        val tileCount = params.requireInt("tileCount")
        if (tileCount !in 1..WebPluginContract.MAX_FRAME_TILES) invalidRequest("tileCount must be between 1 and ${WebPluginContract.MAX_FRAME_TILES}")
        requireWebBridge()
        frame = null
        val status = sendFrameMessage(MemoMindWebBridge.CHANNEL_FRAME_BEGIN, u32(frameId) + u16(tileCount), frameId, FRAME_BEGIN_INDEX)
        frame = ActiveFrame(frameId, tileCount, status.nextIndex)
        return buildJsonObject {
            put("frameId", frameId)
            put("tileIndex", FRAME_BEGIN_INDEX)
            put("nextIndex", status.nextIndex)
            put("complete", false)
        }
    }

    private suspend fun presentFrameTile(params: JsonObject): JsonObject {
        val frameId = params.requireUInt32("frameId")
        val tileIndex = params.requireInt("tileIndex")
        val active = frame?.takeIf { it.frameId == frameId } ?: invalidRequest("frame is not active")
        if (tileIndex != active.nextIndex || tileIndex >= active.tileCount) invalidRequest("tileIndex must equal ${active.nextIndex}")
        val bitmap = lz4Bitmap(params, FRAME_TILE_HEADER)
        requireWebBridge()
        val payload = u32(frameId) + u16(tileIndex) + geometry(bitmap) + u32(bitmap.decodedSize.toLong()) + bitmap.data
        val status = sendFrameMessage(MemoMindWebBridge.CHANNEL_FRAME_TILE_LZ4, payload, frameId, tileIndex)
        active.nextIndex = status.nextIndex
        if (status.complete) frame = null
        return buildJsonObject {
            put("frameId", frameId)
            put("tileIndex", tileIndex)
            put("nextIndex", status.nextIndex)
            put("complete", status.complete)
            put("transport", transport(MemoMindWebBridge.CHANNEL_FRAME_TILE_LZ4, "atomic-framed-lz4", payload.size, bitmap.decodedSize))
        }
    }

    /** Sends a channel 8/9 message and waits for its 0x0104 frame status from the Web Bridge plugin. */
    private suspend fun sendFrameMessage(channel: Int, payload: ByteArray, frameId: Long, tileIndex: Int): MemoMindEvent.FrameStatus {
        val waiting = PendingFrameAck(frameId, tileIndex)
        pendingFrameAck = waiting
        try {
            sendScene(channel, payload)
            val status = withTimeoutOrNull(FRAME_ACK_TIMEOUT_MS) { waiting.status.await() }
                ?: run {
                    frame = null
                    bridgeError(WebPluginErrorCode.TIMEOUT, "The glasses did not acknowledge frame $frameId")
                }
            if (status.status != 0) {
                frame = null
                bridgeError(
                    if (status.status == FRAME_STATUS_LZ4_UNAVAILABLE) WebPluginErrorCode.CAPABILITY_UNAVAILABLE else WebPluginErrorCode.INTERNAL_ERROR,
                    "The glasses rejected frame $frameId tile $tileIndex (status ${status.status})",
                )
            }
            return status
        } finally {
            if (pendingFrameAck === waiting) pendingFrameAck = null
        }
    }

    private fun failFrame() {
        frame = null
        pendingFrameAck?.status?.completeExceptionally(
            WebPluginBridgeException(WebPluginErrorCode.DEVICE_DISCONNECTED, "The frame transfer was interrupted"),
        )
        pendingFrameAck = null
    }

    private suspend fun rebuildPage(params: JsonObject): JsonObject {
        val operations = params["operations"] as? JsonArray
        if (operations == null || operations.size > WebPluginContract.MAX_REBUILD_OPERATIONS) invalidRequest("operations must be a bounded list")
        val parsed = operations.map { element ->
            val operation = element as? JsonObject ?: invalidRequest("Unknown rebuild operation")
            when (operation.string("type")) {
                "text" -> TextElement.parse(operation)
                "image" -> rawBitmap(operation)
                else -> invalidRequest("Unknown rebuild operation")
            }
        }
        requireConnected()
        if (device.webBridgeRunning != true) {
            if (parsed.any { it is Bitmap }) requireWebBridge()
            fallbackTexts.clear()
            parsed.filterIsInstance<TextElement>().forEach { fallbackTexts[it.id] = it.region.y to it.text }
            if (fallbackTexts.isEmpty()) device.clearFallback() else device.showFallbackText(fallbackLines())
            return buildJsonObject { put("presented", true); put("operationCount", parsed.size); put("fallback", "teleprompter") }
        }
        sendScene(MemoMindWebBridge.CHANNEL_CLEAR, MemoMindWebBridge.clear())
        val transports = parsed.map { operation ->
            when (operation) {
                is TextElement -> presentText(operation)["transport"] ?: JsonNull
                is Bitmap -> {
                    val payload = MemoMindWebBridge.bitmap(
                        operation.region.x, operation.region.y, operation.region.width, operation.region.height, operation.stride, operation.data,
                    )
                    sendScene(MemoMindWebBridge.CHANNEL_BITMAP, payload)
                    transport(MemoMindWebBridge.CHANNEL_BITMAP, "gray4", payload.size, operation.data.size)
                }
                else -> JsonNull
            }
        }
        return buildJsonObject {
            put("presented", true)
            put("operationCount", parsed.size)
            put("transports", JsonArray(transports))
        }
    }

    private suspend fun clearPage() {
        fallbackTexts.clear()
        if (device.webBridgeRunning == true) {
            frame = null
            sendScene(MemoMindWebBridge.CHANNEL_CLEAR, MemoMindWebBridge.clear())
        } else {
            device.clearFallback()
        }
    }

    private fun fallbackLines(): List<String> =
        fallbackTexts.entries.sortedWith(compareBy({ it.value.first }, { it.key })).flatMap { it.value.second.lines() }

    private suspend fun sendScene(channel: Int, payload: ByteArray) {
        requireConnected()
        checkStatus(device.sendAppMessage(channel, payload), channel)
    }

    private fun requireWebBridge() {
        requireConnected()
        if (device.webBridgeRunning != true) {
            bridgeError(
                WebPluginErrorCode.CAPABILITY_UNAVAILABLE,
                "The Web Bridge glasses plugin is not running; start it from the official MemoMind app",
            )
        }
    }

    private fun presented(channel: Int, codec: String, payloadBytes: Int, decodedBytes: Int) = buildJsonObject {
        put("presented", true)
        put("transport", transport(channel, codec, payloadBytes, decodedBytes))
    }

    private fun transport(channel: Int, codec: String, payloadBytes: Int, decodedBytes: Int) = buildJsonObject {
        put("channel", channel)
        put("codec", codec)
        put("payloadBytes", payloadBytes)
        put("decodedBytes", decodedBytes)
        put("maxPayloadBytes", WebPluginContract.MAX_PAYLOAD_BYTES)
    }

    private fun geometry(bitmap: Bitmap) =
        u16(bitmap.region.x) + u16(bitmap.region.y) + u16(bitmap.region.width) + u16(bitmap.region.height) + u16(bitmap.stride)

    @OptIn(ExperimentalEncodingApi::class)
    private fun decodeImage(params: JsonObject): ByteArray {
        val encoded = params.requireString("dataBase64")
        if (encoded.length > WebPluginContract.MAX_IMAGE_BASE64_CHARACTERS) {
            bridgeError(WebPluginErrorCode.PAYLOAD_TOO_LARGE, "image base64 exceeds ${WebPluginContract.MAX_IMAGE_BASE64_CHARACTERS} characters")
        }
        return decodeBase64(encoded)
    }

    // endregion

    // region Device, events and messages

    private fun deviceInfo(): JsonObject {
        val info = device.deviceInfo
        // Serial number and MAC address are deliberately not exposed to plugins.
        return buildJsonObject {
            put("connected", device.connected.value)
            put("transport", "bluetooth")
            put("profile", WebPluginContract.DEVICE_PROFILE_ID)
            put("model", info?.model)
            put("name", info?.name)
            put("firmwareVersion", info?.firmwareVersion)
            put("batteryPercent", device.batteryPercent)
            put("webBridgeRunning", device.webBridgeRunning == true)
        }
    }

    private fun subscribe(params: JsonObject): JsonObject {
        val types = (params["types"] as? JsonArray)?.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
        if (types == null || types.isEmpty() || types.toSet().size != types.size || types.any { it !in WebPluginContract.EVENT_TYPES }) {
            invalidRequest("INVALID_EVENT_TYPES")
        }
        val valid = types.filterNotNull()
        WebPluginPolicy.authorize("device.subscribeEvents", declarations, approvals, eventTypes = valid)
        if (subscriptions.size >= WebPluginContract.MAX_SUBSCRIPTIONS) bridgeError(WebPluginErrorCode.QUOTA_EXCEEDED, "Event subscription limit reached")
        val id = "sub-${++subscriptionSequence}"
        subscriptions[id] = valid.toSet()
        if ("connection" in valid) {
            scope.launch {
                emitDevice("device.connection", "connection", buildJsonObject { put("connected", device.connected.value); put("source", "link") })
            }
        }
        return buildJsonObject { put("subscriptionId", id) }
    }

    @OptIn(ExperimentalEncodingApi::class)
    private suspend fun sendPluginMessage(params: JsonObject): JsonObject {
        val channel = (params["channel"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull?.takeIf { it in 0..0xFFFF }
            ?: invalidRequest("INVALID_CHANNEL")
        WebPluginPolicy.authorize("plugin.sendMessage", declarations, approvals, channel = channel)
        val encoded = (params["dataBase64"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: invalidRequest("dataBase64 must be non-empty valid base64")
        if (encoded.length > MAX_MESSAGE_BASE64) bridgeError(WebPluginErrorCode.PAYLOAD_TOO_LARGE, "plugin message exceeds ${WebPluginContract.MAX_PAYLOAD_BYTES} bytes")
        val data = decodeBase64(encoded)
        if (data.isEmpty()) invalidRequest("dataBase64 must be non-empty valid base64")
        if (data.size > WebPluginContract.MAX_PAYLOAD_BYTES) bridgeError(WebPluginErrorCode.PAYLOAD_TOO_LARGE, "plugin message exceeds ${WebPluginContract.MAX_PAYLOAD_BYTES} bytes")
        requireConnected()
        checkStatus(device.sendAppMessage(channel, data), channel)
        return buildJsonObject {
            put("sent", true)
            put("channel", channel)
            put("payloadBytes", data.size)
        }
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun onDeviceEvent(event: MemoMindEvent) {
        when (event) {
            is MemoMindEvent.FrameStatus -> pendingFrameAck?.takeIf { it.frameId == event.frameId && it.tileIndex == event.tileIndex }
                ?.status?.complete(event)
            is MemoMindEvent.AppMessage -> {
                if (event.data.isEmpty() || event.data.size > WebPluginContract.MAX_PAYLOAD_BYTES) return
                if (!WebPluginPolicy.channelAllowed(event.channel, declarations, approvals)) return
                emit("plugin.message", buildJsonObject { put("channel", event.channel); put("dataBase64", Base64.encode(event.data)) })
            }
            is MemoMindEvent.Button -> emitDevice(
                "device.button",
                "button",
                buildJsonObject { put("button", buttonName(event.button)); put("action", actionName(event.action)); put("source", "glasses") },
            )
            is MemoMindEvent.HeadGesture -> gestureName(event.gesture)?.let { gesture ->
                emitDevice("device.imuGesture", "imuGesture", buildJsonObject { put("gesture", gesture); put("active", event.active); put("source", "glasses") })
            }
            is MemoMindEvent.RawImu -> emitDevice(
                "device.rawImu",
                "rawImu",
                buildJsonObject {
                    put("accel", buildJsonArray { event.accel.forEach { add(JsonPrimitive(it)) } })
                    put("gyro", buildJsonArray { event.gyro.forEach { add(JsonPrimitive(it)) } })
                    put("temperature", event.temperature)
                    put("pitchDegrees", event.pitchDegrees)
                    put("source", "glasses")
                },
            )
            is MemoMindEvent.PluginConnection ->
                emitDevice("device.connection", "connection", buildJsonObject { put("connected", event.connected); put("source", "webBridge") })
            else -> Unit
        }
    }

    private fun emitDevice(name: String, type: String, data: JsonObject) {
        val ids = subscriptions.filterValues { type in it }.keys
        if (ids.isEmpty()) return
        emit(name, data, ids.toList())
    }

    private fun emit(name: String, data: JsonObject, subscriptionIds: List<String> = emptyList()) {
        if (sessionToken == null) return
        if (name != "runtime.lifecycleChanged" && lifecycle != WebPluginLifecycle.RUNNING) return
        val event = buildJsonObject {
            put("name", name)
            put("subscriptionIds", buildJsonArray { subscriptionIds.forEach { add(JsonPrimitive(it)) } })
            put(
                "data",
                buildJsonObject {
                    put("sequence", ++eventSequence)
                    put("timestampMs", clock())
                    data.forEach { (key, value) -> put(key, value) }
                },
            )
            put("runtimeGeneration", generation)
        }
        page.emit(event.toString())
    }

    // endregion

    // region Audio

    private suspend fun openCapture(params: JsonObject): JsonObject {
        val options = WebPluginAudioOptions.parse(params)
        if (!audioPortsSupported) bridgeError(WebPluginErrorCode.CAPABILITY_UNAVAILABLE, "This WebView cannot stream binary audio")
        if (capture != null) bridgeError(WebPluginErrorCode.AUDIO_BUSY, "A capture is already open")
        requireConnected()
        val sessionId = "capture-${++captureSequence}-${secureToken().take(8)}"
        val runtime = generation
        val listener = MemoMindAudioListener { frames -> audioFrames.trySend(sessionId to frames.map { it.copyOf() }) }
        val opened = device.openMicrophone(options.captureOptions, listener)
        if (generation != runtime || lifecycle != WebPluginLifecycle.RUNNING) {
            opened.close()
            bridgeError(WebPluginErrorCode.STALE_RUNTIME, "Runtime generation is stale")
        }
        val descriptor = buildJsonObject {
            put("id", secureToken())
            put("kind", WebPluginContract.AUDIO_STREAM_KIND)
            put("sessionId", sessionId)
            put("runtimeGeneration", runtime)
        }
        var opening: WebPluginAudioCapture? = null
        val port = page.openStreamPort(
            buildJsonObject { put("type", WebPluginContract.STREAM_PORT_MESSAGE_TYPE); put("descriptor", descriptor) }.toString(),
        ) { text -> scope.launch { opening?.let { onPortMessage(it, text) } } }
        if (port == null) {
            opened.close()
            bridgeError(WebPluginErrorCode.CAPABILITY_UNAVAILABLE, "This WebView cannot stream binary audio")
        }
        val active = WebPluginAudioCapture(sessionId, options, port, clock)
        opening = active
        capture = active
        microphone = opened
        _microphoneActive.value = true
        emitCaptureState(sessionId, "capturing")
        return buildJsonObject {
            put("sessionId", sessionId)
            put("resolvedOptions", options.toJson())
            put("streamDescriptor", descriptor)
        }
    }

    private fun onPortMessage(active: WebPluginAudioCapture, text: String) {
        if (capture !== active && !active.stopping) return
        val type = (runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonObject)?.string("type")
        when (type) {
            "pull" -> active.onPull()
            "cancel" -> {
                active.cancel()
                if (capture === active) scope.launch { stopActiveCapture("cancelled") }
            }
        }
    }

    private suspend fun stopCapture(params: JsonObject): JsonObject {
        val sessionId = params.requireString("sessionId")
        val active = capture?.takeIf { it.sessionId == sessionId } ?: invalidRequest("No open capture $sessionId")
        stopActiveCapture("stopped")
        val frames = active.deliveredFrames + active.queuedFrames
        if (frames == 0L) bridgeError(WebPluginErrorCode.NO_AUDIO, "Recording stopped without receiving an audio frame")
        return buildJsonObject {
            put("sessionId", sessionId)
            put("durationMs", (frames + active.droppedFrames) * WebPluginAudioOptions.FRAME_MS)
            put("frameCount", frames)
            put("opusBytes", active.deliveredBytes + active.queuedBytes)
            put("deliveredFrameCount", frames)
            put("droppedFrameCount", active.droppedFrames)
        }
    }

    private fun onAudioFrames(sessionId: String, frames: List<ByteArray>) {
        val active = capture?.takeIf { it.sessionId == sessionId } ?: return
        if (!active.onFrames(frames)) {
            finishCapture(active, emitError = WebPluginErrorCode.BUFFER_OVERFLOW)
        } else if (active.reachedMaxDuration && !active.stopRequested) {
            scope.launch { stopActiveCapture("maxDuration") }
        }
    }

    /**
     * Stops the microphone of the open capture. Audio the link delivers until the microphone
     * has stopped is still queued; the queued tail then drains to the page before `end`.
     */
    private suspend fun stopActiveCapture(reason: String) {
        val active = capture ?: return
        if (active.stopRequested) return
        active.stopRequested = true
        val opened = microphone
        microphone = null
        runCatching { opened?.close() }.onFailure(::rethrowCancellation)
        _microphoneActive.value = false
        while (true) {
            val (sessionId, frames) = audioFrames.tryReceive().getOrNull() ?: break
            onAudioFrames(sessionId, frames)
        }
        if (capture === active) capture = null
        active.stop()
        emitCaptureState(active.sessionId, "stopped", reason)
    }

    private fun finishCapture(active: WebPluginAudioCapture, emitError: WebPluginErrorCode, reason: String = "error") {
        if (capture === active) capture = null
        val opened = microphone
        microphone = null
        _microphoneActive.value = false
        active.fail(emitError, "Audio capture ended: ${emitError.name}")
        if (opened != null) scope.launch { runCatching { opened.close() }.onFailure(::rethrowCancellation) }
        emitCaptureState(active.sessionId, "stopped", reason, emitError)
    }

    private fun emitCaptureState(sessionId: String, state: String, reason: String? = null, code: WebPluginErrorCode? = null) {
        emit(
            "audio.captureState",
            buildJsonObject {
                put("sessionId", sessionId)
                put("state", state)
                reason?.let { put("reason", it) }
                code?.let { put("code", it.name) }
            },
        )
    }

    // endregion

    // region Helpers

    private fun requireConnected() {
        if (!device.connected.value) bridgeError(WebPluginErrorCode.DEVICE_DISCONNECTED, "MemoMind glasses are not connected")
    }

    private fun checkStatus(status: Int, channel: Int) {
        val code = when (status) {
            MemoMindPluginStatus.OK -> return
            MemoMindPluginStatus.INVALID -> WebPluginErrorCode.INVALID_REQUEST
            MemoMindPluginStatus.BUSY, MemoMindPluginStatus.NO_MEMORY, MemoMindPluginStatus.FULL -> WebPluginErrorCode.BUSY
            MemoMindPluginStatus.NOT_SUPPORTED, MemoMindPluginStatus.NOT_PERMITTED, MemoMindPluginStatus.WRONG_STATE,
            MemoMindPluginStatus.VERSION,
            -> WebPluginErrorCode.CAPABILITY_UNAVAILABLE
            else -> WebPluginErrorCode.INTERNAL_ERROR
        }
        bridgeError(code, "The glasses plugin did not accept channel $channel (status $status)")
    }

    private fun requirePayload(bytes: Int, label: String) {
        if (bytes > WebPluginContract.MAX_PAYLOAD_BYTES) {
            bridgeError(WebPluginErrorCode.PAYLOAD_TOO_LARGE, "$label payload is $bytes bytes; max ${WebPluginContract.MAX_PAYLOAD_BYTES}")
        }
    }

    private fun describe(error: Throwable): Pair<WebPluginErrorCode, String> = when (error) {
        is WebPluginBridgeException -> error.code to (error.message ?: error.code.name)
        is MemoMindCommandException ->
            (if (error.status == null) WebPluginErrorCode.TIMEOUT else WebPluginErrorCode.INTERNAL_ERROR) to "The glasses request failed"
        is IllegalStateException -> {
            if (!device.connected.value) {
                WebPluginErrorCode.DEVICE_DISCONNECTED to "MemoMind glasses are not connected"
            } else {
                WebPluginErrorCode.INTERNAL_ERROR to "The glasses request failed"
            }
        }
        else -> {
            PlatformLogger.w(TAG, "Bridge call failed: ${error::class.simpleName}: ${error.message}")
            WebPluginErrorCode.INTERNAL_ERROR to "Bridge operation failed"
        }
    }

    private fun response(requestId: String, runtime: Long, body: JsonObjectBuilder.() -> Unit): String =
        buildJsonObject {
            put("requestId", requestId)
            body()
            put("runtimeGeneration", runtime)
        }.toString()

    private fun rethrowCancellation(error: Throwable) {
        if (error is CancellationException) throw error
    }

    // endregion

    companion object {
        private const val TAG = "MemoMindWebPlugin"
        private const val INVALID_REQUEST_ID = "invalid"
        const val BOOTSTRAP_REQUEST = """{"type":"gm-plugin:bootstrap-request"}"""
        private const val TEXT_HEADER = 11
        private const val BITMAP_HEADER = 10
        private const val LZ4_HEADER = 14
        private const val FRAME_TILE_HEADER = 20
        private const val FRAME_BEGIN_INDEX = 0xFFFF
        private const val FRAME_STATUS_LZ4_UNAVAILABLE = 2
        const val FRAME_ACK_TIMEOUT_MS = 5_000L
        private const val RATE_WINDOW_MS = 1_000L
        private const val MAX_REQUESTS_PER_WINDOW = 300
        private const val MAX_IN_FLIGHT = 64
        private val MAX_MESSAGE_BASE64 = (WebPluginContract.MAX_PAYLOAD_BYTES + 2) / 3 * 4
        private val WEB_BRIDGE_DISPLAY = listOf("text", "gray4", "clear", "gray4-lz4", "atomic-framed-lz4")
        private val FALLBACK_DISPLAY = listOf("text", "clear")
        private val BASE64 = Regex("^[A-Za-z0-9+/]*={0,2}$")
        private val json = Json { ignoreUnknownKeys = true }

        @OptIn(ExperimentalEncodingApi::class)
        internal fun decodeBase64(encoded: String): ByteArray {
            if (encoded.isEmpty() || encoded.length % 4 != 0 || !BASE64.matches(encoded)) invalidRequest("dataBase64 must be non-empty valid base64")
            return runCatching { Base64.decode(encoded) }.getOrNull() ?: invalidRequest("dataBase64 must be non-empty valid base64")
        }

        private fun buttonName(button: Int) = when (button) {
            MemoMindButton.PRIMARY -> "primary"
            MemoMindButton.UP -> "up"
            MemoMindButton.DOWN -> "down"
            MemoMindButton.LEFT -> "left"
            MemoMindButton.RIGHT -> "right"
            MemoMindButton.PAGE_UP -> "pageUp"
            MemoMindButton.PAGE_DOWN -> "pageDown"
            MemoMindButton.SCROLL_UP -> "scrollUp"
            MemoMindButton.SCROLL_DOWN -> "scrollDown"
            MemoMindButton.BACK -> "back"
            MemoMindButton.HOME -> "home"
            else -> "unknown"
        }

        private fun actionName(action: Int) = when (action) {
            MemoMindButtonAction.SINGLE -> "single"
            MemoMindButtonAction.DOUBLE -> "double"
            MemoMindButtonAction.LONG -> "long"
            MemoMindButtonAction.VERY_LONG -> "veryLong"
            MemoMindButtonAction.RELEASE -> "release"
            MemoMindButtonAction.TRIGGER -> "trigger"
            else -> "unknown"
        }

        private fun gestureName(gesture: Int) = when (gesture) {
            MemoMindGesture.NOD -> "nod"
            MemoMindGesture.HEAD_RAISE -> "headRaise"
            MemoMindGesture.HEAD_LOWER -> "headLower"
            MemoMindGesture.SHAKE -> "shake"
            MemoMindGesture.HEAD_RAISE_TIMEOUT -> "headRaiseTimeout"
            MemoMindGesture.HEAD_LOWER_TIMEOUT -> "headLowerTimeout"
            MemoMindGesture.LEFT -> "left"
            MemoMindGesture.RIGHT -> "right"
            else -> null
        }

        private fun u16(value: Int) = byteArrayOf((value shr 8).toByte(), value.toByte())
        private fun u32(value: Long) = byteArrayOf((value shr 24).toByte(), (value shr 16).toByte(), (value shr 8).toByte(), value.toByte())

        private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

        private fun JsonObject.requireString(key: String): String =
            string(key)?.takeIf { it.isNotEmpty() } ?: invalidRequest("$key must be a non-empty string")

        private fun JsonObject.requireInt(key: String): Int =
            (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull ?: invalidRequest("$key must be an integer")

        private fun JsonObject.optionalInt(key: String, fallback: Int): Int = if (this[key] == null) fallback else requireInt(key)

        private fun JsonObject.requireUInt32(key: String): Long =
            (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull?.takeIf { it in 0..0xFFFFFFFFL }
                ?: invalidRequest("$key must be uint32")
    }
}
