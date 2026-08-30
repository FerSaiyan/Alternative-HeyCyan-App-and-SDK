package com.fersaiyan.cyanbridge.devices.moyoung

import android.content.Context
import android.util.Log
import com.moyoung.glasses.CRPBleClient
import com.moyoung.glasses.conn.CRPBleConnection
import com.moyoung.glasses.conn.CRPBleDevice
import com.moyoung.glasses.conn.callback.CRPFileDownloadCallback
import com.moyoung.glasses.conn.listener.CRPBleConnectionStateListener
import com.moyoung.glasses.conn.listener.CRPWifiChangeListener
import com.moyoung.glasses.conn.type.CRPWifiType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException

data class MoyoungW620State(
    val connectionLabel: String = "MoYoung / W620 disconnected",
    val protocolState: String = "DISCONNECTED",
    val deviceAddress: String? = null,
    val deviceName: String? = null,
    val batteryPercent: Int? = null,
    val isCharging: Boolean = false,
    val photoCount: Int? = null,
    val videoCount: Int? = null,
    val audioCount: Int? = null,
    val lastError: String? = null,
)

data class MoyoungWifiCredentials(
    val ssid: String,
    val password: String,
)

/** Thin adapter around the published MoYoung Android SDK. */
class MoyoungW620Manager private constructor(context: Context) {
    companion object {
        private const val TAG = "MoyoungW620"
        private const val WIFI_READY_TIMEOUT_MS = 30_000L
        private const val WIFI_CONNECTION_TIMEOUT_MS = 45_000L
        private const val DOWNLOAD_TIMEOUT_MS = 180_000L
        private const val PROBE_TIMEOUT_MS = 15_000L

        @Volatile
        private var instance: MoyoungW620Manager? = null

        fun getInstance(context: Context): MoyoungW620Manager =
            instance ?: synchronized(this) {
                instance ?: MoyoungW620Manager(context.applicationContext).also { instance = it }
            }
    }

    private data class WifiStateEvent(val type: CRPWifiType, val state: Int)
    private data class DownloadFiles(val sourceDirectory: String, val names: List<String>)

    private val appContext = context.applicationContext
    private val client = CRPBleClient.create(appContext)
    private val mediaMutex = Mutex()
    private val wifiStateEvents = MutableSharedFlow<WifiStateEvent>(extraBufferCapacity = 8)
    private val wifiConnectionEvents = MutableSharedFlow<Boolean>(extraBufferCapacity = 8)
    private val _state = MutableStateFlow(MoyoungW620State())

    private var device: CRPBleDevice? = null
    private var connection: CRPBleConnection? = null

    val state: StateFlow<MoyoungW620State> = _state.asStateFlow()

    @Synchronized
    fun connect(address: String, deviceName: String? = null) {
        val normalizedAddress = address.trim()
        if (normalizedAddress.isBlank()) {
            updateError("No MoYoung Bluetooth address was selected")
            return
        }
        if (isConnected() && _state.value.deviceAddress.equals(normalizedAddress, ignoreCase = true)) return

        runCatching { device?.disconnect() }
        _state.value = MoyoungW620State(
            connectionLabel = "Connecting to MoYoung / W620",
            protocolState = "CONNECTING",
            deviceAddress = normalizedAddress,
            deviceName = deviceName,
        )
        try {
            val nextDevice = client.getBleDevice(normalizedAddress)
                ?: throw IOException("MoYoung SDK could not open the selected BLE device")
            val nextConnection = nextDevice.connect()
                ?: throw IOException("MoYoung SDK did not create a BLE connection")
            device = nextDevice
            connection = nextConnection
            installListeners(nextConnection)
            if (nextDevice.isConnected) onConnected(nextConnection)
        } catch (error: Throwable) {
            updateError("MoYoung connection failed: ${error.message}")
        }
    }

    fun disconnect() {
        runCatching { connection?.disableWifi() }
        runCatching { device?.disconnect() }
        device = null
        connection = null
        _state.value = MoyoungW620State()
    }

    fun isConnected(): Boolean = device?.isConnected == true && _state.value.protocolState == "CONNECTED"

    suspend fun probe(address: String, deviceName: String?): Boolean {
        connect(address, deviceName)
        val identified = withTimeoutOrNull(PROBE_TIMEOUT_MS) {
            state.filter { it.protocolState == "CONNECTED" }.first()
            true
        } == true
        if (!identified) disconnect()
        return identified
    }

    fun requestBattery() {
        connectedOrNull()?.queryBattery()
    }

    fun requestMediaCount() {
        connectedOrNull()?.queryNewMediaFile()
    }

    fun stopMediaSync() {
        runCatching { connection?.disableWifi() }
    }

    suspend fun downloadMedia(
        targetDirectory: File,
        wifiCredentials: MoyoungWifiCredentials,
        onProgress: (current: Int, total: Int) -> Unit = { _, _ -> },
    ): List<File> = mediaMutex.withLock {
        val activeConnection = connectedOrNull()
            ?: throw IOException("Connect MoYoung / W620 glasses before syncing media")
        targetDirectory.mkdirs()
        val sdkCacheDirectory = File(appContext.filesDir, "moyoung/wifi/media_res")
        val filesBeforeDownload = sdkCacheDirectory.listFiles()
            ?.filter(File::isFile)
            ?.map(File::getName)
            ?.toSet()
            .orEmpty()
        val downloadStartedAt = System.currentTimeMillis()

        try {
            coroutineScope {
                val wifiReady = async(start = CoroutineStart.UNDISPATCHED) {
                    withTimeout(WIFI_READY_TIMEOUT_MS) {
                        wifiStateEvents
                            .filter { it.type == CRPWifiType.FILE && it.state == CRPWifiChangeListener.STATE_SUCCESS }
                            .first()
                    }
                }
                activeConnection.enableWifi(
                    CRPWifiType.FILE,
                    wifiCredentials.ssid,
                    wifiCredentials.password,
                )
                wifiReady.await()

                // The SDK example waits for file-sync mode to settle before requesting Android's join prompt.
                delay(5_000L)
                val wifiConnected = async(start = CoroutineStart.UNDISPATCHED) {
                    withTimeout(WIFI_CONNECTION_TIMEOUT_MS) {
                        wifiConnectionEvents.filter { it }.first()
                    }
                }
                activeConnection.connectWifi()
                wifiConnected.await()
            }

            val downloaded = awaitSdkDownload(activeConnection, onProgress)
            copyDownloadedFiles(
                downloaded = downloaded,
                targetDirectory = targetDirectory,
                sdkCacheDirectory = sdkCacheDirectory,
                filesBeforeDownload = filesBeforeDownload,
                downloadStartedAt = downloadStartedAt,
            )
        } finally {
            runCatching { activeConnection.disableWifi() }
        }
    }

    private fun installListeners(activeConnection: CRPBleConnection) {
        activeConnection.setConnectionStateListener { newState ->
            when (newState) {
                CRPBleConnectionStateListener.STATE_CONNECTED -> onConnected(activeConnection)
                CRPBleConnectionStateListener.STATE_CONNECTING -> {
                    _state.value = _state.value.copy(
                        connectionLabel = "Connecting to MoYoung / W620",
                        protocolState = "CONNECTING",
                    )
                }
                else -> {
                    _state.value = _state.value.copy(
                        connectionLabel = "MoYoung / W620 disconnected",
                        protocolState = "DISCONNECTED",
                    )
                }
            }
        }
        activeConnection.setBatteryListener { battery ->
            _state.value = _state.value.copy(
                batteryPercent = battery.lvl.coerceIn(0, 100),
                isCharging = battery.charging,
                lastError = null,
            )
        }
        activeConnection.setMediaFileChangeListener { files ->
            if (files != null) {
                _state.value = _state.value.copy(
                    photoCount = files.photoCount,
                    videoCount = files.videoCount,
                    audioCount = files.audioCount,
                    lastError = null,
                )
            }
        }
        activeConnection.setWifiListener(object : CRPWifiChangeListener {
            override fun onWifiStateChange(type: CRPWifiType, state: Int) {
                Log.i(TAG, "Wi-Fi state type=$type state=$state")
                wifiStateEvents.tryEmit(WifiStateEvent(type, state))
            }

            override fun onWifiConnectionStateChanged(connected: Boolean) {
                Log.i(TAG, "Wi-Fi connected=$connected")
                wifiConnectionEvents.tryEmit(connected)
            }

            override fun onLiveUrlChanged(url: String?) {
                Log.d(TAG, "SDK media/live base URL=${url.orEmpty()}")
            }
        })
    }

    private fun onConnected(activeConnection: CRPBleConnection) {
        _state.value = _state.value.copy(
            connectionLabel = "MoYoung / W620 connected",
            protocolState = "CONNECTED",
            lastError = null,
        )
        activeConnection.syncTime()
        activeConnection.queryBattery()
        activeConnection.queryNewMediaFile()
    }

    private suspend fun awaitSdkDownload(
        activeConnection: CRPBleConnection,
        onProgress: (Int, Int) -> Unit,
    ): DownloadFiles {
        val success = CompletableDeferred<Unit>()
        val files = CompletableDeferred<DownloadFiles>()
        activeConnection.downloadMediaFile(object : CRPFileDownloadCallback {
            override fun onStart() = onProgress(0, 100)

            override fun onProgress(current: Int) = onProgress(current, 100)

            override fun onProgress(current: Int, total: Int) = onProgress(current, total)

            override fun onDownloadFile(sourceDirectory: String?, names: MutableList<String>?) {
                if (!files.isCompleted) {
                    files.complete(DownloadFiles(sourceDirectory.orEmpty(), names.orEmpty().toList()))
                }
            }

            override fun onSuccess() {
                if (!success.isCompleted) success.complete(Unit)
            }

            override fun onFail(code: Int) {
                val error = IOException("MoYoung SDK media download failed with code $code")
                if (!success.isCompleted) success.completeExceptionally(error)
                if (!files.isCompleted) files.completeExceptionally(error)
            }
        })
        return withTimeout(DOWNLOAD_TIMEOUT_MS) {
            success.await()
            files.await()
        }
    }

    private fun copyDownloadedFiles(
        downloaded: DownloadFiles,
        targetDirectory: File,
        sdkCacheDirectory: File,
        filesBeforeDownload: Set<String>,
        downloadStartedAt: Long,
    ): List<File> {
        val reportedDirectory = downloaded.sourceDirectory.takeIf(String::isNotBlank)?.let(::File)
        val names = downloaded.names.ifEmpty {
            val scanDirectory = reportedDirectory ?: sdkCacheDirectory
            scanDirectory.listFiles()
                ?.filter { file ->
                    file.isFile && if (reportedDirectory != null) {
                        file.lastModified() >= downloadStartedAt
                    } else {
                        file.name !in filesBeforeDownload || file.lastModified() >= downloadStartedAt
                    }
                }
                ?.map(File::getName)
                .orEmpty()
        }
        return names.mapNotNull { value ->
            val listed = File(value)
            val source = when {
                listed.isAbsolute -> listed
                reportedDirectory != null -> File(reportedDirectory, value)
                else -> File(sdkCacheDirectory, value)
            }
            if (!source.isFile) {
                Log.w(TAG, "SDK-reported media file is missing: ${source.absolutePath}")
                return@mapNotNull null
            }
            val safeName = source.name.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val target = File(targetDirectory, safeName)
            source.copyTo(target, overwrite = true)
            target
        }
    }

    private fun connectedOrNull(): CRPBleConnection? = connection?.takeIf { isConnected() }

    private fun updateError(message: String) {
        Log.w(TAG, message)
        _state.value = _state.value.copy(
            connectionLabel = message,
            protocolState = "ERROR",
            lastError = message,
        )
    }
}
