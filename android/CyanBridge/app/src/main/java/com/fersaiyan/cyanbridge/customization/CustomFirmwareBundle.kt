package com.fersaiyan.cyanbridge.customization

import com.fersaiyan.cyanbridge.shared.customization.CustomizationFamily
import com.fersaiyan.cyanbridge.shared.customization.GlassesFirmwareInfo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

class FirmwareFile(val name: String, val bytes: ByteArray)

/** A customer's emailed custom firmware, checked and split into what each family's OTA takes. */
data class CustomFirmwareBundle(
    val family: CustomizationFamily,
    val baseBleFirmwareVersion: String?,
    val baseWifiFirmwareVersion: String?,
    /** The version the new image reports, when the worker states it (TuneBuds GET_INFO). */
    val firmwareVersion: String?,
    val ble: FirmwareFile,
    val wifi: FirmwareFile?,
)

/**
 * Reads the emailed attachment. The format (recorded in the E2E contract, section 5):
 * - `.zip` with `manifest.json`:
 *   `{"format": "cyanbridge-custom-firmware", "format_version": 1, "device_family": "heycyan",
 *     "base_ble_firmware_version": "...", "base_wifi_firmware_version": "...", "firmware_version": "...",
 *     "files": [{"role": "ble", "name": "x.bin", "sha256": "..."}, {"role": "wifi", "name": "x.swu", "sha256": "..."}]}`
 *   HeyCyan needs `ble` (.bin) and `wifi` (.swu); EyeVue `ble` (.ufw); TuneBuds `ble` (.fot/.fota/.bin).
 * - a bare `.ufw` (EyeVue) or `.fot`/`.fota` (TuneBuds), without base-version checks.
 */
object CustomFirmwareBundleReader {
    const val FORMAT = "cyanbridge-custom-firmware"
    const val MANIFEST = "manifest.json"
    private const val MAX_TOTAL_BYTES = 96L * 1024 * 1024
    private val json = Json { ignoreUnknownKeys = true }

    fun read(fileName: String, bytes: ByteArray): CustomFirmwareBundle {
        val lower = fileName.lowercase()
        return when {
            lower.endsWith(".zip") || isZip(bytes) -> readZip(bytes)
            lower.endsWith(".ufw") -> CustomFirmwareBundle(CustomizationFamily.EYEVUE, null, null, null, FirmwareFile(fileName, bytes), null)
            lower.endsWith(".fot") || lower.endsWith(".fota") ->
                CustomFirmwareBundle(CustomizationFamily.TUNEBUDS, null, null, null, FirmwareFile(fileName, bytes), null)
            else -> throw IllegalArgumentException("Pick the .zip (or .ufw / .fot) file from the firmware email.")
        }
    }

    /** Null when [bundle] was built for these glasses, otherwise why not. */
    fun mismatch(bundle: CustomFirmwareBundle, glasses: GlassesFirmwareInfo): String? = when {
        bundle.family != glasses.family ->
            "This firmware is for ${bundle.family.label} glasses, but ${glasses.family.label} glasses are connected."
        bundle.baseBleFirmwareVersion != null && !bundle.baseBleFirmwareVersion.equals(glasses.bleFirmwareVersion, ignoreCase = true) ->
            "This firmware was built for Bluetooth firmware ${bundle.baseBleFirmwareVersion}, but the glasses run ${glasses.bleFirmwareVersion}."
        bundle.family == CustomizationFamily.HEYCYAN && bundle.baseWifiFirmwareVersion != null &&
            !bundle.baseWifiFirmwareVersion.equals(glasses.wifiFirmwareVersion.orEmpty(), ignoreCase = true) ->
            "This firmware was built for Wi-Fi firmware ${bundle.baseWifiFirmwareVersion}, but the glasses run ${glasses.wifiFirmwareVersion ?: "an unknown version"}."
        else -> null
    }

    private fun isZip(bytes: ByteArray) = bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()

    private fun readZip(bytes: ByteArray): CustomFirmwareBundle {
        val entries = mutableMapOf<String, ByteArray>()
        var total = 0L
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                val name = entry.name
                require(!name.contains("..") && !name.startsWith("/") && !name.contains('\\')) { "Unsafe file name in the firmware zip: $name" }
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = zip.read(buffer)
                    if (n < 0) break
                    total += n
                    require(total <= MAX_TOTAL_BYTES) { "The firmware zip is too large" }
                    out.write(buffer, 0, n)
                }
                entries[name] = out.toByteArray()
            }
        }
        val manifestBytes = entries[MANIFEST] ?: throw IllegalArgumentException("The firmware zip has no $MANIFEST")
        val manifest = runCatching { json.parseToJsonElement(manifestBytes.decodeToString()) as JsonObject }.getOrNull()
            ?: throw IllegalArgumentException("The firmware manifest is not valid JSON")
        fun str(key: String) = (manifest[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        require(str("format") == FORMAT) { "This zip is not a CyanBridge custom firmware" }
        val formatVersion = (manifest["format_version"] as? JsonPrimitive)?.intOrNull ?: 1
        require(formatVersion == 1) { "Update CyanBridge to install this firmware (format $formatVersion)" }
        val family = CustomizationFamily.fromWire(str("device_family"))
            ?: throw IllegalArgumentException("The firmware manifest names no known device family")
        val files = (manifest["files"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.associate { file ->
            fun fileStr(key: String) = (file[key] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            val role = fileStr("role").lowercase()
            val name = fileStr("name")
            val data = entries[name] ?: throw IllegalArgumentException("The firmware zip is missing $name")
            val expected = fileStr("sha256").lowercase()
            require(expected.matches(Regex("[0-9a-f]{64}"))) { "The manifest has no valid sha256 for $name" }
            require(sha256Hex(data) == expected) { "$name is damaged (sha256 mismatch)" }
            role to FirmwareFile(name.substringAfterLast('/'), data)
        }
        val ble = files["ble"] ?: throw IllegalArgumentException("The firmware zip has no Bluetooth image")
        val wifi = files["wifi"]
        when (family) {
            CustomizationFamily.HEYCYAN -> {
                require(ble.name.endsWith(".bin", ignoreCase = true)) { "The HeyCyan Bluetooth image must be a .bin" }
                require(wifi != null && wifi.name.endsWith(".swu", ignoreCase = true)) {
                    "HeyCyan firmware needs both the Wi-Fi .swu and the Bluetooth .bin"
                }
            }
            CustomizationFamily.EYEVUE -> require(ble.name.endsWith(".ufw", ignoreCase = true)) { "The EyeVue image must be a .ufw" }
            CustomizationFamily.TUNEBUDS -> require(
                listOf(".fot", ".fota", ".bin").any { ble.name.endsWith(it, ignoreCase = true) },
            ) { "The TuneBuds image must be a .fot" }
        }
        return CustomFirmwareBundle(
            family = family,
            baseBleFirmwareVersion = str("base_ble_firmware_version"),
            baseWifiFirmwareVersion = str("base_wifi_firmware_version"),
            firmwareVersion = str("firmware_version"),
            ble = ble,
            wifi = wifi,
        )
    }

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
