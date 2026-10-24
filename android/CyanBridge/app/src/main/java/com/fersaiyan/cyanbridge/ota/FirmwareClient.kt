package com.fersaiyan.cyanbridge.ota

import android.content.Context
import android.net.Uri
import android.util.Log
import com.fersaiyan.cyanbridge.agent.ProSubscriptionServerPrefs
import com.fersaiyan.cyanbridge.shared.glasses.OtaFirmwareSource
import com.fersaiyan.cyanbridge.shared.prefs.AppPrefs
import com.fersaiyan.cyanbridge.shared.prefs.get
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** The server artifact must match the transport selected by the user. */
fun OtaTarget.expectedFirmwareExtension(): String = when (this) {
    OtaTarget.V821_WIFI -> ".swu"
    OtaTarget.JIELI_BLE -> ".bin"
}

fun OtaTarget.isExpectedFirmwareFilename(filename: String): Boolean =
    filename.endsWith(expectedFirmwareExtension(), ignoreCase = true)

internal fun OtaFirmwareSource.serverChannel(): String? = when (this) {
  OtaFirmwareSource.PERSONAL_FILE -> null
  OtaFirmwareSource.STEALTH_CATALOG -> "stealth"
  OtaFirmwareSource.DEBUG_CATALOG -> "debug"
}

/** The legacy stealth catalog is authorized as the server's LED channel. */
private fun OtaFirmwareSource.serverRiskChannel(): String? = when (this) {
    OtaFirmwareSource.PERSONAL_FILE -> null
    OtaFirmwareSource.STEALTH_CATALOG -> "led"
    OtaFirmwareSource.DEBUG_CATALOG -> "debug"
}

/** A server artifact is valid only when it was built from the exact reported base. */
internal fun isExactFirmwareBaseMatch(baseFirmwareVersion: String, currentFirmwareVersion: String): Boolean =
    baseFirmwareVersion.trim().isNotEmpty() &&
        baseFirmwareVersion.trim().equals(currentFirmwareVersion.trim(), ignoreCase = true)

/** All values come from one fresh syncDeviceInfo response before either OTA file is resolved. */
data class InstalledFirmwareVersions(
    val wifiHardwareVersion: String,
    val wifiFirmwareVersion: String,
    val bleHardwareVersion: String,
    val bleFirmwareVersion: String,
) {
    fun isComplete(): Boolean = listOf(
        wifiHardwareVersion,
        wifiFirmwareVersion,
        bleHardwareVersion,
        bleFirmwareVersion,
    ).all { it.isNotBlank() }

    fun hardwareVersionFor(target: OtaTarget): String = when (target) {
        OtaTarget.V821_WIFI -> wifiHardwareVersion
        OtaTarget.JIELI_BLE -> bleHardwareVersion
    }

    fun firmwareVersionFor(target: OtaTarget): String = when (target) {
        OtaTarget.V821_WIFI -> wifiFirmwareVersion
        OtaTarget.JIELI_BLE -> bleFirmwareVersion
    }
}

internal fun isSha256Hex(value: String): Boolean = value.matches(Regex("[a-fA-F0-9]{64}"))

/** Only this explicit relay contract may open the user-facing patch request flow. */
internal const val FIRMWARE_PATCH_UNAVAILABLE_STATUS = 409
internal const val FIRMWARE_PATCH_UNAVAILABLE_ERROR = "firmware_patch_unavailable"
private const val DEFAULT_PATCH_UNAVAILABLE_MESSAGE =
    "No approved firmware patch exists for this installed version."

internal fun isFirmwarePatchUnavailableResponse(statusCode: Int, error: String): Boolean =
    statusCode == FIRMWARE_PATCH_UNAVAILABLE_STATUS && error == FIRMWARE_PATCH_UNAVAILABLE_ERROR

internal fun firmwarePatchUnavailableMessage(
    statusCode: Int,
    error: String,
    message: String,
): String? {
    if (!isFirmwarePatchUnavailableResponse(statusCode, error)) return null
    return message
        .trim()
        .ifBlank { DEFAULT_PATCH_UNAVAILABLE_MESSAGE }
}

/** Keep patch requests on the same relay that resolved the firmware catalog. */
internal fun firmwareRelayBaseUrl(context: Context): String =
    AppPrefs.RelayServer.baseUrl[context]
        ?.trim()
        .takeUnless { it.isNullOrBlank() }
        ?: "https://cyanbridge.vercel.app"

/**
 * Result of a firmware catalog lookup + download.
 */
sealed class FirmwareResult {
    data class Ready(val file: File, val filename: String) : FirmwareResult()
    data class NotAvailable(val message: String, val hardwareVersion: String) : FirmwareResult()
    data class SubscriptionRequired(
        val message: String,
        val currentPlan: String,
        val requiredPlans: List<String>,
    ) : FirmwareResult()

    data class DebugAccessRequired(val message: String) : FirmwareResult()

    data class Error(val message: String) : FirmwareResult()
}

/**
 * Client for the CyanBridge server's firmware download API.
 *
 * Calls GET /api/firmware/download with one freshly reported Wi-Fi/BLE version
 * baseline. The relay returns each component only when both exact base records exist.
 */
class FirmwareClient(
    private val context: Context,
) {
    /**
     * Fetch firmware info from the server and download the firmware file.
     *
     * Both target-chip tuples come from the same fresh device-info response. The server
     * resolves each target only from its own exact installed version, and refuses both
     * downloads when either component lacks an exact catalog match.
     *
     * @param deviceVersions Current Wi-Fi and BLE identifiers from one device-info read
     * @param outputDir Directory to save the downloaded file
     * @param target OTA target: V821_WIFI (.swu) or JIELI_BLE (.bin)
     * @param source server catalog channel. Personal files are staged locally and never reach this client.
     * @return FirmwareResult indicating success, failure, or access gate
     */
    fun fetchAndDownload(
        deviceVersions: InstalledFirmwareVersions,
        outputDir: File,
        target: OtaTarget = OtaTarget.V821_WIFI,
        source: OtaFirmwareSource = OtaFirmwareSource.DEBUG_CATALOG,
    ): FirmwareResult {
        if (!deviceVersions.isComplete()) {
            return FirmwareResult.Error("Could not read all Wi-Fi and Bluetooth firmware identifiers")
        }
        val hardwareVersion = deviceVersions.hardwareVersionFor(target)
        val firmwareVersion = deviceVersions.firmwareVersionFor(target)
        val startTime = System.currentTimeMillis()
        Log.i(TAG, "=== FIRMWARE FETCH START ===")
        Log.i(TAG, "  hardwareVersion: $hardwareVersion")
        Log.i(TAG, "  firmwareVersion: $firmwareVersion")
        Log.i(TAG, "  target: $target")
        Log.i(TAG, "  source: $source")
        Log.i(TAG, "  outputDir: ${outputDir.absolutePath}")

        val serverChannel = source.serverChannel()
            ?: return FirmwareResult.Error("Personal firmware files must be imported from the file picker")
        val riskChannel = source.serverRiskChannel()
            ?: return FirmwareResult.Error("Personal firmware files do not use the server catalog")

        // Step 1: Resolve relay base URL
        val relayBase = firmwareRelayBaseUrl(context)
        Log.i(TAG, "[1/5] Relay base URL: $relayBase")
        if (!isHttpsRelayUrl(relayBase)) {
            Log.e(TAG, "[1/5] Refusing non-HTTPS firmware relay URL")
            return FirmwareResult.Error("Firmware relay must use HTTPS")
        }

        // Step 2: Get API token
        val apiToken = ProSubscriptionServerPrefs.getApiToken(context)
        val accountEmail = ProSubscriptionServerPrefs.getAccountEmail(context)
        if (apiToken.isBlank()) {
            Log.e(TAG, "[2/5] FAIL: No API token available. User not signed in.")
            return FirmwareResult.Error("No API token available. Please sign in to your CyanBridge account.")
        }
        Log.i(TAG, "[2/5] API token present (email: ${accountEmail.ifBlank { "(none)" }})")

        // The source picker requires a local acknowledgement before this method is
        // reached. Record the corresponding versioned server acknowledgement before
        // requesting any signed firmware URL.
        val acknowledgement = try {
            acknowledgeFirmwareRisk(relayBase, apiToken, riskChannel)
        } catch (e: Exception) {
            Log.e(TAG, "[3/5] Risk acknowledgement request failed", e)
            return FirmwareResult.Error("Could not record firmware risk acknowledgement: ${e.message}")
        }
        if (acknowledgement.statusCode !in 200..299) {
            val message = runCatching {
                JSONObject(acknowledgement.body).optString("message")
            }.getOrDefault("").ifBlank { "Firmware risk acknowledgement was not accepted" }
            return FirmwareResult.Error(message)
        }

        // Step 4: Call the firmware download endpoint
        val apiUrl = buildFirmwareApiUrl(
            relayBase = relayBase,
            deviceVersions = deviceVersions,
            target = target,
            serverChannel = serverChannel,
        )

        Log.i(TAG, "[4/5] Calling firmware API: $apiUrl")

        val response = try {
            httpGet(apiUrl, apiToken)
        } catch (e: Exception) {
            Log.e(TAG, "[4/5] FAIL: Firmware API request threw exception: ${e.javaClass.simpleName}: ${e.message}", e)
            return FirmwareResult.Error("Server request failed: ${e.message}")
        }

        if (response == null) {
            Log.e(TAG, "[4/5] FAIL: Firmware API returned null response")
            return FirmwareResult.Error("Empty response from server")
        }

        val statusCode = response.statusCode
        val body = response.body
        Log.i(TAG, "[4/5] Firmware API response: HTTP $statusCode (${body.length} bytes)")
        if (statusCode != 200) {
            Log.w(TAG, "[4/5] Non-200 response body: ${body.take(500)}")
        }

        // Step 4: Parse response based on status code
        return when (statusCode) {
            200 -> {
                val json = JSONObject(body)
                val downloadUrl = json.getString("download_url")
                val filename = json.getString("filename").trim()
                val objectKey = json.optString("object_key", "")
                val expiresIn = json.optInt("expires_in_seconds", 0)
                val responseHardwareVersion = json.optString("hardwareVersion", "").trim()
                val responseTarget = json.optString("target", "").trim()
                val baseFirmwareVersion = json.optString("base_firmware_version", "").trim()
                val expectedSha256 = json.optString("sha256", "").trim()
                val expectedSizeBytes = json.optLong("size_bytes", -1L)

                if (!isSafeFirmwareFilename(filename)) {
                    Log.e(TAG, "[4/5] Refusing unsafe firmware filename from server: $filename")
                    return FirmwareResult.Error("Server returned an unsafe firmware filename")
                }
                if (!target.isExpectedFirmwareFilename(filename)) {
                    Log.e(
                        TAG,
                        "[4/5] Refusing $filename for $target; expected ${target.expectedFirmwareExtension()} artifact",
                    )
                    return FirmwareResult.Error(
                        "Server returned $filename, but ${target.expectedFirmwareExtension()} firmware is required for $target",
                    )
                }
                if (!isExactFirmwareBaseMatch(responseHardwareVersion, hardwareVersion)) {
                    Log.e(
                        TAG,
                        "[4/5] Refusing $filename: server hardware=$responseHardwareVersion, current=$hardwareVersion",
                    )
                    return FirmwareResult.Error("Server returned firmware for a different hardware target")
                }
                if (responseTarget != target.serverTargetParameter()) {
                    Log.e(TAG, "[4/5] Refusing $filename: server target=$responseTarget, expected=$target")
                    return FirmwareResult.Error("Server returned firmware for a different OTA target")
                }
                if (!isExactFirmwareBaseMatch(baseFirmwareVersion, firmwareVersion)) {
                    Log.e(
                        TAG,
                        "[4/5] Refusing $filename: server base=$baseFirmwareVersion, current=$firmwareVersion",
                    )
                    return FirmwareResult.Error(
                        "Server firmware was not built from this chip's current version",
                    )
                }
                if (!responseMatchesInstalledFirmwareVersions(json, deviceVersions)) {
                    Log.e(TAG, "[4/5] Server did not echo the installed Wi-Fi/Bluetooth version baseline")
                    return FirmwareResult.Error("Server returned firmware for a different glasses version baseline")
                }
                if (!isSha256Hex(expectedSha256)) {
                    Log.e(TAG, "[4/5] Server returned an invalid firmware SHA-256")
                    return FirmwareResult.Error("Server returned invalid firmware integrity metadata")
                }
                if (expectedSizeBytes !in 1L..MAX_SERVER_FIRMWARE_SIZE_BYTES) {
                    Log.e(TAG, "[4/5] Server returned invalid firmware size: $expectedSizeBytes")
                    return FirmwareResult.Error("Server returned invalid firmware size metadata")
                }
                Log.i(TAG, "[4/5] Firmware available!")
                Log.i(TAG, "  filename: $filename")
                Log.i(TAG, "  object_key: $objectKey")
                Log.i(TAG, "  base_firmware_version: $baseFirmwareVersion")
                Log.i(TAG, "  expected_size: $expectedSizeBytes bytes")
                Log.i(TAG, "  signed_url_expires_in: ${expiresIn}s")

                // Step 5: Download the firmware file
                Log.i(TAG, "[5/5] Starting firmware download...")
                val file = downloadFirmware(
                    downloadUrl = downloadUrl,
                    filename = filename,
                    outputDir = outputDir,
                    expectedSha256 = expectedSha256,
                    expectedSizeBytes = expectedSizeBytes,
                )

                if (file != null) {
                    val elapsed = System.currentTimeMillis() - startTime
                    Log.i(TAG, "=== FIRMWARE FETCH SUCCESS === (${elapsed}ms)")
                    Log.i(TAG, "  file: ${file.absolutePath}")
                    Log.i(TAG, "  size: ${file.length()} bytes (${file.length() / 1024 / 1024} MB)")
                    FirmwareResult.Ready(file, filename)
                } else {
                    Log.e(TAG, "=== FIRMWARE FETCH FAIL === Firmware download returned null")
                    FirmwareResult.Error("Failed to download firmware file from storage")
                }
            }

            403 -> {
                val json = JSONObject(body)
                val error = json.optString("error", "")
                val message = json.optString("message", "Access denied")

                Log.w(TAG, "[4/5] Subscription gate: error=$error, message=$message")

                when (error) {
                    "subscription_required" -> {
                        val currentPlan = json.optString("currentPlan", "unknown")
                        val requiredPlans = mutableListOf<String>()
                        val arr = json.optJSONArray("requiredPlans")
                        if (arr != null) {
                            for (i in 0 until arr.length()) {
                                requiredPlans.add(arr.getString(i))
                            }
                        }
                        Log.w(TAG, "  currentPlan: $currentPlan, requiredPlans: $requiredPlans")
                        Log.i(TAG, "=== FIRMWARE FETCH BLOCKED (subscription) ===")
                        FirmwareResult.SubscriptionRequired(message, currentPlan, requiredPlans)
                    }

                    "subscription_expired" -> {
                        Log.w(TAG, "  Subscription expired")
                        Log.i(TAG, "=== FIRMWARE FETCH BLOCKED (expired) ===")
                        FirmwareResult.SubscriptionRequired(message, "expired", listOf("standard", "max"))
                    }

                    "debug_firmware_access_required" -> {
                        Log.w(TAG, "  Debug firmware entitlement is missing")
                        FirmwareResult.DebugAccessRequired(message)
                    }

                    "rate_limited" -> {
                        val retryAfterMs = json.optLong("retryAfterMs", 0)
                        Log.w(TAG, "  Rate limited! Retry after ${retryAfterMs}ms")
                        Log.i(TAG, "=== FIRMWARE FETCH BLOCKED (rate limit) ===")
                        FirmwareResult.Error("Too many requests. Please try again in ${retryAfterMs / 1000 / 2} minutes.")
                    }

                    else -> {
                        Log.e(TAG, "=== FIRMWARE FETCH FAIL (403 unknown) ===")
                        FirmwareResult.Error(message)
                    }
                }
            }

            FIRMWARE_PATCH_UNAVAILABLE_STATUS -> {
                val json = try { JSONObject(body) } catch (_: Exception) { JSONObject() }
                val error = json.optString("error", "")
                val patchUnavailableMessage = firmwarePatchUnavailableMessage(
                    statusCode = statusCode,
                    error = error,
                    message = json.optString("message", ""),
                )
                if (patchUnavailableMessage != null) {
                    Log.w(TAG, "[4/5] No exact-base firmware patch: $patchUnavailableMessage")
                    Log.i(TAG, "=== FIRMWARE FETCH NOT AVAILABLE ===")
                    FirmwareResult.NotAvailable(patchUnavailableMessage, hardwareVersion)
                } else {
                    val message = json.optString("message", "Firmware catalog conflict")
                    Log.e(TAG, "[4/5] Unexpected conflict response: error=$error, message=$message")
                    FirmwareResult.Error(message)
                }
            }

            401 -> {
                val json = JSONObject(body)
                val message = json.optString("message", "Authentication failed")
                Log.e(TAG, "[4/5] Auth failed: $message")
                Log.i(TAG, "=== FIRMWARE FETCH FAIL (auth) ===")
                FirmwareResult.Error("Auth failed: $message")
            }

            429 -> {
                val json = try { JSONObject(body) } catch (_: Exception) { JSONObject() }
                val retryAfter = json.optLong("retryAfterMs", 60_000)
                Log.e(TAG, "[4/5] Rate limited by server. Retry after ${retryAfter}ms")
                Log.i(TAG, "=== FIRMWARE FETCH FAIL (rate limit) ===")
                FirmwareResult.Error("Too many requests. Please wait a moment and try again.")
            }

            else -> {
                Log.e(TAG, "[4/5] Unexpected response: $statusCode — ${body.take(500)}")
                Log.i(TAG, "=== FIRMWARE FETCH FAIL (HTTP $statusCode) ===")
                FirmwareResult.Error("Server error ($statusCode)")
            }
        }
    }

    private fun downloadFirmware(
        downloadUrl: String,
        filename: String,
        outputDir: File,
        expectedSha256: String,
        expectedSizeBytes: Long,
    ): File? {
        val downloadStart = System.currentTimeMillis()
        var connection: HttpURLConnection? = null
        var partialFile: File? = null
        return try {
            if (!outputDir.exists() && !outputDir.mkdirs()) {
                Log.e(TAG, "Could not create firmware directory: ${outputDir.absolutePath}")
                return null
            }
            if (!outputDir.isDirectory) {
                Log.e(TAG, "Firmware output path is not a directory: ${outputDir.absolutePath}")
                return null
            }
            if (!isSafeFirmwareFilename(filename)) {
                Log.e(TAG, "Refusing unsafe firmware filename: $filename")
                return null
            }

            val outFile = File(outputDir, filename)
            val stagedFile = File(outputDir, "$filename.download")
            partialFile = stagedFile
            if (stagedFile.exists() && !stagedFile.delete()) {
                Log.e(TAG, "Could not remove stale partial firmware file: ${stagedFile.absolutePath}")
                return null
            }

            val url = URL(downloadUrl)
            if (!url.protocol.equals("https", ignoreCase = true)) {
                Log.e(TAG, "Refusing non-HTTPS firmware URL")
                return null
            }
            Log.d(TAG, "Opening HTTPS connection to firmware storage...")
            val conn = (url.openConnection() as? HttpURLConnection) ?: run {
                Log.e(TAG, "Firmware URL is not an HTTP(S) URL")
                return null
            }
            connection = conn
            conn.requestMethod = "GET"
            conn.connectTimeout = 30_000
            conn.readTimeout = 300_000 // 5 min for large files
            conn.instanceFollowRedirects = true

            Log.d(TAG, "Waiting for response...")
            val responseCode = conn.responseCode
            Log.d(TAG, "Response code: $responseCode")

            if (responseCode != HttpURLConnection.HTTP_OK) {
                Log.e(TAG, "Firmware download failed: HTTP $responseCode")
                val errorBody = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "(no body)"
                Log.e(TAG, "Error body: ${errorBody.take(300)}")
                return null
            }
            if (!conn.url.protocol.equals("https", ignoreCase = true)) {
                Log.e(TAG, "Refusing firmware redirect to non-HTTPS URL")
                return null
            }

            val contentLength = conn.contentLengthLong
            val contentType = conn.contentType
            Log.i(TAG, "Content-Length: $contentLength bytes, Content-Type: $contentType")
            if (contentLength >= 0L && contentLength != expectedSizeBytes) {
                Log.e(TAG, "Firmware size mismatch before download: expected $expectedSizeBytes, got $contentLength")
                return null
            }

            val buf = ByteArray(8192)
            var totalRead = 0L
            var lastLogAt = 0L

            conn.inputStream.use { input ->
                FileOutputStream(stagedFile).use { output ->
                    while (true) {
                        val read = input.read(buf)
                        if (read == -1) break
                        if (read == 0) continue
                        output.write(buf, 0, read)
                        totalRead += read

                        // Log progress every 2 seconds
                        val now = System.currentTimeMillis()
                        if (now - lastLogAt > 2000) {
                            val pct = if (contentLength > 0) (totalRead * 100 / contentLength) else -1
                            val elapsedSec = (now - downloadStart) / 1000.0
                            val speedKbps = if (elapsedSec > 0) (totalRead / 1024.0 / elapsedSec) else 0.0
                            Log.d(TAG, "Download progress: $totalRead / $contentLength bytes ($pct%) — ${speedKbps.toInt()} KB/s")
                            lastLogAt = now
                        }
                    }
                    output.flush()
                }
            }

            val elapsed = System.currentTimeMillis() - downloadStart
            val avgSpeed = if (elapsed > 0) (totalRead / 1024.0 / (elapsed / 1000.0)) else 0.0
            Log.i(TAG, "Firmware download complete: $totalRead bytes in ${elapsed}ms (${avgSpeed.toInt()} KB/s avg)")

            if (totalRead <= 0L) {
                Log.e(TAG, "Firmware download was empty")
                return null
            }
            if (totalRead != expectedSizeBytes) {
                Log.e(TAG, "Firmware size mismatch: expected $expectedSizeBytes, got $totalRead")
                return null
            }
            val actualSha256 = sha256Hex(stagedFile)
            if (!actualSha256.equals(expectedSha256, ignoreCase = true)) {
                Log.e(TAG, "Firmware SHA-256 mismatch; discarding staged download")
                return null
            }
            if (outFile.exists() && !outFile.delete()) {
                Log.e(TAG, "Could not replace existing firmware file: ${outFile.absolutePath}")
                return null
            }
            if (!stagedFile.renameTo(outFile)) {
                Log.e(TAG, "Could not finalize firmware file: ${outFile.absolutePath}")
                return null
            }
            Log.i(TAG, "Saved to: ${outFile.absolutePath}")

            outFile
        } catch (error: Exception) {
            Log.e(TAG, "Firmware download exception: ${error.javaClass.simpleName}: ${error.message}", error)
            null
        } finally {
            connection?.disconnect()
            partialFile?.takeIf { it.exists() }?.delete()
        }
    }

    private fun buildFirmwareApiUrl(
        relayBase: String,
        deviceVersions: InstalledFirmwareVersions,
        target: OtaTarget,
        serverChannel: String,
    ): String {
        val targetParam = target.serverTargetParameter()
        // Both requests send one immutable baseline read from the glasses. The server
        // rejects either download unless both component records exactly match it.
        val builder = Uri.parse(relayBase.trimEnd('/') + "/api/firmware/download")
            .buildUpon()
            .appendQueryParameter("wifiHardwareVersion", deviceVersions.wifiHardwareVersion)
            .appendQueryParameter("wifiFirmwareVersion", deviceVersions.wifiFirmwareVersion)
            .appendQueryParameter("bleHardwareVersion", deviceVersions.bleHardwareVersion)
            .appendQueryParameter("bleFirmwareVersion", deviceVersions.bleFirmwareVersion)
            .appendQueryParameter("target", targetParam)
            .appendQueryParameter("channel", serverChannel)
        return builder.build().toString()
    }

    private fun isSafeFirmwareFilename(filename: String): Boolean =
        filename.isNotBlank() &&
            !filename.contains('/') &&
            !filename.contains('\\') &&
            File(filename).name == filename

    private fun isHttpsRelayUrl(url: String): Boolean {
        val uri = Uri.parse(url)
        return uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank()
    }

    private fun OtaTarget.serverTargetParameter(): String = when (this) {
        OtaTarget.V821_WIFI -> "v821"
        OtaTarget.JIELI_BLE -> "jieli"
    }

    private fun responseMatchesInstalledFirmwareVersions(
        response: JSONObject,
        deviceVersions: InstalledFirmwareVersions,
    ): Boolean =
        isExactFirmwareBaseMatch(
            response.optString("wifi_hardware_version", ""),
            deviceVersions.wifiHardwareVersion,
        ) && isExactFirmwareBaseMatch(
            response.optString("wifi_firmware_version", ""),
            deviceVersions.wifiFirmwareVersion,
        ) && isExactFirmwareBaseMatch(
            response.optString("ble_hardware_version", ""),
            deviceVersions.bleHardwareVersion,
        ) && isExactFirmwareBaseMatch(
            response.optString("ble_firmware_version", ""),
            deviceVersions.bleFirmwareVersion,
        )

    private fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private data class HttpResponse(val statusCode: Int, val body: String)

    private fun acknowledgeFirmwareRisk(
        relayBase: String,
        apiToken: String,
        channel: String,
    ): HttpResponse {
        val url = relayBase.trimEnd('/') + "/api/firmware/risk-acknowledgement"
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "POST"
            conn.setRequestProperty("Authorization", "Bearer $apiToken")
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.instanceFollowRedirects = false
            conn.doOutput = true
            conn.outputStream.use { output ->
                output.write(
                    JSONObject()
                        .put("channel", channel)
                        .put("accepted", true)
                        .toString()
                        .toByteArray(),
                )
            }

            val statusCode = conn.responseCode
            val stream = if (statusCode in 200..299) conn.inputStream else conn.errorStream
            HttpResponse(statusCode, stream?.bufferedReader()?.use { it.readText() } ?: "")
        } finally {
            conn.disconnect()
        }
    }

    private fun httpGet(url: String, apiToken: String): HttpResponse? {
        Log.d(TAG, "HTTP GET: $url")
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "GET"
            // The request needs the complete token; never log it.
            conn.setRequestProperty("Authorization", "Bearer $apiToken")
            conn.setRequestProperty("Accept", "application/json")
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            // Do not follow a redirect with the bearer credential to an unverified destination.
            conn.instanceFollowRedirects = false

            val statusCode = conn.responseCode
            Log.d(TAG, "HTTP response: $statusCode")

            val stream = if (statusCode in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
            Log.d(TAG, "Response body length: ${body.length} bytes")

            HttpResponse(statusCode, body)
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private const val TAG = "FirmwareClient"
        private const val MAX_SERVER_FIRMWARE_SIZE_BYTES = 50L * 1024L * 1024L
    }
}
