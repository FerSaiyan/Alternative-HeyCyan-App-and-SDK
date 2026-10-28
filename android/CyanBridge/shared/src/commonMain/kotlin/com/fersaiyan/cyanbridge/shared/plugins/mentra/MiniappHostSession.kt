package com.fersaiyan.cyanbridge.shared.plugins.mentra

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** The glasses and phone features a running miniapp reaches, implemented per platform. */
interface MiniappDeviceIo {
    val hardware: MiniappHardwareSupport
    val displayProfile: MiniappDisplayProfile get() = MiniappDisplayProfile()

    /** Shows [text] on the glasses (latest wins); clears after [durationMs] when set. False when nothing can show it. */
    fun showText(text: String, durationMs: Long?): Boolean

    fun clearDisplay()

    /** Starts or stops speech recognition for this miniapp; [language] is a BCP-47 tag or null for automatic. */
    fun setTranscription(active: Boolean, language: String?)

    /** Speaks [text] with the phone's TTS and calls [onDone] with true when it finished. */
    fun speak(text: String, onDone: (Boolean) -> Unit)

    fun stopSpeaking()

    /** Opens an http(s) URL outside the miniapp. */
    fun openUrl(url: String): Boolean
}

/** Where the session sends what it produces. */
interface MiniappHostOutput {
    /** A raw SDK envelope for the background (`__deliver({kind:"bridge", raw})`). */
    fun toBackground(raw: String)

    /** A frame for the UI WebView (`window.__mentra.recv(frame)`). */
    fun toUi(frameJson: String)

    /** The background finished its start-up (READY, or the fallback timeout): the UI may open. */
    fun onBackgroundReady() {}

    fun log(message: String) {}
}

/**
 * The host side of one running miniapp's session: the Mentra wire protocol of
 * `@mentra/miniapp` (`protocol.ts`, `session.ts`, `modules/ui.ts`) mapped onto CyanBridge's
 * device contracts through [device]. Implemented: the handshake, subscriptions (transcription,
 * buttons, touch, glasses connection and battery), `render` and the legacy layouts as a text
 * wall, `storage.*`, `speak`, `open_url`, the UI bus and visibility. Every other request is
 * answered `NOT_IMPLEMENTED`, after the manifest permission check the Mentra host does.
 */
class MiniappHostSession(
    private val manifest: MiniappManifest,
    private val approvals: MiniappApprovals,
    private val device: MiniappDeviceIo,
    private val storage: MiniappKeyValueStore,
    private val output: MiniappHostOutput,
) {
    private val lock = SynchronizedObject()
    private var connected = false
    private var backgroundReady = false
    private var uiAttached = false
    private var uiOpenOwed = false
    private val heldUiFrames = ArrayDeque<JsonObject>()
    private var subscriptions: Set<String> = emptySet()
    private var transcriptionStream: String? = null
    private var displayToken = 0
    private var displayShowing = false
    private var closed = false

    /** The SDK's `__bridge.send` payload. */
    fun onBackgroundMessage(raw: String) {
        val envelope = runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return
        val payload = envelope["payload"] as? JsonObject ?: return
        val requestId = (envelope["requestId"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val type = payload.string("type") ?: return
        if (closed) return
        when (type) {
            CONNECT -> connect(requestId, announcedReady = (payload["initReady"] as? JsonPrimitive)?.content == "true")
            READY -> markReady()
            SUBSCRIBE -> subscribe(payload)
            RENDER -> reply(requestId, render(payload))
            DISPLAY -> reply(requestId, legacyDisplay(payload))
            "UI_SEND" -> forwardToUi(payload)
            "UI_CANCEL" -> payload.string("requestId")?.let { toUi(buildJsonObject { put("type", "cancel"); put("requestId", it) }) }
            "miniapp_pong", "miniapp_ping" -> Unit
            else -> request(type, payload, requestId)
        }
    }

    // region handshake and lifecycle

    /**
     * Answers CONNECT. A session that did not announce `initReady` (bundles packed with older
     * SDKs) never sends READY, so its UI may open right away, as the Mentra host does.
     */
    private fun connect(requestId: String?, announcedReady: Boolean) {
        synchronized(lock) { connected = true }
        val ack = buildJsonObject {
            put("type", CONNECT_ACK)
            put("userId", "")
            put("packageName", manifest.packageName)
            put("capabilities", capabilities())
            put("visibility", if (synchronized(lock) { uiAttached }) "foreground" else "background")
            putJsonObject("permissions") { MiniappPermissionPolicy.permissionRecord(manifest).forEach { (k, v) -> put(k, v) } }
            putJsonObject("configuration") {}
            putJsonObject("hostFeatures") { put("initReady", true) }
        }
        send(ack, requestId)
        if (!announcedReady) markReady()
    }

    /** READY, or the host's start-up timeout: open the UI if it is waiting and flush what it sent. */
    fun markReady() {
        val (owedOpen, held) = synchronized(lock) {
            if (backgroundReady) return
            backgroundReady = true
            val owed = uiOpenOwed
            uiOpenOwed = false
            owed to heldUiFrames.toList().also { heldUiFrames.clear() }
        }
        output.onBackgroundReady()
        if (owedOpen) deliverUi(buildJsonObject { put("type", "UI_OPEN") })
        held.forEach(::deliverUi)
    }

    val isBackgroundReady: Boolean get() = synchronized(lock) { backgroundReady }

    /** The glasses changed: new capabilities for the app. */
    fun onHardwareChanged() {
        if (!synchronized(lock) { connected }) return
        send(buildJsonObject { put("type", "miniapp_capabilities_update"); put("capabilities", capabilities()) })
    }

    /** Stops the session: clears what it showed and stops speech recognition. */
    fun close() {
        val (wasShowing, hadTranscription) = synchronized(lock) {
            if (closed) return
            closed = true
            displayShowing to (transcriptionStream != null)
        }
        if (hadTranscription) device.setTranscription(false, null)
        if (wasShowing) device.clearDisplay()
        device.stopSpeaking()
    }

    private fun capabilities(): JsonObject {
        val hw = device.hardware
        val profile = device.displayProfile
        return buildJsonObject {
            put("modelName", hw.modelName)
            put("hasDisplay", hw.display)
            put("hasMicrophone", true)
            put("hasSpeaker", true)
            put("hasCamera", false)
            put("hasButton", hw.button)
            put("hasIMU", false)
            put("hasLight", false)
            put("hasWifi", false)
            put("hasOta", false)
            put("camera", JsonNull)
            if (hw.display) {
                putJsonObject("display") {
                    put("count", 1)
                    put("isColor", false)
                    put("canDisplayBitmap", false)
                    putJsonObject("resolution") { put("width", profile.width); put("height", profile.height) }
                    put("width", profile.width)
                    put("height", profile.height)
                    put("maxTextLines", profile.maxLines)
                    put("canPosition", false)
                    put("maxImageElements", 0)
                }
            } else {
                put("display", JsonNull)
            }
            putJsonObject("microphone") { put("count", 1) }
            putJsonObject("speaker") { put("count", 1) }
            put("imu", JsonNull)
            put("button", if (hw.button) buildJsonObject { put("count", 1) } else JsonNull)
            put("light", JsonNull)
            putJsonObject("power") { put("hasExternalBattery", false) }
        }
    }

    // endregion

    // region subscriptions and events

    private fun subscribe(payload: JsonObject) {
        val requested = (payload["subscriptions"] as? JsonArray)?.mapNotNull { item ->
            when (item) {
                is JsonPrimitive -> item.takeIf { it.isString }?.content
                is JsonObject -> item.string("stream")
                else -> null
            }
        }.orEmpty().filterNot { it.startsWith("_") }
        val allowed = requested.filter { stream ->
            val permission = MiniappPermissionPolicy.permissionForStream(stream) ?: return@filter true
            val gate = MiniappPermissionPolicy.gate(manifest, approvals, permission)
            if (gate != MiniappGate.ALLOWED) output.log("Subscription $stream refused: ${gate.code(permission)}")
            gate == MiniappGate.ALLOWED
        }.toSet()
        // One recognizer per miniapp: the most specific language wins over "auto".
        val transcription = allowed.filter { it.startsWith("transcription:") }
            .sortedBy { if (it == "transcription:auto") 1 else 0 }.firstOrNull()
        val changed = synchronized(lock) {
            subscriptions = allowed
            val before = transcriptionStream
            transcriptionStream = transcription
            before != transcription
        }
        if (changed) {
            val language = transcription?.substringAfter(':')?.takeIf { it != "auto" }
            device.setTranscription(transcription != null, language)
        }
    }

    private fun subscribed(stream: String): Boolean = synchronized(lock) { stream in subscriptions }

    /** A recognized phrase from the host's speech recognition. */
    fun onTranscription(text: String, isFinal: Boolean, language: String?) {
        val stream = synchronized(lock) { transcriptionStream } ?: return
        event(stream, buildJsonObject {
            put("text", text)
            put("isFinal", isFinal)
            language?.let { put("language", it) }
        }, transcriptionRoute = "all")
    }

    /** A glasses button: [pressType] is `short` or `long`. */
    fun onButton(buttonId: String, pressType: String) {
        if (subscribed(BUTTON_PRESS)) event(BUTTON_PRESS, buildJsonObject { put("buttonId", buttonId); put("pressType", pressType) })
    }

    /** A touch gesture: `single_tap`, `double_tap`, `triple_tap`, `long_press`, `swipe_up`, `swipe_down`. */
    fun onTouch(kind: String) {
        val data = buildJsonObject { put("kind", kind) }
        if (subscribed(TOUCH_EVENT)) event(TOUCH_EVENT, data)
        if (subscribed("$TOUCH_EVENT:$kind")) event("$TOUCH_EVENT:$kind", data)
    }

    fun onGlassesConnection(connected: Boolean) {
        if (subscribed("glasses_connection")) {
            event("glasses_connection", buildJsonObject { put("connected", connected); put("modelName", device.hardware.modelName) })
        }
    }

    fun onGlassesBattery(level: Int, charging: Boolean?) {
        if (subscribed("glasses_battery")) {
            event("glasses_battery", buildJsonObject { put("level", level); charging?.let { put("charging", it) } })
        }
    }

    private fun event(stream: String, data: JsonElement, transcriptionRoute: String? = null) {
        send(buildJsonObject {
            put("type", EVENT)
            put("streamType", stream)
            put("data", data)
            transcriptionRoute?.let { put("transcriptionRoute", it) }
        })
    }

    // endregion

    // region display

    private fun render(payload: JsonObject): JsonObject {
        val view = payload.string("view") ?: "main"
        if (view != "main") return blocked("The $view view is not supported on these glasses")
        payload.string("ifDisplayToken")?.let { token ->
            if (token != synchronized(lock) { displayToken.toString() }) return blocked("stale display token")
        }
        val elements = (payload["elements"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        val includeLayout = (payload["includeTextLayout"] as? JsonPrimitive)?.content == "true"
        val durationMs = (payload["durationMs"] as? JsonPrimitive)?.content?.toDoubleOrNull()?.toLong()?.takeIf { it > 0 }
        val profile = device.displayProfile
        val dropped = mutableListOf<String>()
        val layouts = linkedMapOf<String, MiniappTextLayoutResult>()
        val texts = mutableListOf<Pair<Int, String>>()
        elements.forEachIndexed { index, element ->
            val type = element.string("type")
            val id = element.string("id")
            if (type != "text") {
                dropped += id ?: "$type[$index]"
                return@forEachIndexed
            }
            val box = element["box"] as? JsonObject
            val style = element["style"] as? JsonObject
            val result = MiniappTextLayout.layout(
                text = element.string("text").orEmpty(),
                boxWidth = box?.int("w")?.coerceIn(1, profile.width) ?: profile.width,
                boxHeight = box?.int("h")?.coerceIn(1, profile.height) ?: profile.height,
                maxLines = style?.int("maxLines"),
                fromEnd = style?.string("textWindow") == "end",
                breakByCharacter = style?.string("breakMode")?.startsWith("character") == true,
                profile = profile,
            )
            layouts[id ?: "text[$index]"] = result
            texts += (box?.int("y") ?: 0) to result.text
        }
        val wall = texts.sortedBy { it.first }.map { it.second }.filter { it.isNotEmpty() }.joinToString("\n")
        val shown = if (elements.isEmpty() || wall.isEmpty()) {
            device.clearDisplay()
            synchronized(lock) { displayShowing = false }
            device.hardware.display
        } else {
            device.showText(wall, durationMs).also { ok -> if (ok) synchronized(lock) { displayShowing = true } }
        }
        val token = synchronized(lock) { (++displayToken).toString() }
        return buildJsonObject {
            put("status", if (shown) "displayed" else "blocked")
            if (shown) put("displayToken", token) else put("reason", "No glasses display is connected")
            put("degraded", dropped.isNotEmpty() || layouts.values.any { it.truncated })
            if (dropped.isNotEmpty()) put("dropped", buildJsonArray { dropped.forEach { add(JsonPrimitive(it)) } })
            if (includeLayout) putJsonObject("textLayout") {
                layouts.forEach { (key, layout) ->
                    putJsonObject(key) {
                        put("lines", buildJsonArray {
                            layout.lines.forEach { line ->
                                add(buildJsonObject { put("text", line.text); put("start", line.start); put("end", line.end) })
                            }
                        })
                        put("lineStarts", buildJsonArray { layout.lineStarts.forEach { add(JsonPrimitive(it)) } })
                        put("capacity", layout.capacity)
                        put("truncated", layout.truncated)
                    }
                }
            }
        }
    }

    /** The historical `miniapp_display` layouts from bundles packed with older SDKs. */
    private fun legacyDisplay(payload: JsonObject): JsonObject {
        val layout = payload["layout"] as? JsonObject ?: return blocked("layout is missing")
        val text = when (layout.string("layoutType")) {
            "text_wall" -> layout.string("text")
            "double_text_wall" -> listOfNotNull(layout.string("topText"), layout.string("bottomText")).joinToString("\n")
            "reference_card" -> listOfNotNull(layout.string("title"), layout.string("text")).joinToString("\n")
            "text_rows" -> (layout["text"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }?.joinToString("\n")
            "clear_view" -> ""
            else -> null
        } ?: return blocked("Unsupported layout")
        val element = buildJsonObject {
            put("type", "text")
            put("id", "legacy")
            putJsonObject("box") { put("x", 0); put("y", 0); put("w", device.displayProfile.width); put("h", device.displayProfile.height) }
            put("text", text)
        }
        return render(buildJsonObject {
            put("view", payload.string("view") ?: "main")
            put("elements", if (text.isEmpty()) JsonArray(emptyList()) else JsonArray(listOf(element)))
            payload["durationMs"]?.let { put("durationMs", it) }
        })
    }

    private fun blocked(reason: String) = buildJsonObject { put("status", "blocked"); put("reason", reason) }

    // endregion

    // region requests

    private fun request(type: String, payload: JsonObject, requestId: String?) {
        MiniappPermissionPolicy.permissionForRequest(type)?.let { permission ->
            val gate = MiniappPermissionPolicy.gate(manifest, approvals, permission)
            if (gate != MiniappGate.ALLOWED) return fail(requestId, gate.code(permission), "$type needs the ${permission.name} permission")
        }
        when (type) {
            "miniapp_storage_get" -> reply(requestId, buildJsonObject { put("value", payload.string("key")?.let(storage::get)?.let(::JsonPrimitive) ?: JsonNull) })
            "miniapp_storage_has" -> reply(requestId, buildJsonObject { put("has", payload.string("key")?.let(storage::has) == true) })
            "miniapp_storage_list" -> reply(requestId, buildJsonObject { put("keys", buildJsonArray { storage.keys().forEach { add(JsonPrimitive(it)) } }) })
            "miniapp_storage_get_all" -> reply(requestId, buildJsonObject { putJsonObject("values") { storage.all().forEach { (k, v) -> put(k, v) } } })
            "miniapp_storage_set" -> {
                val key = payload.string("key") ?: return fail(requestId, INVALID_ARGUMENT, "key is required")
                val value = (payload["value"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                    ?: return fail(requestId, INVALID_ARGUMENT, "value must be a string")
                if (storage.set(mapOf(key to value))) reply(requestId) else fail(requestId, INVALID_ARGUMENT, "storage is full")
            }
            "miniapp_storage_set_multiple" -> {
                val values = (payload["values"] as? JsonObject)?.mapNotNull { (k, v) ->
                    (v as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { k to it }
                }?.toMap() ?: return fail(requestId, INVALID_ARGUMENT, "values must be an object of strings")
                if (storage.set(values)) reply(requestId) else fail(requestId, INVALID_ARGUMENT, "storage is full")
            }
            "miniapp_storage_delete" -> { payload.string("key")?.let(storage::remove); reply(requestId) }
            "miniapp_storage_clear" -> { storage.clear(); reply(requestId) }
            "miniapp_storage_flush", "miniapp_transcription_config" -> reply(requestId)
            "miniapp_speak" -> speak(payload, requestId)
            "miniapp_stop_audio" -> { device.stopSpeaking(); reply(requestId) }
            "miniapp_open_url" -> {
                val url = payload.string("url")
                if (url != null && (url.startsWith("https://") || url.startsWith("http://")) && device.openUrl(url)) reply(requestId)
                else fail(requestId, INVALID_ARGUMENT, "Only http(s) URLs can be opened")
            }
            else -> fail(requestId, NOT_IMPLEMENTED, "$type is not available in CyanBridge yet")
        }
    }

    private fun speak(payload: JsonObject, requestId: String?) {
        val text = when (val value = payload["text"]) {
            is JsonPrimitive -> value.content
            is JsonArray -> value.mapNotNull { (it as? JsonPrimitive)?.content }.joinToString(" ")
            else -> null
        }?.takeIf { it.isNotBlank() } ?: return fail(requestId, INVALID_ARGUMENT, "text is required")
        if (text.length > MAX_SPEAK_CHARS) return fail(requestId, "TTS_TEXT_TOO_LONG", "text is longer than $MAX_SPEAK_CHARS characters")
        device.speak(text) { completed -> reply(requestId, buildJsonObject { put("completed", completed) }) }
    }

    private fun reply(requestId: String?, data: JsonElement? = null) {
        if (requestId == null) return
        send(buildJsonObject {
            put("type", REQUEST_RESULT)
            put("requestId", requestId)
            put("ok", true)
            data?.let { put("data", it) }
        }, requestId)
    }

    private fun fail(requestId: String?, code: String, message: String) {
        output.log("$code: $message")
        if (requestId == null) return
        send(buildJsonObject {
            put("type", REQUEST_RESULT)
            put("requestId", requestId)
            put("ok", false)
            putJsonObject("error") { put("code", code); put("message", message) }
        }, requestId)
    }

    private fun send(payload: JsonObject, requestId: String? = null) {
        output.toBackground(buildJsonObject {
            put("payload", payload)
            requestId?.let { put("requestId", it) }
        }.toString())
    }

    // endregion

    // region UI bus

    /** A frame the UI WebView posted (`window.ReactNativeWebView.postMessage`). */
    fun onUiFrame(frameJson: String) {
        val frame = runCatching { Json.parseToJsonElement(frameJson) as? JsonObject }.getOrNull() ?: return
        when (frame.string("type")) {
            "ready" -> {
                val deliverNow = synchronized(lock) {
                    uiAttached = true
                    if (!backgroundReady) uiOpenOwed = true
                    backgroundReady
                }
                visibility("foreground")
                if (deliverNow) deliverUi(buildJsonObject { put("type", "UI_OPEN") })
            }
            "msg" -> {
                val channel = frame.string("channel") ?: return
                deliverUi(buildJsonObject {
                    put("type", "UI_MESSAGE")
                    put("channel", channel)
                    put("payload", frame["payload"] ?: JsonNull)
                    put("seq", frame.int("seq") ?: 0)
                    frame.string("requestId")?.let { put("requestId", it) }
                })
            }
            "cancel" -> frame.string("requestId")?.let { id -> deliverUi(buildJsonObject { put("type", "UI_CANCEL"); put("requestId", id) }) }
        }
    }

    /** The UI WebView went away. */
    fun onUiClosed() {
        val wasAttached = synchronized(lock) {
            val was = uiAttached
            uiAttached = false
            uiOpenOwed = false
            heldUiFrames.clear()
            was
        }
        if (!wasAttached) return
        deliverUi(buildJsonObject { put("type", "UI_CLOSE") })
        visibility("background")
    }

    private fun visibility(value: String) {
        if (synchronized(lock) { connected }) send(buildJsonObject { put("type", "miniapp_visibility_change"); put("visibility", value) })
    }

    /** UI frames wait (up to [MAX_HELD_UI_FRAMES]) while the background is starting. */
    private fun deliverUi(data: JsonObject) {
        val now = synchronized(lock) {
            if (backgroundReady) true
            else {
                if (data.string("type") != "UI_OPEN" && heldUiFrames.size < MAX_HELD_UI_FRAMES) heldUiFrames.addLast(data)
                false
            }
        }
        if (now) event(UI_STREAM, data)
    }

    private fun forwardToUi(payload: JsonObject) {
        val channel = payload.string("channel") ?: return
        toUi(buildJsonObject {
            put("type", "msg")
            put("seq", payload.int("seq") ?: 0)
            put("channel", channel)
            put("payload", payload["payload"] ?: JsonNull)
            payload.string("requestId")?.let { put("requestId", it) }
        })
    }

    private fun toUi(frame: JsonObject) {
        if (synchronized(lock) { uiAttached }) output.toUi(frame.toString())
    }

    // endregion

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.int(key: String): Int? =
        (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.let { it.intOrNull ?: it.content.toDoubleOrNull()?.toInt() }

    private fun MiniappGate.code(permission: MiniappPermissionType) = when (this) {
        MiniappGate.NOT_DECLARED -> PERMISSION_NOT_DECLARED
        MiniappGate.DENIED -> PERMISSION_DENIED
        MiniappGate.ALLOWED -> "OK:${permission.name}"
    }

    companion object {
        const val CONNECT = "miniapp_connect"
        const val CONNECT_ACK = "miniapp_connect_ack"
        const val READY = "miniapp_ready"
        const val SUBSCRIBE = "miniapp_subscribe"
        const val RENDER = "miniapp_render"
        const val DISPLAY = "miniapp_display"
        const val EVENT = "miniapp_event"
        const val REQUEST_RESULT = "miniapp_request_result"
        const val UI_STREAM = "_ui"
        const val BUTTON_PRESS = "button_press"
        const val TOUCH_EVENT = "touch_event"
        const val NOT_IMPLEMENTED = "NOT_IMPLEMENTED"
        const val INVALID_ARGUMENT = "INVALID_ARGUMENT"
        const val PERMISSION_NOT_DECLARED = "PERMISSION_NOT_DECLARED"
        const val PERMISSION_DENIED = "PERMISSION_DENIED"
        const val MAX_HELD_UI_FRAMES = 128
        const val MAX_SPEAK_CHARS = 4_000
    }
}
