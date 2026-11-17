package com.fersaiyan.cyanbridge.shared.customization

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** The glasses families the customization service builds for (`device_family` on the wire). */
enum class CustomizationFamily(val wire: String, val label: String) {
    HEYCYAN("heycyan", "HeyCyan"),
    EYEVUE("eyevue", "EyeVue"),
    TUNEBUDS("tunebuds", "TuneBuds");

    companion object {
        fun fromWire(value: String?): CustomizationFamily? = entries.firstOrNull { it.wire.equals(value?.trim(), ignoreCase = true) }
    }
}

/** What the connected glasses reported: the base firmware a custom build must start from. */
data class GlassesFirmwareInfo(
    val family: CustomizationFamily,
    val model: String,
    val bleHardwareVersion: String,
    val bleFirmwareVersion: String,
    val wifiHardwareVersion: String? = null,
    val wifiFirmwareVersion: String? = null,
) {
    val label: String
        get() = buildString {
            append(family.label)
            if (model.isNotBlank() && !model.equals(family.label, ignoreCase = true)) append(" ").append(model)
            append(" · BLE ").append(bleFirmwareVersion)
            wifiFirmwareVersion?.takeIf { it.isNotBlank() }?.let { append(" · Wi-Fi ").append(it) }
        }
}

/**
 * One firmware tone slot. [maxBytes] is the slot's capacity in the firmware (the encoded tone the
 * worker writes), not an upload limit: uploads use [CustomizationConfig.toneMaxBytes] and the worker
 * encodes and shortens the sound to fit. [maxDurationMs] is the catalog's estimate of how long a
 * sound fits, when the server sends it.
 */
data class ToneSlot(
    val id: String,
    val label: String,
    val maxBytes: Long?,
    val format: String?,
    val maxDurationMs: Long? = null,
)

data class WakeLanguage(val code: String, val maxChars: Int, val maxWords: Int, val minLetters: Int)

/**
 * `/api/customization/config` for one exact base firmware. When the server does not hold that base
 * but [reviewRequired] is set, the tone slots and options describe what the family's catalog can do,
 * and a request is still accepted: the owner reviews the version within [reviewMaxDays] days.
 */
data class CustomizationConfig(
    val enabled: Boolean,
    val eligible: Boolean,
    val ineligibleReason: String?,
    val consentVersion: String,
    /** The server holds this exact base firmware. */
    val supported: Boolean,
    /** Not [supported], but a request can still be sent and waits for the owner's review. */
    val reviewRequired: Boolean,
    val reviewMaxDays: Int,
    val toneSlots: List<ToneSlot>,
    val toneMaxBytes: Long,
    val toneContentTypes: List<String>,
    val disableLedSupported: Boolean,
    val muteShutterSupported: Boolean,
    val wakeWordSupported: Boolean,
    val wakeLanguages: List<WakeLanguage>,
    val phonemesNote: String?,
    val sampleMin: Int,
    val sampleMax: Int,
    val sampleMaxBytes: Long,
    val sampleMinMs: Long,
    val sampleMaxMs: Long,
    /** Total bytes one job may upload (`uploads.job_max_bytes`). */
    val jobMaxBytes: Long,
)

data class CustomizationAssetRef(val kind: String, val slot: String)

data class CustomizationGate(val name: String, val passed: Boolean)

/**
 * One build request. [bleFirmwareVersion]/[wifiFirmwareVersion] are what the glasses reported;
 * [baseBleFirmwareVersion]/[baseWifiFirmwareVersion] the base the build uses (null while in review).
 * [baseFirmwareStatus]: `catalog`, `review`, `added`, `mapped` (built on a newer official version) or `declined`.
 */
data class CustomizationJob(
    val id: String,
    val status: String,
    val statusMessage: String,
    val deviceFamily: String,
    val bleFirmwareVersion: String? = null,
    val wifiFirmwareVersion: String? = null,
    val baseFirmwareStatus: String? = null,
    val baseBleFirmwareVersion: String? = null,
    val baseWifiFirmwareVersion: String? = null,
    val declineReason: String? = null,
    val reviewExpiresAt: String? = null,
    val wakePhrase: String?,
    val toneSlots: List<String>,
    val disableLedFlash: Boolean,
    val muteShutter: Boolean,
    val expectedAssets: List<CustomizationAssetRef>,
    val gates: List<CustomizationGate>,
    val artifactFilename: String?,
    val createdAt: String,
) {
    val isActive: Boolean get() = status in ACTIVE_STATUSES

    companion object {
        /** `built` stays active only for jobs created before the review flow. */
        val ACTIVE_STATUSES = setOf("draft", "uploading", "awaiting_base_firmware", "queued", "building", "built")
    }
}

/** One asset upload: the bytes plus the metadata the server checks (`PUT /jobs/{id}/assets/{slot}`). */
data class CustomizationUpload(
    val kind: String,
    val slot: String,
    val contentType: String,
    val bytes: ByteArray,
    val sha256: String,
    val durationMs: Long? = null,
)

/** The server's `{"error": code, "message": ...}` body with the extra fields the app acts on. */
data class CustomizationApiError(
    val httpStatus: Int,
    val code: String,
    override val message: String,
    val currentPlan: String? = null,
    val jobId: String? = null,
    val problemSlots: List<String> = emptyList(),
    /** The limit a 413 (`asset_too_large`, `job_upload_limit`) refers to. */
    val maxBytes: Long? = null,
) : Exception(message)

/** The job-create request body (one place, so the ViewModel test can read it back). */
data class CustomizationJobRequest(
    val glasses: GlassesFirmwareInfo,
    val wakePhrase: String?,
    val wakeLanguage: String?,
    val wakeSampleCount: Int?,
    val toneSlots: List<String>,
    val disableLedFlash: Boolean,
    val muteShutter: Boolean,
    val consentVersion: String,
)

object CustomizationJson {
    const val SERVICE_NOT_OPEN = "The customization service is not open yet."

    private val json = Json { ignoreUnknownKeys = true }

    fun parseObject(text: String): JsonObject? = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()

    /** Generic limits come from the top level; the per-firmware fields from the contract override them. */
    fun parseConfig(text: String): CustomizationConfig {
        val root = parseObject(text) ?: error("Invalid customization config")
        val tones = root.obj("tones")
        val samples = root.obj("wake_samples")
        val sampleDuration = samples?.obj("duration_ms")
        val genericLanguages = root.array("wake_languages").mapNotNull { element ->
            val o = element as? JsonObject ?: return@mapNotNull null
            val code = o.str("code") ?: return@mapNotNull null
            WakeLanguage(code, o.int("max_chars") ?: 32, o.int("max_words") ?: 4, o.int("min_letters") ?: 4)
        }
        val toneMaxBytes = tones?.long("max_bytes") ?: 1_048_576L
        val perFirmwareSlots = root["tone_slots"]?.let { it as? JsonArray }?.mapNotNull { element ->
            val o = element as? JsonObject ?: return@mapNotNull null
            val id = o.str("id") ?: return@mapNotNull null
            ToneSlot(id, o.str("label") ?: slotLabel(id), o.long("max_bytes"), o.str("format"), o.long("max_duration_ms_est"))
        }
        val genericSlots = tones?.array("slots")?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            ?.map { ToneSlot(it, slotLabel(it), null, null) }
            .orEmpty()
        val options = root.obj("options_supported")
        val wake = root.obj("wake_word")
        val wakeCodes = wake?.array("languages")?.mapNotNull { element ->
            (element as? JsonPrimitive)?.contentOrNull ?: (element as? JsonObject)?.str("code")
        }
        val languages = if (wakeCodes == null) {
            genericLanguages
        } else {
            wakeCodes.map { code -> genericLanguages.firstOrNull { it.code == code } ?: WakeLanguage(code, 32, 4, 4) }
        }
        return CustomizationConfig(
            enabled = root.bool("enabled") ?: false,
            eligible = root.bool("eligible") ?: false,
            ineligibleReason = root.str("ineligible_reason"),
            consentVersion = root.str("consent_version").orEmpty(),
            supported = root.bool("supported") ?: false,
            reviewRequired = root.bool("review_required") ?: false,
            reviewMaxDays = root.int("review_max_days") ?: 30,
            toneSlots = perFirmwareSlots ?: genericSlots,
            toneMaxBytes = toneMaxBytes,
            toneContentTypes = tones?.array("content_types")?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty(),
            disableLedSupported = options?.bool("disable_led_flash") ?: false,
            muteShutterSupported = options?.bool("mute_shutter") ?: false,
            wakeWordSupported = wake?.bool("supported") ?: true,
            wakeLanguages = languages,
            phonemesNote = wake?.str("phonemes_note")?.takeIf { it.isNotBlank() },
            sampleMin = samples?.int("min") ?: 8,
            sampleMax = samples?.int("max") ?: 12,
            sampleMaxBytes = samples?.long("max_bytes") ?: 393_216L,
            sampleMinMs = sampleDuration?.long("min") ?: 300L,
            sampleMaxMs = sampleDuration?.long("max") ?: 10_000L,
            jobMaxBytes = root.obj("uploads")?.long("job_max_bytes") ?: 8_388_608L,
        )
    }

    fun parseJob(obj: JsonObject): CustomizationJob {
        val options = obj.obj("options")
        val gates = (obj["gates"] as? JsonObject)?.map { (name, value) ->
            CustomizationGate(name, (value as? JsonObject)?.bool("passed") ?: false)
        }.orEmpty()
        return CustomizationJob(
            id = obj.str("id") ?: error("Job without id"),
            status = obj.str("status").orEmpty(),
            statusMessage = obj.str("status_message").orEmpty(),
            deviceFamily = obj.str("device_family").orEmpty(),
            bleFirmwareVersion = obj.str("ble_firmware_version"),
            wifiFirmwareVersion = obj.str("wifi_firmware_version"),
            baseFirmwareStatus = obj.str("base_firmware_status"),
            baseBleFirmwareVersion = obj.str("base_ble_firmware_version"),
            baseWifiFirmwareVersion = obj.str("base_wifi_firmware_version"),
            declineReason = obj.str("decline_reason")?.takeIf { it.isNotBlank() },
            reviewExpiresAt = obj.str("review_expires_at"),
            wakePhrase = obj.str("wake_phrase"),
            toneSlots = obj.array("tone_slots").mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
            disableLedFlash = options?.bool("disable_led_flash") ?: false,
            muteShutter = options?.bool("mute_shutter") ?: false,
            expectedAssets = obj.array("expected_assets").mapNotNull { element ->
                val o = element as? JsonObject ?: return@mapNotNull null
                CustomizationAssetRef(o.str("kind") ?: return@mapNotNull null, o.str("slot") ?: return@mapNotNull null)
            },
            gates = gates,
            artifactFilename = obj.obj("artifact")?.str("filename"),
            createdAt = obj.str("created_at").orEmpty(),
        )
    }

    fun parseJobEnvelope(text: String): CustomizationJob =
        parseJob(parseObject(text)?.obj("job") ?: error("Response has no job"))

    fun parseJobList(text: String): List<CustomizationJob> =
        parseObject(text)?.array("jobs")?.mapNotNull { (it as? JsonObject)?.let(::parseJob) }.orEmpty()

    fun parseError(httpStatus: Int, text: String): CustomizationApiError {
        val o = parseObject(text)
        // A relay without the customization API (not deployed yet) answers a bare 404, not a JSON error.
        val noService = httpStatus == 404 && o?.str("error") == null
        return CustomizationApiError(
            httpStatus = httpStatus,
            code = o?.str("error") ?: if (noService) "customization_disabled" else "http_$httpStatus",
            message = o?.str("message") ?: if (noService) SERVICE_NOT_OPEN else "Server answered HTTP $httpStatus",
            currentPlan = o?.str("currentPlan"),
            jobId = o?.str("job_id"),
            problemSlots = o?.array("problems")?.mapNotNull { (it as? JsonObject)?.str("slot") }.orEmpty(),
            maxBytes = o?.long("max_bytes"),
        )
    }

    fun jobRequestBody(request: CustomizationJobRequest): String = buildJsonObject {
        val g = request.glasses
        put("device_family", g.family.wire)
        put("device_model", g.model)
        put("ble_hardware_version", g.bleHardwareVersion)
        put("ble_firmware_version", g.bleFirmwareVersion)
        g.wifiHardwareVersion?.takeIf { it.isNotBlank() }?.let { put("wifi_hardware_version", it) }
        g.wifiFirmwareVersion?.takeIf { it.isNotBlank() }?.let { put("wifi_firmware_version", it) }
        if (request.wakePhrase != null) {
            put("wake_phrase", request.wakePhrase)
            put("wake_language", request.wakeLanguage)
            put("wake_sample_count", request.wakeSampleCount)
        }
        put("tone_slots", buildJsonArray { request.toneSlots.forEach { add(JsonPrimitive(it)) } })
        put("options", buildJsonObject {
            put("disable_led_flash", request.disableLedFlash)
            put("mute_shutter", request.muteShutter)
        })
        put("consent", buildJsonObject {
            put("version", request.consentVersion)
            put("accepted", true)
        })
    }.toString()

    /** "power_on" -> "Power on". */
    fun slotLabel(id: String): String = id.replace('_', ' ').replaceFirstChar { it.uppercaseChar() }

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
    private fun JsonObject.array(key: String): List<JsonElement> = (this[key] as? JsonArray)?.jsonArray.orEmpty()
    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.contentOrNull
    private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
    private fun JsonObject.int(key: String): Int? = long(key)?.toInt()
}
