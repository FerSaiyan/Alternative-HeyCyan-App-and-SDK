package com.fersaiyan.cyanbridge.ota

import com.fersaiyan.cyanbridge.ui.nav.AppRoute
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.fersaiyan.cyanbridge.BuildConfig
import com.fersaiyan.cyanbridge.agent.ProSubscriptionDestination
import com.fersaiyan.cyanbridge.agent.ProSubscriptionServerPrefs
import com.fersaiyan.cyanbridge.glasses.GlassesDeviceInfoReader
import com.fersaiyan.cyanbridge.glasses.GlassesSession
import com.fersaiyan.cyanbridge.glasses.GlassesSessionLease
import com.fersaiyan.cyanbridge.media.autocapture.AutoAudioCaptureService
import com.fersaiyan.cyanbridge.shared.glasses.FirmwarePatchRequestUiState
import com.fersaiyan.cyanbridge.shared.glasses.GlassesDashboardUiState
import com.fersaiyan.cyanbridge.shared.glasses.OtaFirmwareSource
import com.fersaiyan.cyanbridge.shared.glasses.OtaSectionUiState
import com.fersaiyan.cyanbridge.ui.debug.DebugLogSupport
import com.fersaiyan.cyanbridge.ui.hasBluetooth
import com.fersaiyan.cyanbridge.ui.hasWifiP2pPermission
import com.oudmon.ble.base.bluetooth.BleOperateManager
import com.oudmon.ble.base.bluetooth.DeviceManager
import com.oudmon.ble.base.communication.LargeDataHandler
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Glasses firmware update flows (personal files, catalog artifacts, patch requests) and the
 * debug OTA probes. Owns the preparation phase before [OtaManager] takes over and mirrors the
 * OTA state into the dashboard.
 */
internal class OtaCoordinator(
    private val activity: AppCompatActivity,
    private val otaManagerProvider: () -> OtaManager,
    private val host: Host,
) {
    interface Host {
        var dashboardState: GlassesDashboardUiState
        var otaSessionLease: GlassesSessionLease?
        val glassesTeardownScope: CoroutineScope
        fun rejectHeyCyanOnlyFeature(feature: String): Boolean
        fun isGlassesCommandBlocked(source: String): Boolean
        fun ensureGlassesTransportPermissions(feature: String, onGranted: () -> Unit)
        fun acquireExclusiveGlassesSession(session: GlassesSession): GlassesSessionLease?
        fun releaseExclusiveGlassesSession(lease: GlassesSessionLease?)
        fun launchPersonalFirmwarePicker()
    }

    private val otaManager: OtaManager get() = otaManagerProvider()
    private val bleReadiness = OtaBleReadiness()
    var otaPreparationJob: Job? = null
    private var pendingPersonalFirmwareTarget: OtaTarget? = null
    private var stagedPersonalWifiFirmware: File? = null

    private var otaExpectedDeviceAddress: String?
        get() = bleReadiness.otaExpectedDeviceAddress
        set(value) {
            bleReadiness.otaExpectedDeviceAddress = value
        }

    private var dashboardState: GlassesDashboardUiState
        get() = host.dashboardState
        set(value) {
            host.dashboardState = value
        }

    private var otaSessionLease: GlassesSessionLease?
        get() = host.otaSessionLease
        set(value) {
            host.otaSessionLease = value
        }

    fun dumpOtaServerInfo() {
        if (host.rejectHeyCyanOnlyFeature("HeyCyan OTA")) return
        if (!BleOperateManager.getInstance().isConnected) {
            Log.e("OTAProbe", "Bluetooth not connected. Please connect to glasses first.")
            Toast.makeText(
                activity,
                "Bluetooth not connected. Please connect to glasses first.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        LargeDataHandler.getInstance().syncDeviceInfo { _, response ->
            if (response == null) {
                Log.e("OTAProbe", "syncDeviceInfo returned null response")
                activity.runOnUiThread {
                    Toast.makeText(
                        activity,
                        "Failed to read device info for OTA",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                return@syncDeviceInfo
            }

            val wifiHw = response.wifiHardwareVersion ?: ""
            val wifiFw = response.wifiFirmwareVersion ?: ""
            val btFw = response.firmwareVersion ?: ""
            val hw = response.hardwareVersion ?: ""

            // OTA binary URL used by the official app's debug/down path.
            val otaBinaryUrl =
                "https://qcwxfactory.oss-cn-beijing.aliyuncs.com/bin/glasses/${wifiHw}.swu"

            // Try to download the OTA file directly into the app's files dir
            // so you can pull it with `adb` for inspection.
            val otaDir = File(activity.getExternalFilesDir(null), "ota")
            if (!otaDir.exists()) {
                otaDir.mkdirs()
            }
            val outFile = File(otaDir, "${wifiHw}.swu")

            CoroutineScope(Dispatchers.IO).launch {
                try {
                    Log.i(
                        "OTAProbe",
                        "Attempting OTA binary download to: ${outFile.absolutePath}"
                    )
                    val url = URL(otaBinaryUrl)
                    val conn = url.openConnection() as HttpURLConnection
                    conn.requestMethod = "GET"
                    conn.connectTimeout = 15000
                    conn.readTimeout = 60000

                    if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                        conn.inputStream.use { input ->
                            FileOutputStream(outFile).use { output ->
                                val buffer = ByteArray(8 * 1024)
                                while (true) {
                                    val read = input.read(buffer)
                                    if (read <= 0) break
                                    output.write(buffer, 0, read)
                                }
                                output.flush()
                            }
                        }
                        Log.i(
                            "OTAProbe",
                            "OTA binary download completed: ${outFile.absolutePath} (size=${outFile.length()} bytes)"
                        )
                    } else {
                        Log.e(
                            "OTAProbe",
                            "OTA binary download failed, HTTP ${conn.responseCode}"
                        )
                    }
                    conn.disconnect()
                } catch (e: Exception) {
                    Log.e(
                        "OTAProbe",
                        "Exception while downloading OTA binary: ${e.message}",
                        e
                    )
                }
            }

            Log.i("OTAProbe", "==== OTA SERVER INFO START ====")
            Log.i("OTAProbe", "Device hardware version     : $hw")
            Log.i("OTAProbe", "WiFi hardware version       : $wifiHw")
            Log.i("OTAProbe", "WiFi firmware version       : $wifiFw")
            Log.i("OTAProbe", "Bluetooth firmware version  : $btFw")
            Log.i(
                "OTAProbe",
                "OTA metadata API (global)   : https://www.qlifesnap.com/glasses/app-update/last-ota"
            )
            Log.i(
                "OTAProbe",
                "OTA metadata API (China)    : https://www.qlifesnap.com/glasses/app-update/last-ota/china"
            )
            Log.i("OTAProbe", "OTA binary URL candidate    : $otaBinaryUrl")

            val lastOtaJsonTemplate = """
                {
                  "appId": <APP_ID>,
                  "uid": <USER_ID>,
                  "hardwareVersion": "$wifiHw",
                  "romVersion": "$wifiFw",
                  "os": 1,
                  "mac": "<PHONE_OR_BT_MAC>",
                  "country": "<COUNTRY_CODE>",
                  "dev": 2
                }
            """.trimIndent()

            Log.i("OTAProbe", "Sample LastOtaRequest JSON (fill in placeholders):")
            Log.i("OTAProbe", lastOtaJsonTemplate)
            Log.i(
                "OTAProbe",
                "Sample curl (metadata): curl -X POST 'https://www.qlifesnap.com/glasses/app-update/last-ota' -H 'Content-Type: application/json' -d '<JSON_ABOVE>'"
            )
            Log.i(
                "OTAProbe",
                "Sample curl (binary)  : curl -o '${wifiHw}.swu' '$otaBinaryUrl'"
            )
            Log.i("OTAProbe", "==== OTA SERVER INFO END ====")

            activity.runOnUiThread {
                Toast.makeText(
                    activity,
                    "OTA server info dumped to logcat (tag: OTAProbe)",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    /**
     * Minimal wrapper around LargeDataHandler.writeIpToSoc so we can observe
     * how the glasses behave when asked to fetch an OTA image from an HTTP
     * server under our control.
     *
     * This does not start any HTTP server on the phone; you must run one
     * yourself and point TEST_PULL_OTA_URL at it.
     */
    fun testPullModeOta() {
        if (host.rejectHeyCyanOnlyFeature("HeyCyan pull-mode OTA")) return
        if (!BuildConfig.DEBUG) {
            Log.w("PullOtaTest", "Pull-mode OTA testing is disabled outside debug builds")
            Toast.makeText(activity, "Pull-mode OTA testing is available only in debug builds.", Toast.LENGTH_LONG).show()
            return
        }
        if (host.isGlassesCommandBlocked("pull-mode OTA test")) return
        if (!BleOperateManager.getInstance().isConnected) {
            Log.e("PullOtaTest", "Bluetooth not connected. Please connect to glasses first.")
            Toast.makeText(
                activity,
                "Bluetooth not connected. Please connect to glasses first.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val url = TEST_PULL_OTA_URL
        if (url.isBlank()) {
            Log.e("PullOtaTest", "TEST_PULL_OTA_URL is blank; edit MainActivity to set it.")
            Toast.makeText(
                activity,
                "TEST_PULL_OTA_URL is blank. Edit MainActivity first.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val pullOtaLease = host.acquireExclusiveGlassesSession(GlassesSession.OTA) ?: return
        Log.i("PullOtaTest", "Calling writeIpToSoc with URL: $url")
        try {
            LargeDataHandler.getInstance().writeIpToSoc(url) { cmdType, response ->
                Log.i(
                    "PullOtaTest",
                    "writeIpToSoc callback: cmdType=$cmdType, response=$response"
                )
            }
            // Keep the exclusive BLE slot for the response window. The callback is diagnostic
            // only, so a late vendor response cannot release a later OTA session.
            host.glassesTeardownScope.launch {
                delay(PULL_OTA_TEST_LEASE_MS)
                host.releaseExclusiveGlassesSession(pullOtaLease)
            }
        } catch (e: Exception) {
            host.releaseExclusiveGlassesSession(pullOtaLease)
            Log.e("PullOtaTest", "writeIpToSoc failed", e)
        }
    }

    fun observeOtaState() {
        activity.lifecycleScope.launch {
            otaManager.uiState.collect { ota ->
                val preparationActive = otaPreparationJob?.isActive == true
                val sessionActive = otaManager.isActive || otaSessionLease != null
                dashboardState = dashboardState.copy(
                    ota = otaSectionUiState(ota, preparationActive, sessionActive),
                )
            }
        }
    }

    fun requestOtaFirmware(source: OtaFirmwareSource) {
        if (host.rejectHeyCyanOnlyFeature("HeyCyan OTA")) return
        if (!hasBluetooth(activity) || !hasWifiP2pPermission(activity)) {
            host.ensureGlassesTransportPermissions("Wi-Fi OTA") {
                requestOtaFirmware(source)
            }
            return
        }
        if (otaManager.isActive || otaPreparationJob?.isActive == true || otaSessionLease != null) {
            Log.w("Ota", "OTA is already preparing or running")
            return
        }
        if (AutoAudioCaptureService.isRunning()) {
            Toast.makeText(activity, "Stop auto audio capture before starting a firmware update.", Toast.LENGTH_LONG).show()
            return
        }

        when (source) {
            OtaFirmwareSource.PERSONAL_FILE -> {
                val otaLease = host.acquireExclusiveGlassesSession(GlassesSession.OTA) ?: return
                otaSessionLease = otaLease
                stagedPersonalWifiFirmware = null
                pendingPersonalFirmwareTarget = OtaTarget.V821_WIFI
                dashboardState = dashboardState.copy(
                    ota = dashboardState.ota.copy(
                        stateLabel = "Selecting firmware",
                        detail = "Select the Wi-Fi .swu file first; the BLE .bin picker follows.",
                        progress = null,
                        canStart = false,
                        canCancel = true,
                    ),
                )
                try {
                    host.launchPersonalFirmwarePicker()
                } catch (error: Exception) {
                    finishPersonalFirmwareSelection("Could not open the Wi-Fi firmware picker: ${error.message}")
                }
            }
            OtaFirmwareSource.STEALTH_CATALOG,
            OtaFirmwareSource.DEBUG_CATALOG,
            -> startCatalogOta(source)
        }
    }

    /** Result of the personal firmware document picker registered by the activity. */
    fun onPersonalFirmwareDocumentPicked(uri: Uri?) {
        val target = pendingPersonalFirmwareTarget
        pendingPersonalFirmwareTarget = null
        if (target == null) return
        if (uri == null) {
            abortPersonalFirmwareSelection("Both Wi-Fi and BLE firmware files are required.")
        } else {
            stagePersonalFirmware(uri, target)
        }
    }

    /** Dashboard "Cancel" for both the preparation phase and a running OTA session. */
    fun cancelFromDashboard() {
        val managerWasActive = otaManager.isActive
        otaPreparationJob?.cancel()
        otaPreparationJob = null
        pendingPersonalFirmwareTarget = null
        stagedPersonalWifiFirmware?.takeIf { it.exists() }?.delete()
        stagedPersonalWifiFirmware = null
        otaManager.cancel()
        if (!managerWasActive) {
            host.releaseExclusiveGlassesSession(otaSessionLease)
            resetOtaDashboardToIdle()
        }
    }

    fun onActivityDestroyed() {
        otaPreparationJob?.cancel()
        otaPreparationJob = null
        otaManager.cancel()
        pendingPersonalFirmwareTarget = null
        stagedPersonalWifiFirmware?.takeIf { it.exists() }?.delete()
        stagedPersonalWifiFirmware = null
        if (!otaManager.isActive) {
            host.releaseExclusiveGlassesSession(otaSessionLease)
        }
    }

    private fun stagePersonalFirmware(uri: Uri, target: OtaTarget) {
        if (otaManager.isActive || otaSessionLease == null) return
        runCatching {
            activity.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        otaPreparationJob = activity.lifecycleScope.launch(Dispatchers.IO) {
            try {
                withContext(Dispatchers.Main) {
                    dashboardState = dashboardState.copy(
                        ota = dashboardState.ota.copy(
                            stateLabel = "Staging firmware",
                            detail = "Copying the selected ${target.expectedFirmwareExtension()} into private app storage...",
                            canStart = false,
                            canCancel = true,
                        ),
                    )
                }
                val firmwareFile = copyPersonalFirmware(uri, target)
                withContext(Dispatchers.Main) {
                    if (target == OtaTarget.V821_WIFI) {
                        stagedPersonalWifiFirmware = firmwareFile
                        pendingPersonalFirmwareTarget = OtaTarget.JIELI_BLE
                        dashboardState = dashboardState.copy(
                            ota = dashboardState.ota.copy(
                                stateLabel = "Selecting firmware",
                                detail = "Wi-Fi SWU staged. Select the companion Bluetooth/JieLi .bin file.",
                                canStart = false,
                                canCancel = true,
                            ),
                        )
                        try {
                            host.launchPersonalFirmwarePicker()
                        } catch (error: Exception) {
                            finishPersonalFirmwareSelection("Could not open the BLE firmware picker: ${error.message}")
                        }
                    } else {
                        val wifiFile = stagedPersonalWifiFirmware
                            ?: throw IllegalStateException("The Wi-Fi firmware file was not staged")
                        stagedPersonalWifiFirmware = null
                        startCombinedOtaWithLease(
                            wifiFile = wifiFile,
                            bleFile = firmwareFile,
                            source = OtaFirmwareSource.PERSONAL_FILE,
                            otaLease = requireNotNull(otaSessionLease),
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("Ota", "Could not import personal firmware", e)
                withContext(Dispatchers.Main) {
                    finishPersonalFirmwareSelection(
                        "Could not stage the selected firmware: ${e.message ?: "unknown error"}",
                    )
                }
            }
        }
    }

    private fun finishPersonalFirmwareSelection(message: String? = null) {
        pendingPersonalFirmwareTarget = null
        stagedPersonalWifiFirmware?.takeIf { it.exists() }?.delete()
        stagedPersonalWifiFirmware = null
        if (!otaManager.isActive) {
            host.releaseExclusiveGlassesSession(otaSessionLease)
        }
        resetOtaDashboardToIdle()
        message?.let {
            Toast.makeText(activity, it, Toast.LENGTH_LONG).show()
        }
    }

    private fun abortPersonalFirmwareSelection(message: String) {
        finishPersonalFirmwareSelection(message)
    }

    private fun copyPersonalFirmware(uri: Uri, target: OtaTarget): File {
        val displayName = personalFirmwareDisplayName(uri)
            ?: throw IllegalArgumentException("The selected document has no filename")
        if (!target.isExpectedFirmwareFilename(displayName)) {
            throw IllegalArgumentException("Select a ${target.expectedFirmwareExtension()} file for this target")
        }

        val otaDir = File(activity.filesDir, "ota/personal")
        if (!otaDir.exists() && !otaDir.mkdirs()) {
            throw IllegalStateException("Could not create private OTA storage")
        }
        val targetName = target.name.lowercase()
        val outputFile = File(
            otaDir,
            "personal_${targetName}_${System.currentTimeMillis()}${target.expectedFirmwareExtension()}",
        )
        val stagingFile = File(otaDir, ".${outputFile.name}.partial")
        try {
            activity.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(stagingFile).use { output -> input.copyTo(output) }
            } ?: throw IllegalArgumentException("The selected document cannot be read")

            if (stagingFile.length() <= 0L) {
                throw IllegalArgumentException("The selected firmware file is empty")
            }
            if (!stagingFile.renameTo(outputFile)) {
                throw IllegalStateException("Could not finalize the selected firmware file")
            }
            return outputFile
        } finally {
            if (stagingFile.exists()) stagingFile.delete()
        }
    }

    private fun personalFirmwareDisplayName(uri: Uri): String? {
        val displayName = runCatching {
            activity.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
            }
        }.getOrNull()
        return displayName?.trim()?.takeIf { it.isNotEmpty() }
            ?: uri.lastPathSegment?.substringAfterLast('/')?.trim()?.takeIf { it.isNotEmpty() }
    }

    fun resetOtaDashboardToIdle() {
        dashboardState = dashboardState.copy(
            ota = dashboardState.ota.copy(
                stateLabel = "Idle",
                detail = "",
                progress = null,
                canStart = true,
                canCancel = false,
            ),
        )
    }

    private fun startCombinedOtaWithLease(
        wifiFile: File,
        bleFile: File,
        source: OtaFirmwareSource,
        otaLease: GlassesSessionLease,
    ) {
        if (AutoAudioCaptureService.isRunning()) {
            Toast.makeText(activity, "Stop auto audio capture before starting a firmware update.", Toast.LENGTH_LONG).show()
            host.releaseExclusiveGlassesSession(otaLease)
            resetOtaDashboardToIdle()
            return
        }
        Log.i(
            "Ota",
            "Starting combined ${source.name} OTA: wifi=${wifiFile.name} (${wifiFile.length()} bytes), " +
                "ble=${bleFile.name} (${bleFile.length()} bytes)",
        )
        otaExpectedDeviceAddress = runCatching { DeviceManager.getInstance().deviceAddress }
            .getOrNull()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        otaManager.startCombinedOta(
            wifiFirmwareFile = wifiFile,
            bleFirmwareFile = bleFile,
            awaitFreshBleReadiness = bleReadiness::awaitFreshBleReadiness,
        ) {
            host.releaseExclusiveGlassesSession(otaLease)
            otaExpectedDeviceAddress = null
            activity.runOnUiThread {
                dashboardState = dashboardState.copy(
                    ota = dashboardState.ota.copy(
                        canStart = true,
                        canCancel = false,
                    ),
                )
            }
        }
    }

    /** Downloads a catalog artifact only after the shared OTA session lease is held. */
    private fun startCatalogOta(source: OtaFirmwareSource) {
        val otaLease = host.acquireExclusiveGlassesSession(GlassesSession.OTA) ?: return
        otaSessionLease = otaLease
        otaPreparationJob = activity.lifecycleScope.launch(Dispatchers.IO) {
            try {
                withContext(Dispatchers.Main) {
                    dashboardState = dashboardState.copy(
                        ota = dashboardState.ota.copy(
                            stateLabel = "Checking firmware versions",
                            detail = "Reading Wi-Fi and Bluetooth identifiers once before resolving both artifacts...",
                            canStart = false,
                            canCancel = true,
                        ),
                    )
                }

                val deviceInfo = GlassesDeviceInfoReader.readGlassesDeviceInfo()
                if (deviceInfo == null) {
                    throw IllegalStateException("Could not read glasses info. Is Bluetooth connected?")
                }

                val wifiHardwareVersion = deviceInfo.wifiHardwareVersion.orEmpty().trim()
                val wifiFirmwareVersion = deviceInfo.wifiFirmwareVersion.orEmpty().trim()
                val bleHardwareVersion = deviceInfo.hardwareVersion.orEmpty().trim()
                val bleFirmwareVersion = deviceInfo.firmwareVersion.orEmpty().trim()
                val installedFirmwareVersions = InstalledFirmwareVersions(
                    wifiHardwareVersion = wifiHardwareVersion,
                    wifiFirmwareVersion = wifiFirmwareVersion,
                    bleHardwareVersion = bleHardwareVersion,
                    bleFirmwareVersion = bleFirmwareVersion,
                )
                if (!installedFirmwareVersions.isComplete()) {
                    throw IllegalStateException("Could not read all Wi-Fi and Bluetooth firmware identifiers")
                }

                val client = FirmwareClient(activity)
                withContext(Dispatchers.Main) {
                    dashboardState = dashboardState.copy(
                        ota = dashboardState.ota.copy(
                            stateLabel = "Resolving Wi-Fi artifact",
                            detail = "Requesting the exact-base Wi-Fi .swu artifact (1/2)...",
                        ),
                    )
                }
                val otaDir = File(activity.filesDir, "ota/catalog")
                val wifiResult = try {
                    client.fetchAndDownload(
                        deviceVersions = installedFirmwareVersions,
                        outputDir = otaDir,
                        target = OtaTarget.V821_WIFI,
                        source = source,
                    )
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    FirmwareResult.Error("Wi-Fi artifact request failed: ${error.message}")
                }

                if (wifiResult !is FirmwareResult.Ready) {
                    showCatalogResolutionFailure(
                        result = wifiResult,
                        source = source,
                        target = OtaTarget.V821_WIFI,
                        deviceInfo = deviceInfo,
                    )
                    return@launch
                }

                withContext(Dispatchers.Main) {
                    dashboardState = dashboardState.copy(
                        ota = dashboardState.ota.copy(
                            stateLabel = "Resolving BLE artifact",
                            detail = "Requesting the exact-base Bluetooth/JieLi .bin artifact (2/2)...",
                        ),
                    )
                }
                val bleResult = try {
                    client.fetchAndDownload(
                        deviceVersions = installedFirmwareVersions,
                        outputDir = otaDir,
                        target = OtaTarget.JIELI_BLE,
                        source = source,
                    )
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    FirmwareResult.Error("BLE artifact request failed: ${error.message}")
                }

                if (bleResult !is FirmwareResult.Ready) {
                    showCatalogResolutionFailure(
                        result = bleResult,
                        source = source,
                        target = OtaTarget.JIELI_BLE,
                        deviceInfo = deviceInfo,
                    )
                    return@launch
                }

                withContext(Dispatchers.Main) {
                    startCombinedOtaWithLease(
                        wifiFile = wifiResult.file,
                        bleFile = bleResult.file,
                        source = source,
                        otaLease = otaLease,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("Ota", "Catalog OTA preparation failed", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        activity,
                        "Firmware preparation failed: ${e.message}",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            } finally {
                if (!otaManager.isActive) {
                    withContext(Dispatchers.Main) {
                        host.releaseExclusiveGlassesSession(otaLease)
                        dashboardState = dashboardState.copy(
                            ota = dashboardState.ota.copy(
                                stateLabel = "Idle",
                                detail = "",
                                progress = null,
                                canStart = true,
                                canCancel = false,
                            ),
                        )
                    }
                }
            }
        }
    }

    private suspend fun showCatalogResolutionFailure(
        result: FirmwareResult,
        source: OtaFirmwareSource,
        target: OtaTarget,
        deviceInfo: com.oudmon.ble.base.communication.bigData.resp.DeviceInfoResponse,
    ) {
        withContext(Dispatchers.Main) {
            when (result) {
                is FirmwareResult.NotAvailable -> {
                    val targetHardwareVersion = when (target) {
                        OtaTarget.V821_WIFI -> deviceInfo.wifiHardwareVersion.orEmpty()
                        OtaTarget.JIELI_BLE -> deviceInfo.hardwareVersion.orEmpty()
                    }
                    val targetFirmwareVersion = when (target) {
                        OtaTarget.V821_WIFI -> deviceInfo.wifiFirmwareVersion.orEmpty()
                        OtaTarget.JIELI_BLE -> deviceInfo.firmwareVersion.orEmpty()
                    }
                    dashboardState = dashboardState.copy(
                        ota = dashboardState.ota.copy(
                            stateLabel = "Firmware set unavailable",
                            detail = "The ${target.expectedFirmwareExtension()} artifact was not resolved; no component will be flashed.",
                        ),
                        firmwarePatchRequest = FirmwarePatchRequestUiState(
                            source = source,
                            target = target.toOtaTargetSelection(),
                            targetHardwareVersion = targetHardwareVersion.ifBlank { "unknown" },
                            targetFirmwareVersion = targetFirmwareVersion.ifBlank { "unknown" },
                            wifiHardwareVersion = deviceInfo.wifiHardwareVersion.orEmpty().ifBlank { "unknown" },
                            wifiFirmwareVersion = deviceInfo.wifiFirmwareVersion.orEmpty().ifBlank { "unknown" },
                            bleHardwareVersion = deviceInfo.hardwareVersion.orEmpty().ifBlank { "unknown" },
                            bleFirmwareVersion = deviceInfo.firmwareVersion.orEmpty().ifBlank { "unknown" },
                            relayMessage = result.message,
                            suggestedContactEmail = ProSubscriptionServerPrefs.getAccountEmail(activity),
                        ),
                    )
                }

                is FirmwareResult.DebugAccessRequired -> {
                    AlertDialog.Builder(activity)
                        .setTitle("Debug Firmware Access Required")
                        .setMessage(result.message)
                        .setPositiveButton("OK", null)
                        .show()
                }

                is FirmwareResult.SubscriptionRequired -> {
                    val copy = firmwareSubscriptionGateCopy(result.currentPlan)
                    AlertDialog.Builder(activity)
                        .setTitle(copy.title)
                        .setMessage(copy.message)
                        .setNegativeButton("Not now", null)
                        .setPositiveButton(copy.actionLabel) { _, _ ->
                            activity.startActivity(
                                AppRoute.PRO_SUBSCRIPTION.intent(activity).apply {
                                    putExtra(ProSubscriptionDestination.EXTRA_INITIAL_PLAN, "standard")
                                    putExtra(ProSubscriptionDestination.EXTRA_CHANGE_PLAN, true)
                                },
                            )
                        }
                        .show()
                }

                is FirmwareResult.Error -> {
                    Toast.makeText(
                        activity,
                        "Firmware resolution failed: ${result.message}",
                        Toast.LENGTH_LONG,
                    ).show()
                }

                is FirmwareResult.Ready -> Unit
            }
        }
    }

    fun submitFirmwarePatchRequest(
        request: FirmwarePatchRequestUiState,
        contactEmail: String,
    ) {
        Toast.makeText(activity, "Collecting firmware diagnostics...", Toast.LENGTH_SHORT).show()
        activity.lifecycleScope.launch(Dispatchers.IO) {
            val result = DebugLogSupport.sendFirmwarePatchRequest(
                context = activity,
                contactEmail = contactEmail,
                request = DebugLogSupport.FirmwarePatchRequest(
                    source = request.source.label,
                    target = request.target.label,
                    targetHardwareVersion = request.targetHardwareVersion,
                    targetFirmwareVersion = request.targetFirmwareVersion,
                    wifiHardwareVersion = request.wifiHardwareVersion,
                    wifiFirmwareVersion = request.wifiFirmwareVersion,
                    bleHardwareVersion = request.bleHardwareVersion,
                    bleFirmwareVersion = request.bleFirmwareVersion,
                    relayMessage = request.relayMessage,
                ),
                relayBaseUrl = firmwareRelayBaseUrl(activity),
            )
            withContext(Dispatchers.Main) {
                result.onSuccess { logId ->
                    dashboardState = dashboardState.copy(firmwarePatchRequest = null)
                    Toast.makeText(
                        activity,
                        "Patch request sent. Reference: $logId",
                        Toast.LENGTH_LONG,
                    ).show()
                }.onFailure { error ->
                    Log.e("Ota", "Could not send firmware patch request", error)
                    dashboardState.firmwarePatchRequest?.let { activeRequest ->
                        dashboardState = dashboardState.copy(
                            firmwarePatchRequest = activeRequest.copy(
                                isSubmitting = false,
                                submissionError = "Could not send the request. Check your connection and try again.",
                            ),
                        )
                    }
                    Toast.makeText(
                        activity,
                        "Could not send patch request: ${error.message}",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }

    companion object {
        private const val PULL_OTA_TEST_LEASE_MS = 10_000L

        // Edit this URL before using the pull-mode OTA test button.
        // In the official app, the phone runs an HTTP server on its own
        // Wi‑Fi Direct address and the glasses fetch the file from there.
        // For experiments you can point this at a simple `python -m http.server`
        // instance on the phone or on a reachable host.
        private const val TEST_PULL_OTA_URL =
            "http://192.168.49.1:8080/dummy.swu"
    }
}

/** Maps the [OtaManager] state onto the dashboard OTA section. */
internal fun otaSectionUiState(
    ota: OtaUiState,
    preparationActive: Boolean,
    sessionActive: Boolean,
): OtaSectionUiState {
    val terminal = ota.state == OtaState.IDLE ||
        ota.state == OtaState.COMPLETE ||
        ota.state == OtaState.FAILED
    return OtaSectionUiState(
        stateLabel = ota.state.name.replace("_", " ").lowercase()
            .replaceFirstChar { it.uppercase() },
        detail = ota.detail.ifBlank { ota.error.orEmpty() },
        progress = ota.progress,
        canStart = terminal && !preparationActive && !sessionActive,
        canCancel = (preparationActive || sessionActive) &&
            ota.state !in setOf(OtaState.COMPLETE, OtaState.CANCELLING),
    )
}

internal fun OtaTarget.toOtaTargetSelection(): com.fersaiyan.cyanbridge.shared.glasses.OtaTargetSelection = when (this) {
    OtaTarget.V821_WIFI -> com.fersaiyan.cyanbridge.shared.glasses.OtaTargetSelection.V821_WIFI
    OtaTarget.JIELI_BLE -> com.fersaiyan.cyanbridge.shared.glasses.OtaTargetSelection.JIELI_BLE
}
