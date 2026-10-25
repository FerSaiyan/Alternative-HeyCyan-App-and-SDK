package com.fersaiyan.cyanbridge.shared.devices.memomind.webplugin

import com.fersaiyan.cyanbridge.shared.devices.memomind.MemoMindCaptureOptions
import com.fersaiyan.cyanbridge.shared.devices.memomind.MemoMindNoiseMode
import com.fersaiyan.cyanbridge.shared.devices.memomind.MemoMindPickupMode
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** The host end of a binary stream port transferred to the plugin page. */
interface WebPluginStreamPort {
    fun postBinary(bytes: ByteArray)
    fun postText(text: String)
    fun close()
}

/** `audio.openCapture` options after validation and profile defaults (`resolvedOptions`). */
data class WebPluginAudioOptions(
    val profile: String,
    val pickupMode: String,
    val noiseReduction: Boolean,
    val chunkDurationMs: Int,
    val maxQueueMs: Int,
    val overflowStrategy: String,
    val maxDurationMs: Int?,
) {
    val framesPerChunk: Int get() = chunkDurationMs / FRAME_MS
    val maxQueueFrames: Int get() = maxQueueMs / FRAME_MS

    val captureOptions: MemoMindCaptureOptions
        get() = MemoMindCaptureOptions(
            noise = if (noiseReduction) MemoMindNoiseMode.NOISE_REDUCTION else MemoMindNoiseMode.RAW,
            pickup = when (pickupMode) {
                "frontFixed" -> MemoMindPickupMode.FRONT_FIXED
                "meetingAuto" -> MemoMindPickupMode.MEETING_AUTO
                "nonWearerFocus" -> MemoMindPickupMode.NON_WEARER_FOCUS
                "frontBalanced" -> MemoMindPickupMode.FRONT_BALANCED
                "frontFocus" -> MemoMindPickupMode.FRONT_FOCUS
                else -> null
            },
        )

    fun toJson(): JsonObject = buildJsonObject {
        put("codec", "opus")
        put("sampleRate", 16_000)
        put("channels", 1)
        put("pickupMode", pickupMode)
        put("noiseReduction", noiseReduction)
        put("profile", profile)
        put("chunkDurationMs", chunkDurationMs)
        put("maxQueueMs", maxQueueMs)
        put("overflowStrategy", overflowStrategy)
        put("maxDurationMs", maxDurationMs?.let(::JsonPrimitive) ?: JsonNull)
    }

    companion object {
        const val FRAME_MS = 20
        private val PROFILES = mapOf(
            "interactive" to Triple(40, 200, "drop-oldest"),
            "balanced" to Triple(100, 500, "drop-oldest"),
            "reliable" to Triple(100, 3_000, "error"),
        )
        private val STRATEGIES = listOf("drop-oldest", "drop-newest", "error")
        private val KEYS = setOf("profile", "pickupMode", "noiseReduction", "chunkDurationMs", "maxQueueMs", "overflowStrategy", "maxDurationMs")

        /** Validates `audio.openCapture` params; throws INVALID_REQUEST. */
        fun parse(params: JsonObject): WebPluginAudioOptions {
            if ("mode" in params) invalidRequest("audio capture mode was removed; openCapture always returns a stream")
            params.keys.firstOrNull { it !in KEYS }?.let { invalidRequest("unknown audio option $it") }
            val profile = params.optString("profile") ?: "interactive"
            val pickupMode = params.optString("pickupMode") ?: "frontBalanced"
            if (pickupMode !in WebPluginContract.PICKUP_MODES) invalidRequest("unknown pickupMode")
            val noiseReduction = params["noiseReduction"]?.let { (it as? JsonPrimitive)?.takeIf { p -> !p.isString }?.booleanOrNull ?: invalidRequest("noiseReduction must be boolean") } ?: true
            val maxDuration = params["maxDurationMs"]?.takeIf { it !is JsonNull }?.let {
                (it as? JsonPrimitive)?.intOrNull?.takeIf { value -> value in 1_000..3_600_000 } ?: invalidRequest("maxDurationMs must be 1000..3600000")
            }
            val (chunk, queue, strategy) = if (profile == "custom") {
                val chunk = params.optInt("chunkDurationMs") ?: 40
                val queue = params.optInt("maxQueueMs") ?: 200
                val strategy = params.optString("overflowStrategy") ?: "drop-oldest"
                if (chunk !in 20..200 || chunk % FRAME_MS != 0) invalidRequest("chunkDurationMs must be 20..200 in 20 ms steps")
                if (queue !in 100..5_000 || queue < chunk) invalidRequest("maxQueueMs must be 100..5000 and at least chunkDurationMs")
                if (strategy !in STRATEGIES) invalidRequest("unknown overflowStrategy")
                Triple(chunk, queue, strategy)
            } else {
                if (listOf("chunkDurationMs", "maxQueueMs", "overflowStrategy").any { it in params }) {
                    invalidRequest("chunkDurationMs, maxQueueMs and overflowStrategy need profile 'custom'")
                }
                PROFILES[profile] ?: invalidRequest("unknown profile")
            }
            return WebPluginAudioOptions(profile, pickupMode, noiseReduction, chunk, queue, strategy, maxDuration)
        }

        /** The `audio` capability object (AUDIO_PROFILE of the contract). */
        fun capability(): JsonObject = buildJsonObject {
            put("codec", "opus")
            put("sampleRate", 16_000)
            put("channels", 1)
            put("noiseReduction", true)
            put("pickupModes", buildJsonArray { WebPluginContract.PICKUP_MODES.forEach { add(JsonPrimitive(it)) } })
            put("transport", "message-port")
            put("payload", "binary-envelope-v1")
            put(
                "envelope",
                buildJsonObject {
                    put("magic", AudioEnvelope.MAGIC)
                    put("version", 1)
                    put("chunkType", 1)
                    put("discontinuityFlag", 1)
                    put("baseHeaderBytes", AudioEnvelope.BASE_HEADER_BYTES)
                    put("frameLengthBytes", 2)
                    put("maxFramesPerChunk", AudioEnvelope.MAX_FRAMES)
                    put("byteOrder", "big-endian")
                },
            )
            put("frameDurationMs", FRAME_MS)
            put("chunkDurationMs", buildJsonObject { put("min", 20); put("max", 200) })
            put("maxQueueMs", buildJsonObject { put("min", 100); put("max", 5_000) })
            put("maxDurationMs", buildJsonObject { put("min", 1_000); put("max", 3_600_000); put("unlimited", true) })
            put("defaultProfile", "interactive")
            put(
                "profiles",
                buildJsonObject {
                    PROFILES.forEach { (name, values) ->
                        put(
                            name,
                            buildJsonObject {
                                put("chunkDurationMs", values.first)
                                put("maxQueueMs", values.second)
                                put("overflowStrategy", values.third)
                            },
                        )
                    }
                },
            )
            put("overflowStrategies", buildJsonArray { STRATEGIES.forEach { add(JsonPrimitive(it)) } })
        }

        private fun JsonObject.optString(key: String): String? = this[key]?.let {
            (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: invalidRequest("$key must be a string")
        }

        private fun JsonObject.optInt(key: String): Int? = this[key]?.let {
            (it as? JsonPrimitive)?.takeIf { p -> !p.isString }?.intOrNull ?: invalidRequest("$key must be an integer")
        }
    }
}

/** The V1 audio chunk envelope (big-endian), see the API reference. */
object AudioEnvelope {
    const val MAGIC = 0x474D4155
    const val BASE_HEADER_BYTES = 40
    const val MAX_FRAMES = 10

    fun encode(
        frames: List<ByteArray>,
        sequence: Long,
        timestampUs: Long,
        droppedFrameCount: Int,
        queueLatencyMs: Int,
        discontinuity: Boolean,
    ): ByteArray {
        require(frames.size in 1..MAX_FRAMES && frames.all { it.isNotEmpty() && it.size <= 0xFFFF })
        val headerBytes = BASE_HEADER_BYTES + frames.size * 2
        val payloadBytes = frames.sumOf { it.size }
        val out = ByteArray(headerBytes + payloadBytes)
        fun u16(offset: Int, value: Int) {
            out[offset] = (value shr 8).toByte()
            out[offset + 1] = value.toByte()
        }
        fun u32(offset: Int, value: Long) {
            u16(offset, (value shr 16).toInt() and 0xFFFF)
            u16(offset + 2, value.toInt() and 0xFFFF)
        }
        u32(0, MAGIC.toLong())
        out[4] = 1
        out[5] = 1
        u16(6, if (discontinuity) 1 else 0)
        u16(8, headerBytes)
        u16(10, frames.size)
        u32(12, sequence and 0xFFFFFFFFL)
        u32(16, timestampUs ushr 32)
        u32(20, timestampUs and 0xFFFFFFFFL)
        u32(24, (frames.size * WebPluginAudioOptions.FRAME_MS).toLong())
        u32(28, droppedFrameCount.toLong())
        u32(32, queueLatencyMs.toLong())
        u32(36, payloadBytes.toLong())
        var offset = BASE_HEADER_BYTES
        frames.forEach { u16(offset, it.size); offset += 2 }
        frames.forEach { it.copyInto(out, offset); offset += it.size }
        return out
    }
}

/**
 * One capture's bounded queue between the glasses microphone and the plugin's stream port:
 * groups 20 ms Opus frames into chunks, sends one chunk per `pull` from the page, applies the
 * overflow strategy, and on stop flushes the queued tail before `end`. Not thread-safe: the host
 * feeds it from its own dispatcher.
 */
internal class WebPluginAudioCapture(
    val sessionId: String,
    val options: WebPluginAudioOptions,
    private val port: WebPluginStreamPort,
    private val clock: () -> Long,
) {
    private class Frame(val bytes: ByteArray, val index: Long, val arrivedAtMs: Long)

    private val queue = ArrayDeque<Frame>()
    private var demand = 0
    private var sequence = 0L
    private var receivedFrames = 0L
    private var droppedSinceChunk = 0
    var droppedFrames = 0L
        private set
    var deliveredFrames = 0L
        private set
    var deliveredBytes = 0L
        private set
    /** The host is stopping the microphone; frames still arriving are queued. */
    var stopRequested = false
    var stopping = false
        private set
    var finished = false
        private set

    val queuedFrames: Int get() = queue.size
    val queuedBytes: Long get() = queue.sumOf { it.bytes.size.toLong() }
    val reachedMaxDuration: Boolean
        get() = options.maxDurationMs?.let { receivedFrames * WebPluginAudioOptions.FRAME_MS >= it } == true

    /** Queues microphone frames; returns false when the `error` strategy overflowed (the capture failed). */
    fun onFrames(frames: List<ByteArray>): Boolean {
        if (finished || stopping) return true
        frames.forEach { bytes ->
            if (bytes.isEmpty()) return@forEach
            val frame = Frame(bytes, receivedFrames++, clock())
            if (queue.size >= options.maxQueueFrames) {
                when (options.overflowStrategy) {
                    "drop-newest" -> {
                        dropped()
                        return@forEach
                    }
                    "drop-oldest" -> {
                        queue.removeFirst()
                        dropped()
                    }
                    else -> {
                        fail(WebPluginErrorCode.BUFFER_OVERFLOW, "Audio queue exceeded ${options.maxQueueMs} ms")
                        return false
                    }
                }
            }
            queue.addLast(frame)
        }
        drain()
        return true
    }

    fun onPull() {
        if (finished) return
        demand = (demand + 1).coerceAtMost(MAX_DEMAND)
        drain()
    }

    /** Microphone stopped: send the queued tail on demand, then `end`. */
    fun stop() {
        stopping = true
        drain()
    }

    /** Ends the stream with an error (runtime replaced, closed, overflow). */
    fun fail(code: WebPluginErrorCode, message: String) {
        if (finished) return
        finished = true
        queue.clear()
        port.postText(buildJsonObject { put("type", "error"); put("code", code.name); put("message", message) }.toString())
        port.close()
    }

    /** The page cancelled its stream. */
    fun cancel() {
        if (finished) return
        finished = true
        queue.clear()
        port.close()
    }

    private fun dropped() {
        droppedFrames++
        droppedSinceChunk++
    }

    private fun drain() {
        while (!finished && demand > 0 && (queue.size >= options.framesPerChunk || (stopping && queue.isNotEmpty()))) {
            val frames = List(minOf(options.framesPerChunk, queue.size)) { queue.removeFirst() }
            val envelope = AudioEnvelope.encode(
                frames = frames.map { it.bytes },
                sequence = sequence++,
                timestampUs = frames.first().index * WebPluginAudioOptions.FRAME_MS * 1_000L,
                droppedFrameCount = droppedSinceChunk,
                queueLatencyMs = (clock() - frames.first().arrivedAtMs).coerceIn(0, Int.MAX_VALUE.toLong()).toInt(),
                discontinuity = droppedSinceChunk > 0,
            )
            droppedSinceChunk = 0
            demand--
            deliveredFrames += frames.size
            deliveredBytes += frames.sumOf { it.bytes.size }
            port.postBinary(envelope)
        }
        if (!finished && stopping && queue.isEmpty()) {
            finished = true
            port.postText("""{"type":"end"}""")
            port.close()
        }
    }

    private companion object {
        const val MAX_DEMAND = 8
    }
}
