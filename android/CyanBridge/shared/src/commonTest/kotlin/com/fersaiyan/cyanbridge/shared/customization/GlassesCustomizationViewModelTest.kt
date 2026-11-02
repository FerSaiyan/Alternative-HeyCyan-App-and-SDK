package com.fersaiyan.cyanbridge.shared.customization

import com.fersaiyan.cyanbridge.shared.viewmodel.ScreenDialog
import com.fersaiyan.cyanbridge.shared.viewmodel.ScreenEffect
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class GlassesCustomizationViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private val glasses = GlassesFirmwareInfo(CustomizationFamily.HEYCYAN, "W610", "AC707N", "2.0.1", "V821_HW1", "1.2.3")

    /** A relay that accepts everything and a glasses mic that returns a 1 s tone. */
    private class FakePlatform(var plan: String = "standard") : CustomizationPlatform {
        val calls = mutableListOf<String>()
        val bodies = mutableMapOf<String, String>()
        val uploaded = mutableMapOf<String, Int>()
        var createStatus = 201
        var glasses: GlassesFirmwareInfo? = null
        var jobStatus = "draft"

        override fun plan() = plan
        override suspend fun detectGlasses() = glasses?.let { GlassesDetection.Found(it) } ?: GlassesDetection.Missing("none")

        override suspend fun api(method: String, path: String, body: String?): HttpResult {
            calls += "$method ${path.substringBefore('?')}"
            body?.let { bodies["$method ${path.substringBefore('?')}"] = it }
            return when {
                path.startsWith("/api/customization/config") -> HttpResult(200, CONFIG)
                method == "GET" && path == "/api/customization/jobs" -> HttpResult(200, """{"jobs":[${job()}]}""")
                method == "POST" && path == "/api/customization/jobs" -> if (createStatus == 201) {
                    HttpResult(201, """{"job":${job()}}""")
                } else {
                    HttpResult(createStatus, """{"error":"subscription_required","message":"Upgrade","currentPlan":"cheap"}""")
                }
                path.endsWith("/upload-urls") -> HttpResult(200, uploadsFor(body.orEmpty()))
                path.endsWith("/submit") -> {
                    jobStatus = "queued"
                    HttpResult(200, """{"job":${job()}}""")
                }
                else -> HttpResult(404, """{"error":"job_not_found","message":"no"}""")
            }
        }

        private fun job() = """{"id":"job123456789","status":"$jobStatus","status_message":"ok","device_family":"heycyan",
            "wake_phrase":"Hey Luna","tone_slots":["power_on"],"expected_assets":[],"gates":null,"artifact":null,"created_at":"2026-11-02"}"""

        private fun uploadsFor(body: String): String {
            val slots = Regex("\"slot\":\"([a-z0-9_]+)\"").findAll(body).map { it.groupValues[1] }.toList()
            return """{"uploads":[${slots.joinToString(",") { slot ->
                val kind = if (slot.startsWith("wake_")) "wake_sample" else "tone"
                """{"kind":"$kind","slot":"$slot","method":"PUT","upload_url":"https://s/$slot","headers":{"Content-Type":"audio/wav"}}"""
            }}]}"""
        }

        override suspend fun upload(ticket: UploadTicket, bytes: ByteArray): Int {
            uploaded[ticket.slot] = bytes.size
            return 200
        }

        override fun sha256Hex(bytes: ByteArray) = "a".repeat(64)
        /** A 2 s WAV: far over the slot's firmware capacity, well under the server's 5 MB tone cap. */
        override suspend fun readDocument(ref: String) = PickedDocument("chime.wav", "audio/wav", ByteArray(64_044) { 1 })

        override fun micCapture(family: CustomizationFamily) = object : GlassesMicCapture {
            override suspend fun record(maxMs: Long, stop: Deferred<Unit>, onPcm: (ByteArray) -> Unit): ByteArray {
                val pcm = tone(1_300)
                onPcm(pcm.copyOf(640))
                return pcm
            }
        }

        override suspend fun installFirmware(ref: String, glasses: GlassesFirmwareInfo, progress: (String) -> Unit) = "Installed"

        companion object {
            const val CONFIG = """{"enabled":true,"eligible":true,"ineligible_reason":null,"consent_version":"2026-11-01",
                "supported":true,"tone_slots":[{"id":"power_on","label":"Power on","max_bytes":2976,"format":"wts","max_duration_ms_est":3718}],
                "options_supported":{"disable_led_flash":true,"mute_shutter":false},
                "wake_word":{"supported":true,"languages":["en","pt"],"phonemes_note":null},
                "wake_languages":[{"code":"en","max_chars":32,"max_words":4,"min_letters":4},{"code":"pt","max_chars":32,"max_words":4,"min_letters":4}],
                "wake_samples":{"min":8,"max":12,"max_bytes":2097152,"duration_ms":{"min":300,"max":10000}},
                "tones":{"slots":["power_on","wake_ack"],"max_bytes":5242880,"content_types":["audio/wav","audio/mpeg"]}}"""

            /** 16 kHz PCM16 sine at -6 dBFS. */
            fun tone(ms: Int): ByteArray {
                val n = ms * 16
                return ByteArray(n * 2).also { out ->
                    for (i in 0 until n) {
                        val v = (16_000 * sin(2 * PI * 440 * i / 16_000.0)).toInt()
                        out[2 * i] = v.toByte()
                        out[2 * i + 1] = (v shr 8).toByte()
                    }
                }
            }
        }
    }

    @Test
    fun plansWithoutCustomizationAreLockedWithAnUpgradeButton() = runTest(dispatcher) {
        val effects = mutableListOf<ScreenEffect>()
        val vm = GlassesCustomizationViewModel(FakePlatform(plan = "cheap"), dispatcher)
        backgroundScope.launch(dispatcher) { vm.effectFlow.toList(effects) }

        vm.toggleExpanded()
        assertEquals(CustomizationPhase.LOCKED, vm.state.value.phase)
        vm.upgrade()
        assertEquals(listOf<ScreenEffect>(OpenCustomizationUpgrade), effects)
    }

    @Test
    fun recordedSamplesTonesAndOptionsAreUploadedAndSubmitted() = runTest(dispatcher) {
        val platform = FakePlatform().apply { glasses = this@GlassesCustomizationViewModelTest.glasses }
        val vm = GlassesCustomizationViewModel(platform, dispatcher)

        vm.toggleExpanded()
        val ready = vm.state.value
        assertEquals(CustomizationPhase.READY, ready.phase)
        assertEquals(listOf("power_on"), ready.tones.map { it.id })
        assertEquals("Plays up to about 3.7 s; longer sounds are shortened to fit.", ready.tones.single().hint)
        assertTrue(ready.showDisableLed && !ready.showMuteShutter)
        assertEquals(listOf("en", "pt"), ready.wakeLanguages)

        vm.setWakePhrase("Hey Luna")
        repeat(8) { vm.recordSample() }
        assertEquals(8, vm.state.value.samples.size)
        assertEquals(1_000L, vm.state.value.samples.first().durationMs) // 300 ms trimmed from each take
        vm.onTonePicked("power_on", "content://tone")
        vm.setDisableLed(true)
        vm.setConsent(true)
        vm.submit()

        val created = Json.parseToJsonElement(platform.bodies.getValue("POST /api/customization/jobs")).jsonObject
        assertEquals("heycyan", created["device_family"]!!.jsonPrimitive.content)
        assertEquals("2.0.1", created["ble_firmware_version"]!!.jsonPrimitive.content)
        assertEquals("8", created["wake_sample_count"]!!.jsonPrimitive.content)
        assertEquals("true", created["options"]!!.jsonObject["disable_led_flash"]!!.jsonPrimitive.content)
        assertEquals((1..8).map { "wake_0$it" } + "power_on", platform.uploaded.keys.toList())
        assertEquals(44 + 32_000, platform.uploaded.getValue("wake_01"))
        assertEquals(64_044, platform.uploaded.getValue("power_on")) // the slot's 2976-B capacity is not an upload limit
        assertTrue("POST /api/customization/jobs/job123456789/submit" in platform.calls)
        val after = vm.state.value
        assertEquals(emptyList(), after.samples)
        assertEquals("queued", after.jobs.single().status)
        assertTrue(after.progress!!.startsWith("Sent"))
    }

    @Test
    fun subscriptionRequiredFromTheServerLocksTheCardAndOffersTheUpgrade() = runTest(dispatcher) {
        val effects = mutableListOf<ScreenEffect>()
        val platform = FakePlatform().apply {
            glasses = this@GlassesCustomizationViewModelTest.glasses
            createStatus = 403
        }
        val vm = GlassesCustomizationViewModel(platform, dispatcher)
        backgroundScope.launch(dispatcher) { vm.effectFlow.toList(effects) }
        vm.toggleExpanded()
        vm.setDisableLed(true)
        vm.setConsent(true)
        vm.submit()

        assertEquals(CustomizationPhase.LOCKED, vm.state.value.phase)
        val dialog = effects.filterIsInstance<ScreenDialog>().single()
        assertEquals("Glasses customization: Standard or Max Required", dialog.title)
        dialog.positive!!.onClick!!.invoke()
        assertEquals(OpenCustomizationUpgrade, effects.last())
    }
}
