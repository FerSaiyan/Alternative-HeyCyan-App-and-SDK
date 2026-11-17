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
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class GlassesCustomizationViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private val glasses = GlassesFirmwareInfo(CustomizationFamily.HEYCYAN, "W610", "AC707N", "2.0.1", "V821_HW1", "1.2.3")

    /** One `PUT /jobs/{id}/assets/{slot}` the relay received. */
    private data class AssetPut(val path: String, val contentType: String, val headers: Map<String, String>, val size: Int)

    /** A relay that accepts everything and a glasses mic that returns a 1 s tone. */
    private class FakePlatform(var plan: String = "standard") : CustomizationPlatform {
        val calls = mutableListOf<String>()
        val bodies = mutableMapOf<String, String>()
        val puts = mutableListOf<AssetPut>()
        var createStatus = 201
        var glasses: GlassesFirmwareInfo? = null
        var jobStatus = "draft"
        var apiDeployed = true
        var config = CONFIG
        /** What submit moves the job to: `queued`, or `awaiting_base_firmware` for an unknown base. */
        var submitStatus = "queued"

        override fun plan() = plan
        override suspend fun detectGlasses() = glasses?.let { GlassesDetection.Found(it) } ?: GlassesDetection.Missing("none")

        override suspend fun api(method: String, path: String, body: String?): HttpResult {
            calls += "$method ${path.substringBefore('?')}"
            body?.let { bodies["$method ${path.substringBefore('?')}"] = it }
            return when {
                !apiDeployed -> HttpResult(404, "<!DOCTYPE html><title>404: This page could not be found.</title>")
                path.startsWith("/api/customization/config") -> HttpResult(200, config)
                method == "GET" && path == "/api/customization/jobs" -> HttpResult(200, """{"jobs":[${job()}]}""")
                method == "POST" && path == "/api/customization/jobs" -> if (createStatus == 201) {
                    HttpResult(201, """{"job":${job()}}""")
                } else {
                    HttpResult(createStatus, """{"error":"subscription_required","message":"Upgrade","currentPlan":"cheap"}""")
                }
                path.endsWith("/submit") -> {
                    jobStatus = submitStatus
                    HttpResult(200, """{"job":${job()}}""")
                }
                else -> HttpResult(404, """{"error":"job_not_found","message":"no"}""")
            }
        }

        private fun job() = """{"id":"job123456789","status":"$jobStatus","status_message":"ok","device_family":"heycyan",
            "wake_phrase":"Hey Luna","tone_slots":["power_on"],"expected_assets":[],"gates":null,"artifact":null,"created_at":"2026-11-02"}"""

        override suspend fun apiBytes(method: String, path: String, contentType: String, headers: Map<String, String>, bytes: ByteArray): HttpResult {
            calls += "$method $path"
            puts += AssetPut(path, contentType, headers, bytes.size)
            jobStatus = "uploading"
            return HttpResult(200, """{"asset":{"slot":"${path.substringAfterLast('/')}"},"job":${job()}}""")
        }

        override fun sha256Hex(bytes: ByteArray) = "a".repeat(64)
        /** A 2 s WAV: far over the slot's firmware capacity, well under the server's 1 MiB tone cap. */
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
            const val CONFIG = """{"enabled":true,"eligible":true,"ineligible_reason":null,"consent_version":"2026-11-17",
                "supported":true,"review_required":false,"review_max_days":30,"tone_slots":[{"id":"power_on","label":"Power on","max_bytes":2976,"format":"wts","max_duration_ms_est":3718}],
                "options_supported":{"disable_led_flash":true,"mute_shutter":false},
                "wake_word":{"supported":true,"languages":["en","pt"],"phonemes_note":null},
                "wake_languages":[{"code":"en","max_chars":32,"max_words":4,"min_letters":4},{"code":"pt","max_chars":32,"max_words":4,"min_letters":4}],
                "wake_samples":{"min":8,"max":12,"max_bytes":393216,"duration_ms":{"min":300,"max":10000}},
                "tones":{"slots":["power_on","wake_ack"],"max_bytes":1048576,"content_types":["audio/wav","audio/mpeg"]},
                "uploads":{"job_max_bytes":8388608}}"""

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
        assertEquals("2026-11-17", created["consent"]!!.jsonObject["version"]!!.jsonPrimitive.content)
        // Each asset goes straight to the relay: PUT /jobs/{id}/assets/{slot}, raw bytes, sha + duration headers.
        val base = "/api/customization/jobs/job123456789/assets/"
        assertEquals((1..8).map { base + "wake_0$it" } + (base + "power_on"), platform.puts.map { it.path })
        assertEquals(
            AssetPut(base + "wake_01", "audio/wav", mapOf("X-Content-SHA256" to "a".repeat(64), "X-Duration-Ms" to "1000"), 44 + 32_000),
            platform.puts.first(),
        )
        // The slot's 2976-B firmware capacity is not an upload limit; tones carry no duration.
        assertEquals(AssetPut(base + "power_on", "audio/wav", mapOf("X-Content-SHA256" to "a".repeat(64)), 64_044), platform.puts.last())
        assertTrue(platform.calls.none { it.endsWith("/upload-urls") })
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

    @Test
    fun aRelayWithoutTheCustomizationApiSaysTheServiceIsNotOpen() = runTest(dispatcher) {
        val vm = GlassesCustomizationViewModel(FakePlatform().apply { apiDeployed = false }, dispatcher)
        vm.toggleExpanded()

        assertEquals(CustomizationJson.SERVICE_NOT_OPEN, vm.state.value.statusText)
    }

    @Test
    fun anUnknownBaseFirmwareIsSentForReviewAndOnlyRefusedWithoutReview() = runTest(dispatcher) {
        val platform = FakePlatform().apply {
            glasses = this@GlassesCustomizationViewModelTest.glasses
            config = FakePlatform.CONFIG.replace("\"supported\":true,\"review_required\":false", "\"supported\":false,\"review_required\":true")
            submitStatus = "awaiting_base_firmware"
        }
        val vm = GlassesCustomizationViewModel(platform, dispatcher)
        vm.toggleExpanded()
        val ready = vm.state.value
        assertEquals(CustomizationPhase.READY, ready.phase)
        assertEquals(
            "We don't have your firmware version (HeyCyan W610 · BLE 2.0.1 · Wi-Fi 1.2.3) yet. You can still send your request: " +
                "we will review the version and email you, usually within a few days. Your recordings are kept for at most 30 days while we review.",
            ready.reviewNotice,
        )

        vm.setDisableLed(true)
        vm.setConsent(true)
        vm.submit()
        val after = vm.state.value
        assertTrue(after.progress!!.contains("we will review it and email you"), after.progress)
        assertEquals("Waiting for firmware review", after.jobs.single().statusLabel)
        assertTrue(after.jobs.single().active)

        platform.config = FakePlatform.CONFIG.replace("\"supported\":true,\"review_required\":false", "\"supported\":false,\"review_required\":false")
        vm.refresh()
        assertEquals(CustomizationPhase.UNSUPPORTED, vm.state.value.phase)
        assertNull(vm.state.value.reviewNotice)
    }

    @Test
    fun reviewOutcomesAreShownInTheBuildRow() {
        fun row(fields: String) = GlassesCustomizationViewModel.jobUi(
            CustomizationJson.parseJobEnvelope(
                """{"job":{"id":"job123456789","device_family":"heycyan","ble_firmware_version":"2.0.1","wifi_firmware_version":"1.2.3",$fields}}""",
            ),
        )
        val declined = row(
            """"status":"declined","status_message":"Declined: this model cannot be customized yet. Your recordings were deleted.",
            "base_firmware_status":"declined","base_ble_firmware_version":null,"decline_reason":"this model cannot be customized yet"""",
        )
        assertEquals(listOf("Declined", "Declined: this model cannot be customized yet. Your recordings were deleted."), listOf(declined.statusLabel, declined.message))
        assertTrue(!declined.active && declined.baseNote == null)
        // Without a status_message the reason still reaches the user.
        assertEquals("Reason: Too old. Your recordings were deleted.", row(""""status":"declined","decline_reason":"Too old"""").message)

        val mapped = row(
            """"status":"emailed","status_message":"Emailed","base_firmware_status":"mapped",
            "base_ble_firmware_version":"2.0.4","base_wifi_firmware_version":"1.2.5","review_expires_at":null""",
        )
        assertEquals("Built on firmware 2.0.4 (an update from your 2.0.1). Installing it updates your glasses to that version.", mapped.baseNote)
        assertNull(row(""""status":"queued","base_firmware_status":"catalog","base_ble_firmware_version":"2.0.1"""").baseNote)
    }
}
