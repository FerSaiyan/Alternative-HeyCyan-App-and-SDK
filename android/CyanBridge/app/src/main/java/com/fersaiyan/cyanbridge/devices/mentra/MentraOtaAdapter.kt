package com.fersaiyan.cyanbridge.devices.mentra

import android.content.Context
import android.util.Log
import com.fersaiyan.cyanbridge.shared.devices.mentra.MentraFirmwareUpdateSession
import com.fersaiyan.cyanbridge.shared.devices.mentra.MentraGlassesVersions
import com.fersaiyan.cyanbridge.shared.devices.mentra.MentraOtaCheckException
import com.fersaiyan.cyanbridge.shared.devices.mentra.MentraOtaCheckResult
import com.fersaiyan.cyanbridge.shared.devices.mentra.MentraOtaGlasses
import com.fersaiyan.cyanbridge.shared.devices.mentra.MentraOtaManifest
import com.fersaiyan.cyanbridge.shared.devices.mentra.MentraOtaPort
import com.fersaiyan.cyanbridge.shared.devices.mentra.MentraOtaRules
import com.fersaiyan.cyanbridge.shared.devices.mentra.MentraOtaStartException
import com.fersaiyan.cyanbridge.shared.devices.mentra.MentraOtaStatusEvent
import com.fersaiyan.cyanbridge.shared.devices.mentra.MentraWifiError
import com.fersaiyan.cyanbridge.shared.devices.mentra.MentraWifiNetwork
import com.mentra.bluetoothsdk.BluetoothSdkException
import com.mentra.bluetoothsdk.GlassesRuntimeState
import com.mentra.bluetoothsdk.MentraBluetoothSdkCallback
import com.mentra.bluetoothsdk.OtaStartAckEvent
import com.mentra.bluetoothsdk.OtaStatusEvent
import com.mentra.bluetoothsdk.VersionInfoResult
import com.mentra.bluetoothsdk.WifiStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * The Mentra SDK side of the firmware update ([MentraFirmwareUpdateSession]). The check and the
 * start are the SDK's own `checkForOtaUpdate` / `startOtaUpdate`, which use the update manifest
 * pinned to the SDK release in the Gradle dependency, so the glasses always end on the software
 * that matches this app's SDK. The glasses download the files over their own Wi-Fi.
 */
class MentraOtaAdapter(private val manager: MentraLiveManager, scope: CoroutineScope) : MentraOtaPort {
    private val versionInfo = MutableStateFlow<VersionInfoResult?>(null)
    private val _statusEvents = MutableSharedFlow<MentraOtaStatusEvent>(extraBufferCapacity = 64)
    override val statusEvents: Flow<MentraOtaStatusEvent> = _statusEvents

    override val glasses: StateFlow<MentraOtaGlasses> =
        combine(manager.glasses, versionInfo, ::toOtaGlasses)
            .stateIn(scope, SharingStarted.Eagerly, toOtaGlasses(manager.glasses.value, null))

    override val microphoneInUse: Boolean get() = manager.microphoneInUse

    private val listener = object : MentraBluetoothSdkCallback() {
        override fun onOtaStatus(event: OtaStatusEvent) {
            val error = event.errorMessage ?: (event.values["error"] as? String)
            _statusEvents.tryEmit(
                MentraOtaStatusEvent(
                    sessionId = event.sessionId,
                    status = event.status,
                    phase = event.phase,
                    stepType = event.stepType,
                    currentStep = event.currentStep,
                    totalSteps = event.totalSteps,
                    stepPercent = event.stepPercent,
                    overallPercent = event.overallPercent,
                    error = error,
                ),
            )
        }

        override fun onOtaStartAck(event: OtaStartAckEvent) {
            Log.i(TAG, "ota_start_ack ${event.values}")
        }

        override fun onVersionInfo(event: VersionInfoResult) {
            versionInfo.value = event
        }
    }

    init {
        manager.addSdkListener(listener)
    }

    override suspend fun refreshVersions() {
        if (!manager.ready) return
        versionInfo.value = withContext(Dispatchers.IO) { manager.requestVersionInfo() }
    }

    override suspend fun checkForUpdate(): MentraOtaCheckResult = withContext(Dispatchers.IO) {
        val available = try {
            manager.checkForOtaUpdate()
        } catch (error: BluetoothSdkException) {
            throw MentraOtaCheckException(
                "${error.code}: ${error.message}",
                unofficialClient = error.code == "unofficial_client",
                cause = error,
            )
        }
        val manifest = try {
            readManifest()
        } catch (error: Exception) {
            // Fail closed: without the manifest the downgrade guard cannot run.
            throw MentraOtaCheckException("Could not read the update manifest: ${error.message}", cause = error)
        }
        MentraOtaCheckResult(available, manifest)
    }

    /**
     * Reads the manifest the SDK checks and installs, for the screen and the downgrade guard: the
     * one pinned to the SDK release, or the legacy one the SDK uses for old glasses apps. The
     * SDK's own manifest reader is internal, so this mirrors its rules (OtaManifest.kt).
     */
    private fun readManifest(): MentraOtaManifest {
        val build = buildNumber(manager.glasses.value)?.toIntOrNull()
        val legacy = build != null && build < MentraOtaRules.LEGACY_PROTOCOL_BUILD
        val json = fetchJson(if (legacy) LEGACY_MANIFEST_URL else manager.otaVersionUrl)
        val app = json.optJSONObject("apps")?.optJSONObject(ASG_CLIENT_PACKAGE)
            ?.takeIf { it.has("versionCode") } ?: json
        val versionName = app.optString("versionName").trim()
        val mtkPatches = json.optJSONArray("mtk_patches")?.length() ?: 0
        return MentraOtaManifest(
            releaseVersion = if (legacy) versionName.ifEmpty { "latest" } else com.mentra.bluetoothsdk.BuildConfig.SDK_VERSION,
            appVersionCode = app.optLong("versionCode", 0L).takeIf { it > 0 },
            besVersion = json.optJSONObject("bes_firmware")?.optString("version")?.trim()?.takeIf { it.isNotEmpty() },
            includesSystemPatch = mtkPatches > 0 || json.optJSONObject("mtk_full_ota") != null,
        ).also { Log.i(TAG, "Manifest ${if (legacy) "legacy" else "pinned"}: $it") }
    }

    private fun fetchJson(url: String): JSONObject {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 15_000
        try {
            val code = connection.responseCode
            check(code in 200..299) { "HTTP $code" }
            return JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }

    override suspend fun startUpdate() {
        withContext(Dispatchers.IO) {
            try {
                manager.startOtaUpdate()
            } catch (error: BluetoothSdkException) {
                throw MentraOtaStartException("${error.code}: ${error.message}", error.code, error)
            }
        }
    }

    override suspend fun scanWifi(): List<MentraWifiNetwork> = withContext(Dispatchers.IO) {
        manager.requestWifiScan().map { MentraWifiNetwork(it.ssid, it.requiresPassword, it.signalStrength) }
    }

    override suspend fun connectWifi(ssid: String, password: String): MentraWifiError? = withContext(Dispatchers.IO) {
        try {
            val result = manager.sendWifiCredentials(ssid, password)
            val status = result.status
            when {
                result.error.equals("wrong_password", ignoreCase = true) -> MentraWifiError.WRONG_PASSWORD
                status is WifiStatus.Connected && status.ssid == ssid -> null
                else -> MentraWifiError.CONNECT_FAILED
            }
        } catch (error: BluetoothSdkException) {
            Log.w(TAG, "Wi-Fi join failed: ${error.code}")
            if (error.code.equals("wrong_password", ignoreCase = true)) MentraWifiError.WRONG_PASSWORD else MentraWifiError.CONNECT_FAILED
        }
    }

    override fun setUpdateActive(active: Boolean) {
        manager.firmwareUpdateActive = active
        Log.i(TAG, "Firmware update active=$active")
    }

    private fun toOtaGlasses(state: GlassesRuntimeState, info: VersionInfoResult?): MentraOtaGlasses {
        val connected = state as? GlassesRuntimeState.Connected
        return MentraOtaGlasses(
            ready = state.ready,
            batteryPercent = connected?.battery?.level,
            charging = connected?.battery?.charging == true,
            wifiSsid = (connected?.wifi as? WifiStatus.Connected)?.ssid?.takeIf { it.isNotBlank() },
            versions = MentraGlassesVersions(
                // The connection state follows the glasses after each restart; the version info
                // adds the system and BES versions it does not carry.
                appVersion = connected?.device?.appVersion.nonBlank() ?: info?.appVersion.nonBlank(),
                buildNumber = buildNumber(state) ?: info?.buildNumber.nonBlank(),
                systemVersion = info?.mtkFirmwareVersion.nonBlank(),
                besVersion = info?.besFirmwareVersion.nonBlank(),
            ),
        )
    }

    private fun buildNumber(state: GlassesRuntimeState): String? {
        val connected = state as? GlassesRuntimeState.Connected ?: return null
        return connected.device.buildNumber.nonBlank() ?: connected.firmware.buildNumber.nonBlank()
    }

    private fun String?.nonBlank(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

    companion object {
        private const val TAG = "MentraOta"
        private const val ASG_CLIENT_PACKAGE = "com.mentra.asg_client"

        /** The SDK's OtaManifestDefaults.LEGACY_PROD_OTA_VERSION_URL (internal there). */
        private const val LEGACY_MANIFEST_URL = "https://ota.mentraglass.com/prod_live_version.json"

        @Volatile private var session: MentraFirmwareUpdateSession? = null

        /**
         * The process-wide update session. It outlives the screen so an update keeps its state
         * when the screen is recreated; the Mentra SDK's foreground service keeps the process.
         */
        fun session(context: Context): MentraFirmwareUpdateSession =
            session ?: synchronized(this) {
                session ?: run {
                    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
                    val adapter = MentraOtaAdapter(MentraLiveManager.getInstance(context.applicationContext), scope)
                    MentraFirmwareUpdateSession(adapter, scope)
                }.also { session = it }
            }
    }
}
