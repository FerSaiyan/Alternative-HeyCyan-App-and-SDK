package com.fersaiyan.cyanbridge.shared.plugins.mentra

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** A refusal from `__dispatch`; the engine throws it into JavaScript as `Error("CODE: message")`. */
class MiniappDispatchException(val code: String, message: String) : Exception("$code: $message")

/** An HTTP exchange for the background's `fetch` polyfill. */
data class MiniappHttpRequest(val url: String, val method: String, val headers: Map<String, String>, val body: ByteArray?)

data class MiniappHttpResponse(val status: Int, val statusText: String, val headers: Map<String, String>, val body: ByteArray)

/** Platform services the background runtime needs besides the session. */
interface MiniappRuntimePlatform {
    fun randomBytes(count: Int): ByteArray

    /** Performs [request] (http/https only, already checked); throws on network failure. */
    suspend fun http(request: MiniappHttpRequest): MiniappHttpResponse
}

/**
 * The native side of the background polyfill (`mobile/modules/jspolyfill/src/startup.ts`):
 * `__dispatch(iface, method, argsJson)` routes and the `__deliver` envelopes. The SDK's own
 * traffic is `__bridge.send`, handed to [session]; `localStorage`, `crypto.getRandomBytes` and
 * `fetch.request` are served here; WebSocket is refused (not implemented).
 *
 * [deliver] receives `__deliver` envelope JSON; the engine evaluates it on the JavaScript thread
 * after the current call returns (never re-entrantly).
 */
class MiniappRuntimeBridge(
    private val session: MiniappHostSession,
    private val localStorage: MiniappKeyValueStore,
    private val platform: MiniappRuntimePlatform,
    private val scope: CoroutineScope,
    private val deliver: (String) -> Unit,
) {
    /** The polyfill finished installing (`__runtime.ready`). */
    var polyfillReady = false
        private set

    /** Returns the JSON result for synchronous routes, or null. Throws [MiniappDispatchException]. */
    fun dispatch(iface: String, method: String, argsJson: String): String? {
        val parsed = runCatching { Json.parseToJsonElement(argsJson) }.getOrNull()
            ?: throw MiniappDispatchException("INVALID_ARGS", "argsJson is not JSON")
        // One-shots pass `[a1, …]`; requests pass `{"args": […], "reqId": "N"}`.
        val (args, reqId) = when (parsed) {
            is JsonArray -> parsed to null
            is JsonObject -> ((parsed["args"] as? JsonArray) ?: JsonArray(emptyList())) to (parsed["reqId"] as? JsonPrimitive)?.content
            else -> throw MiniappDispatchException("INVALID_ARGS", "argsJson must be an array or object")
        }
        return when ("$iface.$method") {
            "__bridge.send" -> {
                (args.firstOrNull() as? JsonPrimitive)?.takeIf { it.isString }?.content?.let(session::onBackgroundMessage)
                null
            }
            "__runtime.ready" -> { polyfillReady = true; null }
            "localStorage.getItem" -> args.string(0)?.let(localStorage::get)?.let { JsonPrimitive(it).toString() }
            "localStorage.setItem" -> {
                val key = args.string(0) ?: return null
                if (!localStorage.set(mapOf(key to (args.string(1) ?: "")))) throw MiniappDispatchException("QUOTA_EXCEEDED", "localStorage is full")
                null
            }
            "localStorage.removeItem" -> { args.string(0)?.let(localStorage::remove); null }
            "localStorage.clear" -> { localStorage.clear(); null }
            "localStorage.key" -> (args.getOrNull(0) as? JsonPrimitive)?.intOrNull?.let { localStorage.keys().getOrNull(it) }?.let { JsonPrimitive(it).toString() }
            "localStorage.length" -> localStorage.keys().size.toString()
            "crypto.getRandomBytes" -> {
                val count = (args.getOrNull(0) as? JsonPrimitive)?.intOrNull?.takeIf { it in 0..MAX_RANDOM_BYTES }
                    ?: throw MiniappDispatchException("INVALID_ARGS", "byte count out of range")
                buildJsonArray { platform.randomBytes(count).forEach { add(JsonPrimitive(it.toInt() and 0xFF)) } }.toString()
            }
            "fetch.request" -> {
                val id = reqId ?: throw MiniappDispatchException("INVALID_ARGS", "fetch needs a request id")
                fetch(id, args.getOrNull(0) as? JsonObject)
                null
            }
            else -> throw MiniappDispatchException("NOT_IMPLEMENTED", "$iface.$method is not available in CyanBridge")
        }
    }

    /** The `{kind:"init"}` envelope that runs the miniapp's `registerMiniapp` handler. */
    fun init(sessionId: String) = deliver(buildJsonObject { put("kind", "init"); put("sessionId", sessionId) }.toString())

    /** An SDK envelope from the host session. */
    fun bridge(raw: String) = deliver(buildJsonObject { put("kind", "bridge"); put("raw", raw) }.toString())

    @OptIn(ExperimentalEncodingApi::class)
    private fun fetch(reqId: String, options: JsonObject?) {
        val url = options?.string("url")
        if (url == null || !(url.startsWith("https://") || url.startsWith("http://")) || url.length > 8_192) {
            respondError(reqId, "INVALID_URL", "fetch only supports http(s) URLs")
            return
        }
        val headers = (options["headers"] as? JsonObject)?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.content?.let { k to it } }?.toMap().orEmpty()
        val body = options.string("bodyBase64")?.let { runCatching { Base64.decode(it) }.getOrNull() }
            ?: options.string("body")?.encodeToByteArray()
        val request = MiniappHttpRequest(url, options.string("method")?.uppercase() ?: "GET", headers, body)
        scope.launch {
            val response = try {
                platform.http(request)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                respondError(reqId, "NETWORK", error.message ?: "network error")
                return@launch
            }
            deliver(buildJsonObject {
                put("kind", "response")
                put("reqId", reqId)
                put("ok", true)
                putJsonObject("result") {
                    put("status", response.status)
                    put("statusText", response.statusText)
                    put("ok", response.status in 200..299)
                    putJsonObject("headers") { response.headers.forEach { (k, v) -> put(k.lowercase(), v) } }
                    put("bodyBase64", Base64.encode(response.body))
                }
            }.toString())
        }
    }

    private fun respondError(reqId: String, code: String, message: String) = deliver(buildJsonObject {
        put("kind", "response")
        put("reqId", reqId)
        put("ok", false)
        putJsonObject("error") { put("code", code); put("message", message) }
    }.toString())

    private fun List<JsonElement>.string(index: Int): String? = (getOrNull(index) as? JsonPrimitive)?.content

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    companion object {
        const val MAX_RANDOM_BYTES = 1024 * 1024
        const val MAX_FETCH_BYTES = 10 * 1024 * 1024
    }
}
