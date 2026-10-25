package com.fersaiyan.cyanbridge.shared.devices.memomind.webplugin

import com.fersaiyan.cyanbridge.shared.devices.memomind.GmCodec
import com.fersaiyan.cyanbridge.shared.devices.memomind.GmFrameDecoder
import com.fersaiyan.cyanbridge.shared.devices.memomind.GmMessage
import com.fersaiyan.cyanbridge.shared.devices.memomind.GmTlv
import com.fersaiyan.cyanbridge.shared.devices.memomind.GmTlvType
import com.fersaiyan.cyanbridge.shared.devices.memomind.MemoMindAudioListener
import com.fersaiyan.cyanbridge.shared.devices.memomind.MemoMindCommand
import com.fersaiyan.cyanbridge.shared.devices.memomind.MemoMindPluginStatus
import com.fersaiyan.cyanbridge.shared.devices.memomind.MemoMindRecordingLink
import com.fersaiyan.cyanbridge.shared.devices.memomind.MemoMindService
import com.fersaiyan.cyanbridge.shared.devices.memomind.MemoMindSession
import com.fersaiyan.cyanbridge.shared.devices.memomind.MemoMindStatus
import com.fersaiyan.cyanbridge.shared.devices.memomind.MemoMindTransport
import com.fersaiyan.cyanbridge.shared.devices.memomind.MemoMindWebBridge
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalEncodingApi::class)
class MemoMindWebPluginHostTest {
    /** MemoMind glasses with the Web Bridge plugin: acknowledges messages, answers frames with 0x0104. */
    private class Glasses : MemoMindTransport {
        lateinit var session: MemoMindSession
        val appMessages = mutableListOf<Pair<Int, ByteArray>>()
        val requests = mutableListOf<GmMessage>()
        private val decoder = GmFrameDecoder()
        override val isConnected = true
        override val maxFrameBytes = GmCodec.MAX_FRAME_BYTES
        override val awaitsFragmentAcks = false
        override val recordingLink: MemoMindRecordingLink? = null

        override suspend fun writeCommand(frame: ByteArray) {
            decoder.append(frame).forEach { request ->
                requests += request
                if (request.service != MemoMindService.PLUGIN) {
                    reply(request.copy(tlvs = listOf(GmTlv.status(MemoMindStatus.SUCCESS))))
                    return@forEach
                }
                val channel = request.first(GmTlvType.INT16)!!.intValue!!.toInt()
                val data = request.first(GmTlvType.BYTES)!!.value
                appMessages += channel to data
                reply(request.copy(tlvs = listOf(GmTlv.int8(MemoMindPluginStatus.OK), GmTlv.int32(0))))
                when (channel) {
                    MemoMindWebBridge.CHANNEL_FRAME_BEGIN -> frameStatus(data.copyOfRange(0, 4), tile = 0xFFFF, next = 0, complete = false)
                    MemoMindWebBridge.CHANNEL_FRAME_TILE_LZ4 -> {
                        val tile = ((data[4].toInt() and 0xFF) shl 8) or (data[5].toInt() and 0xFF)
                        frameStatus(data.copyOfRange(0, 4), tile, next = tile + 1, complete = true)
                    }
                }
            }
        }

        private fun frameStatus(frameId: ByteArray, tile: Int, next: Int, complete: Boolean) = pluginEvent(
            MemoMindWebBridge.CHANNEL_FRAME_STATUS,
            byteArrayOf(1, 5, 0, 0, 0, 1, 0, 0, 0, 0) + frameId +
                byteArrayOf((tile shr 8).toByte(), tile.toByte(), (next shr 8).toByte(), next.toByte(), 0, if (complete) 1 else 0),
        )

        fun pluginEvent(channel: Int, data: ByteArray) = reply(
            GmMessage(0x42, MemoMindService.PLUGIN, MemoMindCommand.APP_MESSAGE_FROM_GLASSES, listOf(GmTlv.int16(channel), GmTlv.bytes(data))),
        )

        private fun reply(message: GmMessage) = GmCodec.encode(message).forEach(session::onCommandBytes)
    }

    private class Page : WebPluginPage {
        var bootstrap: Pair<String, Long>? = null
        val events = mutableListOf<JsonObject>()
        val portTexts = mutableListOf<String>()
        val portChunks = mutableListOf<ByteArray>()
        var announcement: String? = null
        var fromPage: ((String) -> Unit)? = null

        override fun bootstrap(sessionToken: String, runtimeGeneration: Long) {
            bootstrap = sessionToken to runtimeGeneration
        }

        override fun resolve(responseJson: String) = Unit
        override fun emit(eventJson: String) {
            events += Json.parseToJsonElement(eventJson).jsonObject
        }

        override fun openStreamPort(announcementJson: String, onMessage: (String) -> Unit): WebPluginStreamPort {
            announcement = announcementJson
            fromPage = onMessage
            return object : WebPluginStreamPort {
                override fun postBinary(bytes: ByteArray) {
                    portChunks += bytes
                }

                override fun postText(text: String) {
                    portTexts += text
                }

                override fun close() = Unit
            }
        }

        fun eventsNamed(name: String) = events.filter { it["name"]!!.jsonPrimitive.content == name }
    }

    private class Fixture(scope: TestScope, webBridge: Boolean, permissions: String = MmpkgFixture.COUNTER_PERMISSIONS) {
        val glasses = Glasses()
        val session = MemoMindSession(glasses).also { glasses.session = it }
        val page = Page()
        var microphone: MemoMindAudioListener? = null
        var tokens = 0
        val manifest = MmpkgManifest.parse(MmpkgFixture.manifest(MmpkgFixture.counterFiles(), permissions = permissions))
        val host = MemoMindWebPluginHost(
            manifest = manifest,
            approvals = WebPluginApprovals.all(manifest.permissions),
            device = MemoMindWebPluginDevice(session, MutableStateFlow(true)) { _, listener ->
                microphone = listener
                WebPluginMicrophone { microphone = null }
            },
            storage = WebPluginStorage(null, persist = {}),
            page = page,
            scope = scope.backgroundScope,
            secureToken = { "token-${++tokens}-0123456789abcdef" },
            clock = { scope.testScheduler.currentTime },
            audioPortsSupported = true,
        )

        init {
            if (webBridge) glasses.pluginEvent(MemoMindWebBridge.CHANNEL_CONNECTION, byteArrayOf(1, 4, 0, 0, 0, 1, 0, 0, 0, 0, 1))
            host.start()
            host.onPageStarted()
            host.onPageLoaded()
            scope.runCurrent()
        }

        suspend fun call(method: String, params: String = "{}", token: String? = null, generation: Long? = null): JsonObject {
            val (bootToken, bootGeneration) = page.bootstrap!!
            val request = """{"version":"2.0","sessionToken":"${token ?: bootToken}","requestId":"r1","method":"$method",""" +
                """"params":$params,"runtimeGeneration":${generation ?: bootGeneration}}"""
            return Json.parseToJsonElement(host.handle(request)).jsonObject
        }
    }

    private fun JsonObject.errorCode() = this["error"]?.jsonObject?.get("code")?.jsonPrimitive?.content
    private fun JsonObject.errorMessage() = this["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content
    private fun JsonObject.result() = this["result"]!!.jsonObject

    @Test
    fun envelopeAndPermissionsAreEnforced() = runTest {
        val fixture = Fixture(this, webBridge = true)

        assertEquals("UNAUTHORIZED", fixture.call("runtime.ping", token = "forged").errorCode())
        assertEquals("STALE_RUNTIME", fixture.call("runtime.ping", generation = 0).errorCode())
        assertEquals("METHOD_NOT_FOUND", fixture.call("private.method").errorCode())
        assertEquals("UNDECLARED", fixture.call("audio.openCapture").errorMessage())
        assertEquals("OUT_OF_SCOPE", fixture.call("plugin.sendMessage", """{"channel":1234,"dataBase64":"AQ=="}""").errorMessage())
        // Channel 2 draws; messaging scope alone cannot reach it.
        assertEquals("PERMISSION_DENIED", fixture.call("plugin.sendMessage", """{"channel":2,"dataBase64":"AQ=="}""").errorCode())
        assertEquals("INVALID_REQUEST", fixture.call("display.updateText", """{"id":1,"x":590,"y":0,"width":20,"height":20,"text":"x"}""").errorCode())

        assertEquals(true, fixture.call("storage.set", """{"key":"count","value":{"n":3}}""").result()["stored"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("""{"n":3}""", fixture.call("storage.get", """{"key":"count"}""").result()["value"].toString())
        val methods = fixture.call("runtime.getCapabilities").result()["methods"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue("display.beginFrame" in methods && "files.pick" !in methods && "audio.openCapture" !in methods)

        // A reload replaces the runtime: the old token and generation stop working.
        val (oldToken, oldGeneration) = fixture.page.bootstrap!!
        fixture.host.onPageStarted()
        fixture.host.onPageLoaded()
        assertEquals("UNAUTHORIZED", fixture.call("runtime.ping", token = oldToken, generation = oldGeneration + 1).errorCode())
        assertEquals("STALE_RUNTIME", fixture.call("runtime.ping", generation = oldGeneration).errorCode())
    }

    @Test
    fun displayMessagesAndInputMapOntoTheWebBridge() = runTest {
        val fixture = Fixture(this, webBridge = true)
        val glasses = fixture.glasses

        fixture.call("display.updateText", """{"id":1,"x":20,"y":20,"width":300,"height":80,"border":1,"radius":8,"text":"Hello"}""")
        assertEquals(MemoMindWebBridge.CHANNEL_TEXT, glasses.appMessages.last().first)
        assertContentEquals(MemoMindWebBridge.text(1, 20, 20, 300, 80, "Hello", 1, 8), glasses.appMessages.last().second)

        val begin = fixture.call("display.beginFrame", """{"frameId":42,"tileCount":1}""").result()
        assertEquals(0, begin["nextIndex"]!!.jsonPrimitive.content.toInt())
        val tile = fixture.call(
            "display.updateFrameImageLz4",
            """{"frameId":42,"tileIndex":0,"x":0,"y":0,"width":4,"height":2,"stride":2,"decodedSize":4,"dataBase64":"QAAAAAA="}""",
        ).result()
        assertEquals("true", tile["complete"]!!.jsonPrimitive.content)
        val (channel, payload) = glasses.appMessages.last()
        assertEquals(MemoMindWebBridge.CHANNEL_FRAME_TILE_LZ4, channel)
        assertEquals(20 + 5, payload.size)

        val sent = fixture.call("plugin.sendMessage", """{"channel":17991,"dataBase64":"AgEAAA=="}""").result()
        assertEquals(4, sent["payloadBytes"]!!.jsonPrimitive.content.toInt())
        assertContentEquals(byteArrayOf(2, 1, 0, 0), glasses.appMessages.last().second)

        fixture.call("device.subscribeEvents", """{"types":["button"]}""")
        glasses.pluginEvent(17992, byteArrayOf(9, 8))
        glasses.pluginEvent(4242, byteArrayOf(7))
        glasses.pluginEvent(MemoMindWebBridge.CHANNEL_BUTTON, byteArrayOf(1, 1, 0, 0, 0, 7, 0, 0, 0, 9, 0, 1, 0, 2))
        runCurrent()

        val messages = fixture.page.eventsNamed("plugin.message").map { it["data"]!!.jsonObject }
        assertEquals(listOf("17992"), messages.map { it["channel"]!!.jsonPrimitive.content })
        assertContentEquals(byteArrayOf(9, 8), Base64.decode(messages.single()["dataBase64"]!!.jsonPrimitive.content))
        val button = fixture.page.eventsNamed("device.button").single()
        assertEquals("double", button["data"]!!.jsonObject["action"]!!.jsonPrimitive.content)
        assertEquals(fixture.page.bootstrap!!.second, button["runtimeGeneration"]!!.jsonPrimitive.long)
    }

    @Test
    fun withoutTheWebBridgeTextFallsBackToTheTeleprompter() = runTest {
        val fixture = Fixture(this, webBridge = false)
        advanceTimeBy(MemoMindSession.WEB_BRIDGE_PING_TIMEOUT_MS + 1)

        val result = fixture.call("display.updateText", """{"id":1,"x":0,"y":0,"width":600,"height":350,"text":"Line one\nLine two"}""").result()

        assertEquals("teleprompter", result["fallback"]!!.jsonPrimitive.content)
        assertTrue(fixture.glasses.requests.any { it.service == MemoMindService.TELEPROMPTER && it.command == MemoMindCommand.TELEPROMPTER_TEXT })
        assertEquals(
            "CAPABILITY_UNAVAILABLE",
            fixture.call("display.updateImage", """{"x":0,"y":0,"width":2,"height":1,"stride":1,"dataBase64":"/w=="}""").errorCode(),
        )
        val display = fixture.call("runtime.getCapabilities").result()["display"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("text", "clear"), display)
    }

    @Test
    fun audioCaptureStreamsEnvelopesOnDemandAndFlushesOnStop() = runTest {
        val fixture = Fixture(
            this,
            webBridge = true,
            permissions = """[{ "name": "audio.capture", "required": false }]""",
        )

        val opened = fixture.call("audio.openCapture", """{"profile":"interactive","pickupMode":"unchanged"}""").result()
        val sessionId = opened["sessionId"]!!.jsonPrimitive.content
        val descriptor = opened["streamDescriptor"]!!.jsonObject
        assertEquals(descriptor, Json.parseToJsonElement(fixture.page.announcement!!).jsonObject["descriptor"])
        assertEquals("AUDIO_BUSY", fixture.call("audio.openCapture").errorCode())

        fixture.microphone!!.onOpusFrames(List(3) { index -> ByteArray(40) { index.toByte() } })
        runCurrent()
        fixture.page.fromPage!!("""{"type":"pull"}""")
        runCurrent()
        val chunk = fixture.page.portChunks.single()
        assertEquals(40 + 2 * 2 + 80, chunk.size)
        assertEquals(listOf(0x47, 0x4D, 0x41, 0x55), chunk.take(4).map { it.toInt() and 0xFF })

        val stopped = fixture.call("audio.stopCapture", """{"sessionId":"$sessionId"}""").result()
        assertEquals(3, stopped["frameCount"]!!.jsonPrimitive.content.toInt())
        assertEquals(120, stopped["opusBytes"]!!.jsonPrimitive.content.toInt())
        fixture.page.fromPage!!("""{"type":"pull"}""")
        runCurrent()
        assertEquals(2, fixture.page.portChunks.size)
        assertEquals("""{"type":"end"}""", fixture.page.portTexts.last())
    }
}
