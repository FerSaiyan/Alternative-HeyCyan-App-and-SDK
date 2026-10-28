package com.fersaiyan.cyanbridge.shared.plugins.mentra

import com.fersaiyan.cyanbridge.shared.devices.DeviceClass
import com.fersaiyan.cyanbridge.shared.devices.GlassesFamilyProfiles
import com.fersaiyan.cyanbridge.shared.plugins.PluginEcosystem
import com.fersaiyan.cyanbridge.shared.plugins.PluginEcosystems
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The host side of the `@mentra/miniapp` wire protocol, driven the way the bundled SDK drives it. */
class MiniappHostSessionTest {
    private class FakeDevice(display: Boolean = true) : MiniappDeviceIo {
        override val hardware = MiniappHardwareSupport("MemoMind", display, true, true, false, true, false)
        val shown = mutableListOf<String>()
        val transcription = mutableListOf<Pair<Boolean, String?>>()
        override fun showText(text: String, durationMs: Long?): Boolean = hardware.display.also { if (it) shown += text }
        override fun clearDisplay() { shown += "<clear>" }
        override fun setTranscription(active: Boolean, language: String?) { transcription += active to language }
        override fun speak(text: String, onDone: (Boolean) -> Unit) = onDone(true)
        override fun stopSpeaking() {}
        override fun openUrl(url: String) = true
    }

    private class Harness(permissions: String = """[{"type":"MICROPHONE"}]""", granted: Boolean = true, display: Boolean = true) {
        val manifest = MiniappManifest.parse(
            """{"packageName":"com.mentra.captions","version":"1.0.24","name":"Captions","entry":{"background":"b.js","ui":"ui/index.html"},
                "permissions":$permissions,"hardwareRequirements":[]}""",
        )
        val device = FakeDevice(display)
        val background = mutableListOf<JsonObject>()
        val ui = mutableListOf<JsonObject>()
        val session = MiniappHostSession(
            manifest,
            if (granted) MiniappApprovals.all(manifest) else MiniappApprovals(decided = manifest.permissions.map { it.type }.toSet()),
            device,
            MiniappKeyValueStore(null) {},
            object : MiniappHostOutput {
                override fun toBackground(raw: String) { background += Json.parseToJsonElement(raw).jsonObject }
                override fun toUi(frameJson: String) { ui += Json.parseToJsonElement(frameJson).jsonObject }
            },
        )

        fun send(payload: String, requestId: String? = null) =
            session.onBackgroundMessage("""{"payload":$payload${requestId?.let { ",\"requestId\":\"$it\"" }.orEmpty()}}""")

        /** The last result or push, as `payload`. */
        fun last(): JsonObject = background.last()["payload"]!!.jsonObject
    }

    private val JsonObject.type get() = this["type"]?.jsonPrimitive?.content

    @Test
    fun handshakeAdvertisesCapabilitiesAndDeclaredPermissions() {
        val h = Harness()
        h.send("""{"type":"miniapp_connect","packageName":"com.mentra.captions","initReady":true,"sessionId":"s1"}""", "c1")
        val ack = h.last()
        assertEquals("miniapp_connect_ack", ack.type)
        assertEquals("c1", h.background.last()["requestId"]!!.jsonPrimitive.content)
        assertEquals("true", ack["permissions"]!!.jsonObject["microphone"]!!.jsonPrimitive.content)
        assertEquals("false", ack["permissions"]!!.jsonObject["location"]!!.jsonPrimitive.content)
        assertEquals("500", ack["capabilities"]!!.jsonObject["display"]!!.jsonObject["width"]!!.jsonPrimitive.content)
        assertEquals("true", ack["hostFeatures"]!!.jsonObject["initReady"]!!.jsonPrimitive.content)
    }

    @Test
    fun transcriptionSubscriptionsFollowTheGrantAndDeliverEvents() {
        val h = Harness()
        h.send("""{"type":"miniapp_subscribe","subscriptions":["transcription:auto",{"stream":"transcription:de-DE","forceLocal":true},"button_press","_ui"]}""")
        assertEquals(listOf<Pair<Boolean, String?>>(true to "de-DE"), h.device.transcription)
        h.session.onTranscription("hallo", isFinal = false, language = "de-DE")
        val event = h.last()
        assertEquals("miniapp_event", event.type)
        assertEquals("transcription:de-DE", event["streamType"]!!.jsonPrimitive.content)
        assertEquals("all", event["transcriptionRoute"]!!.jsonPrimitive.content)
        h.session.onButton("main", "short")
        assertEquals("button_press", h.last()["streamType"]!!.jsonPrimitive.content)
        h.send("""{"type":"miniapp_subscribe","subscriptions":[]}""")
        assertEquals<Pair<Boolean, String?>>(false to null, h.device.transcription.last())

        // Declared but not granted, and not declared at all: no recognizer starts.
        listOf(Harness(granted = false), Harness(permissions = "[]")).forEach { refused ->
            refused.send("""{"type":"miniapp_subscribe","subscriptions":["transcription:auto"]}""")
            assertTrue(refused.device.transcription.isEmpty())
        }
    }

    @Test
    fun renderWrapsTextOnTheHostAndReportsLayout() {
        val h = Harness()
        h.send(
            """{"type":"miniapp_render","view":"main","includeTextLayout":true,"elements":[
                {"type":"text","id":"script","box":{"x":0,"y":0,"w":120,"h":220},"text":"one two three four five six","style":{"maxLines":2}},
                {"type":"image","id":"map","box":{"x":0,"y":0,"w":10,"h":10},"data":"AA=="}]}""",
            "r1",
        )
        val result = h.last()
        assertEquals("miniapp_request_result", result.type)
        val data = result["data"]!!.jsonObject
        assertEquals("displayed", data["status"]!!.jsonPrimitive.content)
        assertEquals(listOf("map"), data["dropped"]!!.jsonArray.map { it.jsonPrimitive.content })
        val layout = data["textLayout"]!!.jsonObject["script"]!!.jsonObject
        // 120 px at 12 px per glyph: 10 characters per line, two lines visible.
        assertEquals(listOf("one two", "three four"), layout["lines"]!!.jsonArray.map { it.jsonObject["text"]!!.jsonPrimitive.content })
        assertEquals(listOf(0, 8, 19), layout["lineStarts"]!!.jsonArray.map { it.jsonPrimitive.content.toInt() })
        assertEquals("true", layout["truncated"]!!.jsonPrimitive.content)
        assertEquals(listOf("one two\nthree four"), h.device.shown)

        h.send("""{"type":"miniapp_render","view":"main","elements":[]}""", "r2")
        assertEquals("<clear>", h.device.shown.last())

        val headless = Harness(display = false)
        headless.send("""{"type":"miniapp_render","elements":[{"type":"text","box":{"x":0,"y":0,"w":500,"h":220},"text":"hi"}]}""", "r3")
        assertEquals("blocked", headless.last()["data"]!!.jsonObject["status"]!!.jsonPrimitive.content)
    }

    @Test
    fun storageAndUnsupportedRequests() {
        val h = Harness()
        h.send("""{"type":"miniapp_storage_set","key":"lang","value":"en-US"}""", "s1")
        h.send("""{"type":"miniapp_storage_get","key":"lang"}""", "s2")
        assertEquals("en-US", h.last()["data"]!!.jsonObject["value"]!!.jsonPrimitive.content)
        h.send("""{"type":"miniapp_storage_get","key":"missing"}""", "s3")
        assertNull(h.last()["data"]!!.jsonObject["value"]!!.jsonPrimitive.contentOrNullValue())

        h.send("""{"type":"miniapp_blob_list"}""", "b1")
        assertEquals("NOT_IMPLEMENTED", h.last()["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
        h.send("""{"type":"miniapp_location_poll"}""", "l1")
        assertEquals("PERMISSION_NOT_DECLARED", h.last()["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
    }

    @Test
    fun theUiBusWaitsForTheBackgroundThenRelaysBothWays() {
        val h = Harness()
        h.session.onUiFrame("""{"type":"ready"}""")
        h.session.onUiFrame("""{"type":"msg","seq":1,"channel":"captions:clear","payload":{}}""")
        assertTrue(h.background.none { it["payload"]!!.jsonObject["streamType"]?.jsonPrimitive?.content == "_ui" })

        h.send("""{"type":"miniapp_ready","sessionId":"s1"}""")
        val ui = h.background.map { it["payload"]!!.jsonObject }.filter { it["streamType"]?.jsonPrimitive?.content == "_ui" }
        assertEquals(listOf("UI_OPEN", "UI_MESSAGE"), ui.map { it["data"]!!.jsonObject.type })

        h.send("""{"type":"UI_SEND","channel":"captions:snapshot","payload":{"n":1},"seq":3,"requestId":"r9"}""")
        val frame = h.ui.single()
        assertEquals("msg", frame.type)
        assertEquals("r9", frame["requestId"]!!.jsonPrimitive.content)

        h.session.onUiClosed()
        h.send("""{"type":"UI_SEND","channel":"captions:snapshot","payload":{}}""")
        assertEquals(1, h.ui.size)
        assertEquals("UI_CLOSE", h.background.map { it["payload"]!!.jsonObject }.last { it["streamType"] != null }["data"]!!.jsonObject.type)
    }

    @Test
    fun pluginsPageSectionsFollowTheSelectedGlasses() {
        fun sections(deviceClass: DeviceClass) = PluginEcosystems.sectionsFor(GlassesFamilyProfiles.forClass(deviceClass))
        assertEquals(listOf(PluginEcosystem.MEMOMIND_WEB_PLUGINS, PluginEcosystem.MENTRA_MINIAPPS), sections(DeviceClass.MEMOMIND))
        assertEquals(listOf(PluginEcosystem.MENTRA_MINIAPPS), sections(DeviceClass.MEIZU_MYVU))
        assertEquals(listOf(PluginEcosystem.MENTRA_MINIAPPS), sections(DeviceClass.MENTRA_LIVE))
        assertEquals(emptyList(), sections(DeviceClass.HEY_CYAN))
        assertEquals(emptyList(), sections(DeviceClass.META_RAYBAN))

        val teleprompter = MiniappManifest.parse(
            """{"packageName":"com.mentra.teleprompter","version":"1.0.0","name":"T","entry":{"background":"b.js"},
                "hardwareRequirements":[{"type":"DISPLAY","level":"REQUIRED"},{"type":"MICROPHONE","level":"REQUIRED"}]}""",
        )
        assertEquals(listOf(MiniappHardware.DISPLAY), MiniappHardwareSupport.of(GlassesFamilyProfiles.mentraLive).missing(teleprompter))
        assertTrue(MiniappHardwareSupport.of(GlassesFamilyProfiles.meizuMyvu).missing(teleprompter).isEmpty())
        assertFalse(MiniappPermissionPolicy.canStart(Harness().manifest, MiniappApprovals()))
    }

    @Test
    fun theRuntimeBridgeRoutesThePolyfillsCalls() {
        val h = Harness()
        val delivered = mutableListOf<String>()
        val bridge = MiniappRuntimeBridge(
            h.session,
            MiniappKeyValueStore(null) {},
            object : MiniappRuntimePlatform {
                override fun randomBytes(count: Int) = ByteArray(count) { 7 }
                override suspend fun http(request: MiniappHttpRequest) = error("offline")
            },
            CoroutineScope(Dispatchers.Unconfined),
            delivered::add,
        )
        bridge.dispatch("__bridge", "send", """["{\"payload\":{\"type\":\"miniapp_connect\"},\"requestId\":\"c\"}"]""")
        assertEquals("miniapp_connect_ack", h.last().type)
        bridge.dispatch("localStorage", "setItem", """["k","v"]""")
        assertEquals("\"v\"", bridge.dispatch("localStorage", "getItem", """["k"]"""))
        assertEquals("1", bridge.dispatch("localStorage", "length", "[]"))
        assertEquals("[7,7]", bridge.dispatch("crypto", "getRandomBytes", "[2]"))
        bridge.dispatch("fetch", "request", """{"args":[{"url":"file:///etc/hosts","method":"GET"}],"reqId":"4"}""")
        assertTrue(delivered.single().contains("\"ok\":false"))
        val refused = runCatching { bridge.dispatch("ws", "open", "[]") }.exceptionOrNull()
        assertTrue(refused is MiniappDispatchException && refused.code == "NOT_IMPLEMENTED")
    }

    private fun kotlinx.serialization.json.JsonPrimitive.contentOrNullValue(): String? = if (this is kotlinx.serialization.json.JsonNull) null else content
}
