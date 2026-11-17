package com.fersaiyan.cyanbridge.customization

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.fersaiyan.cyanbridge.ota.FirmwareClient
import com.fersaiyan.cyanbridge.ota.FirmwareResult
import com.fersaiyan.cyanbridge.ota.InstalledFirmwareVersions
import com.fersaiyan.cyanbridge.ota.customFirmwareHost
import com.fersaiyan.cyanbridge.ota.allowsDebugLocalHttp
import com.fersaiyan.cyanbridge.agent.AndroidProAccountPlatform
import com.fersaiyan.cyanbridge.agent.ProSubscriptionServerPrefs
import com.fersaiyan.cyanbridge.ai.router.AiProviderPrefs
import com.fersaiyan.cyanbridge.devices.DeviceProfileStore
import com.fersaiyan.cyanbridge.devices.eyevue.EyevueManager
import com.fersaiyan.cyanbridge.devices.tunebuds.TuneBudsManager
import com.fersaiyan.cyanbridge.glasses.GlassesDeviceInfoReader
import com.fersaiyan.cyanbridge.shared.customization.CustomizationFamily
import com.fersaiyan.cyanbridge.shared.customization.CustomizationPlatform
import com.fersaiyan.cyanbridge.shared.customization.GlassesDetection
import com.fersaiyan.cyanbridge.shared.customization.GlassesFirmwareInfo
import com.fersaiyan.cyanbridge.shared.customization.GlassesMicCapture
import com.fersaiyan.cyanbridge.shared.customization.HttpResult
import com.fersaiyan.cyanbridge.shared.customization.PickedDocument
import com.fersaiyan.cyanbridge.shared.devices.DeviceClass
import com.fersaiyan.cyanbridge.shared.devices.tunebuds.TuneBudsFota
import com.oudmon.ble.base.bluetooth.BleOperateManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Android side of the customization card: the relay API (the AI relay base URL and the account
 * token), document reads, the selected glasses and their microphone and update paths.
 * HeyCyan installs go to the dashboard's combined OTA (the live OtaCoordinator).
 */
class AndroidCustomizationPlatform(context: Context) : CustomizationPlatform {
    private val context = context.applicationContext

    override fun plan(): String = AndroidProAccountPlatform(context).localStatus().let { if (it.active) it.plan else "none" }

    override suspend fun detectGlasses(): GlassesDetection {
        val profile = DeviceProfileStore.loadLastSelected(context)
        val name = profile?.advertisedName.orEmpty()
        return when (profile?.selectedClass) {
            DeviceClass.HEY_CYAN -> {
                if (!BleOperateManager.getInstance().isConnected) return missing("Connect your HeyCyan glasses, then check again.")
                val info = GlassesDeviceInfoReader.readGlassesDeviceInfo()
                    ?: return missing("The HeyCyan glasses did not report their firmware versions. Check again.")
                found(
                    CustomizationFamily.HEYCYAN, name.ifBlank { "HeyCyan" },
                    bleHw = info.hardwareVersion, bleFw = info.firmwareVersion,
                    wifiHw = info.wifiHardwareVersion, wifiFw = info.wifiFirmwareVersion,
                )
            }
            DeviceClass.EYEVUE -> {
                val manager = EyevueManager.getInstance(context)
                if (!manager.isConnected()) return missing("Connect your EyeVue glasses, then check again.")
                val info = manager.readRcspTargetInfo()
                    ?: return missing("The EyeVue glasses did not report their firmware version. Check again.")
                val version = info.version ?: return missing("The EyeVue glasses reported no firmware version.")
                val state = manager.state.value
                val hardware = listOfNotNull(state.project, state.customer).joinToString("").ifBlank { info.projectCode ?: "BR28" }
                found(CustomizationFamily.EYEVUE, state.deviceName ?: name.ifBlank { "EyeVue" }, hardware, shortVersion(version), null, null)
            }
            DeviceClass.TUNEBUDS -> {
                val manager = TuneBudsManager.getInstance(context)
                if (!manager.isConnected()) return missing("Connect your TuneBuds glasses, then check again.")
                val state = manager.refreshVersions()
                val version = state.firmwareVersion ?: return missing("The TuneBuds glasses did not report their firmware version. Check again.")
                found(CustomizationFamily.TUNEBUDS, state.model ?: name.ifBlank { "TuneBuds" }, state.model ?: "BT895x", version, null, state.coprocessorVersion)
            }
            else -> missing("Select and connect HeyCyan, EyeVue or TuneBuds glasses to customize them.")
        }
    }

    private fun missing(message: String) = GlassesDetection.Missing(message)

    private fun found(
        family: CustomizationFamily,
        model: String,
        bleHw: String?,
        bleFw: String?,
        wifiHw: String?,
        wifiFw: String?,
    ): GlassesDetection {
        val ble = versionToken(bleFw) ?: return missing("The glasses reported no Bluetooth firmware version.")
        return GlassesDetection.Found(
            GlassesFirmwareInfo(
                family = family,
                model = model.filter { it.isLetterOrDigit() || it in " ._()+-" }.trim().take(64).ifBlank { family.label },
                bleHardwareVersion = versionToken(bleHw) ?: family.wire,
                bleFirmwareVersion = ble,
                wifiHardwareVersion = versionToken(wifiHw),
                wifiFirmwareVersion = versionToken(wifiFw),
            ),
        )
    }

    // ── Relay HTTP ──

    override suspend fun api(method: String, path: String, body: String?): HttpResult =
        relay(method, path, body?.toByteArray(), "application/json", emptyMap(), readTimeoutMs = 60_000)

    override suspend fun apiBytes(
        method: String,
        path: String,
        contentType: String,
        headers: Map<String, String>,
        bytes: ByteArray,
    ): HttpResult = relay(method, path, bytes, contentType, headers, readTimeoutMs = 120_000)

    /** One relay request with the account token; a body is streamed with a fixed length. */
    private fun relay(
        method: String,
        path: String,
        body: ByteArray?,
        contentType: String,
        headers: Map<String, String>,
        readTimeoutMs: Int,
    ): HttpResult {
        val base = AiProviderPrefs.getRelayBaseUrl(context).trim().trimEnd('/')
        require(isAllowedEndpoint(base)) { "The relay URL must use HTTPS" }
        val token = ProSubscriptionServerPrefs.getApiToken(context)
        if (token.isBlank()) return HttpResult(401, """{"error":"missing_token","message":"Sign in to your CyanBridge account first."}""")
        val conn = URL(base + path).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 20_000
            conn.readTimeout = readTimeoutMs
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("Accept", "application/json")
            headers.forEach { (key, value) -> conn.setRequestProperty(key, value) }
            if (body != null) {
                conn.doOutput = true
                conn.setFixedLengthStreamingMode(body.size)
                conn.setRequestProperty("Content-Type", contentType)
                conn.outputStream.use { it.write(body) }
            }
            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            return HttpResult(status, stream?.use { it.readBytes().decodeToString() }.orEmpty())
        } finally {
            conn.disconnect()
        }
    }

    override fun sha256Hex(bytes: ByteArray): String = CustomFirmwareBundleReader.sha256Hex(bytes)

    override suspend fun readDocument(ref: String): PickedDocument {
        val uri = Uri.parse(ref)
        val resolver = context.contentResolver
        val name = runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
            }
        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "file"
        val bytes = resolver.openInputStream(uri)?.use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
                if (out.size() > MAX_DOCUMENT_BYTES) throw IOException("The file is too large")
            }
            out.toByteArray()
        } ?: throw IOException("The file cannot be read")
        return PickedDocument(name, resolver.getType(uri), bytes)
    }

    override fun micCapture(family: CustomizationFamily): GlassesMicCapture = when (family) {
        CustomizationFamily.HEYCYAN -> HeyCyanMicCapture
        CustomizationFamily.EYEVUE -> EyevueMicCapture(context)
        CustomizationFamily.TUNEBUDS -> TuneBudsMicCapture(context)
    }

    // ── Install ──

    override suspend fun installFirmware(ref: String, glasses: GlassesFirmwareInfo, progress: (String) -> Unit): String {
        val document = readDocument(ref)
        val bundle = CustomFirmwareBundleReader.read(document.name, document.bytes)
        CustomFirmwareBundleReader.mismatch(bundle, glasses)?.let { throw IllegalStateException(it) }
        return when (bundle.family) {
            CustomizationFamily.HEYCYAN -> {
                val dir = File(context.filesDir, "ota/custom").apply { mkdirs() }
                val stamp = System.currentTimeMillis()
                val wifi = bundle.wifi?.let { attached -> File(dir, "custom_wifi_$stamp.swu").apply { writeBytes(attached.bytes) } }
                    ?: fetchCatalogWifi(requireNotNull(bundle.wifiCatalog), File(dir, "wifi_$stamp"), progress)
                val ble = File(dir, "custom_ble_$stamp.bin").apply { writeBytes(bundle.ble.bytes) }
                val coordinator = customFirmwareHost?.get()
                val refusal = if (coordinator == null) {
                    "Open CyanBridge's main screen and try again."
                } else {
                    withContext(Dispatchers.Main) { coordinator.startCustomFirmware(wifi, ble) }
                }
                refusal?.let { reason ->
                    wifi.delete()
                    ble.delete()
                    throw IllegalStateException(reason)
                }
                "Installing: the Wi-Fi part first, then Bluetooth. Follow the progress in the Glasses dashboard's firmware section."
            }
            CustomizationFamily.EYEVUE -> {
                progress("Sending the firmware to the EyeVue glasses…")
                val result = EyevueManager.getInstance(context).installRcspFirmware(bundle.ble.bytes, progress)
                if (!result.startsWith("ok")) throw IllegalStateException(result.removePrefix("error: "))
                "Installed (${result.removePrefix("ok: ")}). The glasses restarted with your firmware."
            }
            CustomizationFamily.TUNEBUDS -> {
                val md5 = MessageDigest.getInstance("MD5").digest(bundle.ble.bytes).copyOf(4)
                val version = TuneBudsFota.versionCode(bundle.firmwareVersion ?: glasses.bleFirmwareVersion)
                var lastPercent = -1
                val outcome = TuneBudsManager.getInstance(context).installFirmware(bundle.ble.bytes, md5, version) { sent, total ->
                    val percent = (sent * 100 / total.coerceAtLeast(1)).toInt()
                    if (percent != lastPercent) {
                        lastPercent = percent
                        progress("Sending the firmware: $percent%")
                    }
                }
                when (outcome) {
                    TuneBudsFota.Outcome.Finished -> "Installed. The glasses verified the firmware and restart with it."
                    is TuneBudsFota.Outcome.Failed -> throw IllegalStateException(outcome.message)
                }
            }
        }
    }

    /** Downloads the bundle's Wi-Fi `.swu` from the firmware catalog (the Patched LED OTA path). */
    private suspend fun fetchCatalogWifi(ref: WifiCatalogRef, dir: File, progress: (String) -> Unit): File {
        progress("Downloading the Wi-Fi firmware (${ref.channel})…")
        val info = GlassesDeviceInfoReader.readGlassesDeviceInfo()
            ?: throw IllegalStateException("The glasses did not report their firmware versions")
        val versions = InstalledFirmwareVersions(
            wifiHardwareVersion = info.wifiHardwareVersion.orEmpty(),
            wifiFirmwareVersion = info.wifiFirmwareVersion.orEmpty(),
            bleHardwareVersion = info.hardwareVersion.orEmpty(),
            bleFirmwareVersion = info.firmwareVersion.orEmpty(),
        )
        return when (val result = FirmwareClient(context).fetchCustomizationWifiFirmware(ref.channel, versions, ref.sha256, ref.size, dir)) {
            is FirmwareResult.Ready -> result.file
            is FirmwareResult.SubscriptionRequired -> throw IllegalStateException("${result.message} (plan: ${result.currentPlan})")
            is FirmwareResult.NotAvailable -> throw IllegalStateException(result.message)
            is FirmwareResult.DebugAccessRequired -> throw IllegalStateException(result.message)
            is FirmwareResult.Error -> throw IllegalStateException(result.message)
        }
    }

    companion object {
        private const val MAX_DOCUMENT_BYTES = 96 * 1024 * 1024

        /** `[A-Za-z0-9._-]{1,128}` as the server requires, or null. */
        internal fun versionToken(raw: String?): String? =
            raw?.trim()?.filter { it.isLetterOrDigit() && it.code < 128 || it in "._-" }?.take(128)?.takeIf { it.isNotEmpty() }

        /** RCSP versions are 4 nibbles; the shipped files name them `1.3.6`, so trailing `.0` parts are dropped (min 3 parts). */
        internal fun shortVersion(version: String): String {
            val parts = version.split('.').toMutableList()
            while (parts.size > 3 && parts.last() == "0") parts.removeAt(parts.lastIndex)
            return parts.joinToString(".")
        }

        /** Recordings and tone files go over HTTPS; debug builds also allow the local stack (see [allowsDebugLocalHttp]). */
        internal fun isAllowedEndpoint(url: String): Boolean {
            val parsed = runCatching { URL(url) }.getOrNull() ?: return false
            return (parsed.protocol.equals("https", ignoreCase = true) && parsed.host.isNotBlank()) || allowsDebugLocalHttp(url)
        }
    }
}
