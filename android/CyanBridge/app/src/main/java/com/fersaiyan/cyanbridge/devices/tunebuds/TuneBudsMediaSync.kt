package com.fersaiyan.cyanbridge.devices.tunebuds

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.SoftApConfiguration
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.fersaiyan.cyanbridge.ui.hasWifiP2pPermission
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class TuneBudsHotspotCredentials(
    val ssid: String,
    val password: String,
    val channel: Int = 0,
)

/** Owns the phone-created local hotspot used by the safest AB Mate media path. */
class TuneBudsLocalHotspot(context: Context) {
    companion object {
        private const val START_TIMEOUT_MS = 30_000L
    }

    private val context = context.applicationContext
    private val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as WifiManager

    @Volatile
    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null
    @Volatile private var generation = 0L
    private val _isActive = MutableStateFlow(false)
    val isActive = _isActive.asStateFlow()

    @SuppressLint("MissingPermission")
    suspend fun start(): TuneBudsHotspotCredentials {
        if (!hasWifiPermission()) {
            throw SecurityException("Nearby Wi-Fi or location permission is required")
        }
        stop()
        val startedGeneration = generation
        return withTimeout(START_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                continuation.invokeOnCancellation { stop() }
                wifiManager.startLocalOnlyHotspot(
                    object : WifiManager.LocalOnlyHotspotCallback() {
                        override fun onStarted(startedReservation: WifiManager.LocalOnlyHotspotReservation) {
                            if (!continuation.isActive || startedGeneration != generation) {
                                startedReservation.close()
                                return
                            }
                            reservation = startedReservation
                            _isActive.value = true
                            val credentials = readCredentials(startedReservation)
                            if (credentials == null) {
                                stop()
                                continuation.resumeWithException(IOException("Android returned empty hotspot credentials"))
                            } else {
                                continuation.resume(credentials)
                            }
                        }

                        override fun onStopped() {
                            if (startedGeneration != generation) return
                            reservation = null
                            _isActive.value = false
                            if (continuation.isActive) {
                                continuation.resumeWithException(IOException("Local hotspot stopped before it was ready"))
                            }
                        }

                        override fun onFailed(reason: Int) {
                            if (startedGeneration != generation) return
                            reservation = null
                            _isActive.value = false
                            if (continuation.isActive) {
                                continuation.resumeWithException(IOException("Could not start local hotspot: $reason"))
                            }
                        }
                    },
                    Handler(Looper.getMainLooper()),
                )
            }
        }
    }

    fun stop() {
        generation++
        val current = reservation
        reservation = null
        _isActive.value = false
        runCatching { current?.close() }
    }

    @Suppress("DEPRECATION")
    private fun readCredentials(
        current: WifiManager.LocalOnlyHotspotReservation,
    ): TuneBudsHotspotCredentials? {
        val ssid: String?
        val password: String?
        val channel: Int
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val configuration: SoftApConfiguration = current.softApConfiguration
            ssid = configuration.ssid
            password = configuration.passphrase
            channel = 0
        } else {
            val configuration = current.wifiConfiguration
            ssid = configuration?.SSID
            password = configuration?.preSharedKey
            channel = 0
        }
        if (ssid.isNullOrBlank() || password.isNullOrBlank()) return null
        return TuneBudsHotspotCredentials(ssid, password, channel.coerceIn(0, 0xFF))
    }

    private fun hasWifiPermission(): Boolean {
        return hasWifiP2pPermission(context)
    }
}

enum class TuneBudsMediaType {
    PHOTO,
    VIDEO,
    AUDIO,
}

data class TuneBudsMediaItem(
    val remoteName: String,
    val fileName: String,
    val type: TuneBudsMediaType,
)

data class TuneBudsMediaSyncState(
    val detail: String = "Idle",
    val completed: Int = 0,
    val total: Int = 0,
    val lastError: String? = null,
    val totalsByType: Map<TuneBudsMediaType, Int> = emptyMap(),
    val completedByType: Map<TuneBudsMediaType, Int> = emptyMap(),
)

/** Non-destructive TuneBuds HTTP media importer. Device files are never deleted. */
class TuneBudsMediaSync(
    private val manager: TuneBudsManager,
    private val hotspot: TuneBudsLocalHotspot,
    private val temporaryDirectory: File,
    private val context: Context? = null,
) {
    companion object {
        private val CLIENT = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private var client = CLIENT
    private var p2p: TuneBudsP2pTransport? = null

    suspend fun sync(
        onState: suspend (TuneBudsMediaSyncState) -> Unit = {},
        onProgress: suspend (TuneBudsMediaItem, Long, Long) -> Unit = { _, _, _ -> },
        onFile: suspend (TuneBudsMediaItem, File) -> Boolean,
    ): Result<Int> {
        var state = TuneBudsMediaSyncState(detail = "Starting media sync")
        try {
            onState(state)
            temporaryDirectory.mkdirs()
            return coroutineScope {
                val endpoint = openTransport { detail ->
                    state = state.copy(detail = detail)
                    onState(state)
                }
                val monitor = launch(start = CoroutineStart.UNDISPATCHED) {
                    manager.state.first {
                        it.protocolState != TuneBudsSppState.CONNECTED.name || it.wifiState in setOf(0, 4, 5)
                    }
                    throw IOException("TuneBuds Bluetooth or Wi-Fi disconnected during media sync")
                }
                val linkMonitor = launch(start = CoroutineStart.UNDISPATCHED) {
                    (p2p?.isActive ?: hotspot.isActive).first { !it }
                    throw IOException("TuneBuds media Wi-Fi link stopped")
                }
                try {
                    val baseUrl = normalizeBaseUrl(endpoint)
                    android.util.Log.i("TuneBudsMediaSync", "Media endpoint: $baseUrl")
                    state = state.copy(detail = "Reading TuneBuds media list")
                    onState(state)
                    val items = fetchManifest(baseUrl)
                    state = state.copy(
                        total = items.size,
                        totalsByType = items.groupingBy { it.type }.eachCount(),
                        detail = if (items.isEmpty()) "No pending media" else "Downloading 0/${items.size}",
                    )
                    onState(state)

                    var completed = 0
                    for (item in items) {
                        coroutineContext.ensureActive()
                        val file = download(baseUrl, item, onProgress)
                        val imported = try { onFile(item, file) } finally { file.delete() }
                        if (!imported) throw IOException("Could not import ${item.fileName}")
                        completed++
                        state = state.copy(
                            completed = completed,
                            detail = "Downloaded $completed/${items.size}",
                            completedByType = state.completedByType +
                                (item.type to ((state.completedByType[item.type] ?: 0) + 1)),
                        )
                        onState(state)
                    }
                    onState(state.copy(detail = "Completed"))
                    Result.success(completed)
                } finally {
                    monitor.cancel()
                    linkMonitor.cancel()
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            onState(state.copy(detail = "Failed", lastError = error.message ?: error.javaClass.simpleName))
            return Result.failure(error)
        } finally {
            withContext(kotlinx.coroutines.NonCancellable) {
                try {
                    manager.finishTransferBlocking()
                } catch (error: Exception) {
                    android.util.Log.w("TuneBudsMediaSync", "Camera cleanup failed", error)
                } finally {
                    try { p2p?.close() } catch (error: Exception) {
                        android.util.Log.w("TuneBudsMediaSync", "P2P cleanup failed", error)
                    } finally {
                        p2p = null
                        hotspot.stop()
                        client.connectionPool.evictAll()
                        client = CLIENT
                        temporaryDirectory.deleteRecursively()
                    }
                }
            }
        }
    }

    private suspend fun openTransport(onStatus: suspend (String) -> Unit): String {
        // Unknown capabilities preserve the already validated local-hotspot path.
        if (context != null && manager.state.value.wifiSupportMask?.and(4) == 4) {
            val transport = TuneBudsP2pTransport(context)
            p2p = transport
            val ssid = "AiGlassDb_${Random.nextInt(10_000)}"
            try {
                onStatus("Starting TuneBuds Wi-Fi Direct")
                val endpoint = manager.startP2pFileManager(ssid) { address ->
                    onStatus("Connecting TuneBuds P2P peer $address")
                    val network = transport.connect(address, ssid)
                    client = CLIENT.newBuilder().connectionPool(ConnectionPool()).socketFactory(network.socketFactory)
                        .dns(object : okhttp3.Dns {
                            override fun lookup(hostname: String) = network.getAllByName(hostname).toList()
                        }).build()
                }
                return endpoint
            } catch (error: Exception) {
                if (error is CancellationException && error !is TimeoutCancellationException) throw error
                if (error is TuneBudsFileModeException) throw error
                coroutineContext.ensureActive()
                android.util.Log.w("TuneBudsMediaSync", "P2P startup failed; trying phone hotspot", error)
                // Close camera and await group teardown before switching E6 topology.
                manager.finishTransferBlocking()
                transport.close()
                p2p = null
                client.connectionPool.evictAll()
                client = CLIENT
            }
        }
        onStatus("Starting phone hotspot")
        val credentials = hotspot.start()
        onStatus("Connecting TuneBuds to phone hotspot")
        return manager.startFileManager(credentials.ssid, credentials.password, credentials.channel)
            ?: throw IOException("TuneBuds did not report its media server address")
    }

    suspend fun fetchManifest(baseUrl: String): List<TuneBudsMediaItem> = withContext(Dispatchers.IO) {
        parseManifest(getText(mediaUrl(baseUrl, "media.config")))
    }

    suspend fun downloadSingle(baseUrl: String, item: TuneBudsMediaItem): File = download(normalizeBaseUrl(baseUrl), item) { _, _, _ -> }

    fun parseManifest(body: String): List<TuneBudsMediaItem> {
        // Legacy firmware can include an HTTP header block inside the actual body.
        val trimmed = body.lineSequence().filterNot { line ->
            val text = line.trim()
            text.startsWith("HTTP/", ignoreCase = true) ||
                (text.contains(": ") &&
                    Regex("^(Content-|Date:|Server:|Transfer-Encoding:|Connection:|Keep-Alive:|Accept|Cache-|Vary:|Set-Cookie:|Allow:)",
                        RegexOption.IGNORE_CASE).containsMatchIn(text))
        }.joinToString("\n").trim()
        val names = runCatching {
            when {
                trimmed.startsWith("[") -> stringsFromArray(JSONArray(trimmed))
                trimmed.startsWith("{") -> stringsFromArray(JSONObject(trimmed).optJSONArray("files") ?: JSONArray())
                else -> emptyList()
            }
        }.getOrDefault(emptyList()).ifEmpty {
            trimmed.lineSequence()
                .map(String::trim)
                .filter(String::isNotBlank)
                .toList()
        }
        return names.mapNotNull(::mediaItem).distinctBy { it.remoteName }
    }

    private suspend fun download(
        baseUrl: String,
        item: TuneBudsMediaItem,
        onProgress: suspend (TuneBudsMediaItem, Long, Long) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val output = File(temporaryDirectory, item.fileName).apply {
            parentFile?.mkdirs()
            delete()
        }
        val request = mediaRequest(mediaUrl(baseUrl, item.remoteName))
        try {
            withResponse(request) { response ->
                if (!response.isSuccessful) throw IOException("TuneBuds HTTP ${response.code} for ${item.fileName}")
                val body = response.body ?: throw IOException("TuneBuds returned no data for ${item.fileName}")
                val total = body.contentLength()
                var copied = 0L
                body.byteStream().use { input ->
                    output.outputStream().buffered(128 * 1024).use { target ->
                        val buffer = ByteArray(128 * 1024)
                        while (true) {
                            coroutineContext.ensureActive()
                            val count = input.read(buffer)
                            if (count <= 0) break
                            target.write(buffer, 0, count)
                            copied += count
                            onProgress(item, copied, total)
                        }
                    }
                }
                if (copied == 0L || (total >= 0L && copied != total)) {
                    throw IOException("Incomplete TuneBuds download ${item.fileName}: $copied/$total bytes")
                }
            }
            output
        } catch (error: Throwable) {
            output.delete()
            coroutineContext.ensureActive()
            throw error
        }
    }

    private suspend fun getText(url: String): String = withResponse(mediaRequest(url)) { response ->
        if (!response.isSuccessful) throw IOException("TuneBuds HTTP ${response.code} for media.config")
        response.body?.string() ?: throw IOException("TuneBuds returned an empty media.config")
    }

    private fun mediaRequest(url: String): Request = Request.Builder().url(url)
        .header("Connection", "close").header("User-Agent", "TSClient/1.0").get().build()

    private suspend fun <T> withResponse(request: Request, block: suspend (Response) -> T): T = coroutineScope {
        val call = client.newCall(request)
        // Interrupt both a blocked execute() and a stalled response-body read on Stop.
        val cancellation = launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { call.cancel() }
        }
        try { call.execute().use { block(it) } } finally { cancellation.cancel() }
    }

    private fun stringsFromArray(array: JSONArray): List<String> = buildList {
        for (index in 0 until array.length()) {
            when (val value = array.opt(index)) {
                is String -> add(value)
                is JSONObject -> value.optString("name").takeIf(String::isNotBlank)?.let(::add)
            }
        }
    }

    private fun mediaItem(value: String): TuneBudsMediaItem? {
        val remote = value.trim().replace('\\', '/').trimStart('/')
        if (remote.isBlank() || remote.contains("../")) return null
        var fileName = File(remote).name
        val lower = fileName.lowercase()
        val type = when {
            lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> TuneBudsMediaType.PHOTO
            lower.endsWith(".mp4") || lower.startsWith("video-") -> TuneBudsMediaType.VIDEO
            lower.endsWith(".opus") || lower.endsWith(".ogg") || lower.endsWith(".wav") -> TuneBudsMediaType.AUDIO
            else -> return null
        }
        if (type == TuneBudsMediaType.VIDEO && '.' !in fileName) fileName += ".mp4"
        return TuneBudsMediaItem(remote, fileName, type)
    }

    internal fun normalizeBaseUrl(value: String): String {
        val trimmed = value.trim()
        val uri = java.net.URI(trimmed)
        require(uri.scheme == "http" || uri.scheme == "https") { "TuneBuds returned an invalid media server address" }
        trimmed.toHttpUrl() // Validate host, port and HTTP syntax before construction.
        require(uri.rawUserInfo == null && uri.rawFragment == null) { "Invalid TuneBuds media server address" }
        // Match MediaDownloadManager's URI.getPort() fallback, not OkHttp's port 80.
        // Preserve explicit :80 across repeated normalization (OkHttp removes it).
        val authority = uri.rawAuthority + if (uri.port == -1) ":8080" else ""
        val path = uri.rawPath.orEmpty().ifEmpty { "/" }
        return "${uri.scheme}://$authority" + (if (path.endsWith('/')) path else "$path/") +
            (uri.rawQuery?.let { "?$it" } ?: "")
    }

    private fun mediaUrl(base: String, remote: String): String = normalizeBaseUrl(base).toHttpUrl()
        .newBuilder().addEncodedPathSegments(encodePath(remote)).build().toString()

    private fun encodePath(path: String): String = path.split('/')
        .filter(String::isNotBlank)
        .joinToString("/") { segment ->
            URLEncoder.encode(segment, StandardCharsets.UTF_8.name()).replace("+", "%20")
        }
}
