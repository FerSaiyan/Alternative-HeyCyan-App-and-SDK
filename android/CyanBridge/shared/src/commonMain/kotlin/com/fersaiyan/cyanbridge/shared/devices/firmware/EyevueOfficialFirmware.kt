package com.fersaiyan.cyanbridge.shared.devices.firmware

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** A newer BLE firmware the EyeVue update server offers for these glasses. */
data class EyevueOtaOffer(
    val modelCode: String,
    val version: String,
    val bleUrl: String,
    val packageName: String,
    /** The package also updates the camera (Wi-Fi) chip, which CyanBridge does not install. */
    val includesWifiPart: Boolean,
)

/** What a finished download must match before it is installed. */
data class DownloadExpectation(
    /** The server's Content-Length; null when it sent none (then the download is refused). */
    val size: Long?,
    /** The object store's MD5 (Content-MD5 or a plain ETag), lowercase hex; null when unknown. */
    val md5: String?,
    /** The pinned sha256 when this exact (model, version) was mirrored before. */
    val sha256: String?,
)

/**
 * The EyeVue vendor's update server (app `com.eyevue.glassapp` 1.0.68, captured 2026-09-30,
 * firmware_dump `RESULTS/reports/eyevue_ota__platform_eyevue-glass/`): an anonymous GET with the
 * glasses' firmware version and model code, answered with BLE and Wi-Fi packages on a public
 * Alibaba OSS bucket. No account, token or signature is involved. A null package means the
 * glasses already run the newest one.
 */
object EyevueOfficialFirmware {
    val HOSTS = listOf("https://platform.eyevue-glass.com/api", "https://api.eyevueai.com/api")
    private val SAFE = Regex("[A-Za-z0-9._-]{1,32}")
    private val json = Json { ignoreUnknownKeys = true }

    /** The version-check URL, or null when [modelCode] or [version] are not plain tokens. */
    fun checkUrl(host: String, modelCode: String, version: String): String? {
        if (!SAFE.matches(modelCode) || !SAFE.matches(version)) return null
        return "$host/app/ota/getNewOtaPackage?deviceVersion=$version&code=$modelCode"
    }

    /**
     * Parses the server's answer. Returns null when no newer BLE firmware is offered (also when
     * the offer is not newer than [currentVersion]); throws [IllegalArgumentException] when the
     * answer is not the expected JSON or names an unsafe download.
     */
    fun parseOffer(body: String, modelCode: String, currentVersion: String): EyevueOtaOffer? {
        val root = runCatching { json.parseToJsonElement(body) as JsonObject }.getOrNull()
            ?: throw IllegalArgumentException("The EyeVue update server sent an unexpected answer.")
        val success = (root["success"] as? JsonPrimitive)?.contentOrNull
        if (success != null && success != "true") throw IllegalArgumentException("The EyeVue update server refused the check.")
        val data = root["data"] as? JsonObject ?: return null
        fun pkg(lane: String) = ((data[lane] as? JsonObject)?.get("OtaPackage") as? JsonObject)
        fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        val ble = pkg("ble") ?: return null
        val url = ble.str("otaArray").ifEmpty { ble.str("bluetoothUrl") }
        val version = ble.str("deviceVersion")
        if (url.isEmpty() || version.isEmpty()) return null
        if (!isAllowedDownload(url) || !url.substringBefore('?').endsWith(".ufw", ignoreCase = true)) {
            throw IllegalArgumentException("The EyeVue update server named an unexpected download.")
        }
        val packageCode = ble.str("code")
        if (packageCode.isNotEmpty() && !packageCode.equals(modelCode, ignoreCase = true)) {
            throw IllegalArgumentException("The EyeVue update server answered for model $packageCode, not $modelCode.")
        }
        if (FamilyFirmwareRules.compareVersions(version, currentVersion) <= 0) return null
        val wifi = pkg("wifi")
        return EyevueOtaOffer(
            modelCode = modelCode,
            version = version,
            bleUrl = url,
            packageName = ble.str("packageName"),
            includesWifiPart = wifi != null && wifi.str("wifiUrl").ifEmpty { wifi.str("otaArray") }.isNotEmpty(),
        )
    }

    /** Only HTTPS downloads from the vendor's Alibaba OSS buckets. */
    fun isAllowedDownload(url: String): Boolean {
        if (!url.startsWith("https://", ignoreCase = true)) return false
        val host = url.removePrefix("https://").removePrefix("HTTPS://").substringBefore('/').substringBefore(':').lowercase()
        return host.endsWith(".aliyuncs.com") && host.all { it.isLetterOrDigit() || it == '.' || it == '-' }
    }

    fun expectation(offer: EyevueOtaOffer, contentLength: Long?, contentMd5Base64: String?, etag: String?): DownloadExpectation {
        val md5 = contentMd5Base64?.let(::base64ToHex)
            ?: etag?.trim('"', ' ')?.lowercase()?.takeIf { it.matches(Regex("[0-9a-f]{32}")) }
        val pinned = FamilyFirmwareRules.knownEyevueImages.firstOrNull {
            it.modelCode.equals(offer.modelCode, ignoreCase = true) && FamilyFirmwareRules.compareVersions(it.version, offer.version) == 0
        }
        return DownloadExpectation(size = contentLength?.takeIf { it > 0 }, md5 = md5, sha256 = pinned?.sha256)
    }

    /** Null when the download matches [expected], otherwise why not. */
    fun verifyDownload(expected: DownloadExpectation, size: Long, md5Hex: String, sha256Hex: String): String? = when {
        expected.size == null -> "The download server did not state the file size."
        expected.size != size -> "The download is incomplete ($size of ${expected.size} bytes)."
        expected.md5 != null && !expected.md5.equals(md5Hex, ignoreCase = true) -> "The download is damaged (MD5 mismatch)."
        expected.sha256 != null && !expected.sha256.equals(sha256Hex, ignoreCase = true) -> "The download does not match the published firmware (sha256 mismatch)."
        expected.md5 == null && expected.sha256 == null -> "The download server gave no checksum to verify the file."
        else -> null
    }

    private fun base64ToHex(value: String): String? {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        val clean = value.trim().trimEnd('=')
        if (clean.isEmpty() || clean.any { it !in alphabet }) return null
        val bytes = mutableListOf<Int>()
        var buffer = 0
        var bits = 0
        for (c in clean) {
            buffer = (buffer shl 6) or alphabet.indexOf(c)
            bits += 6
            if (bits >= 8) {
                bits -= 8
                bytes += (buffer shr bits) and 0xFF
            }
        }
        return bytes.takeIf { it.size == 16 }?.joinToString("") { it.toString(16).padStart(2, '0') }
    }
}
