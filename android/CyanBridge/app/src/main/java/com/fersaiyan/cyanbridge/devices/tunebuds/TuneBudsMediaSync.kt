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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
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

    @SuppressLint("MissingPermission")
    suspend fun start(): TuneBudsHotspotCredentials {
        if (!hasWifiPermission()) {
            throw SecurityException("Nearby Wi-Fi or location permission is required")
        }
        stop()
        return withTimeout(START_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                continuation.invokeOnCancellation { stop() }
                wifiManager.startLocalOnlyHotspot(
                    object : WifiManager.LocalOnlyHotspotCallback() {
                        override fun onStarted(startedReservation: WifiManager.LocalOnlyHotspotReservation) {
                            if (!continuation.isActive) {
                                startedReservation.close()
                                return
                            }
                            reservation = startedReservation
                            val credentials = readCredentials(startedReservation)
                            if (credentials == null) {
                                stop()
                                continuation.resumeWithException(IOException("Android returned empty hotspot credentials"))
                            } else {
                                continuation.resume(credentials)
                            }
                        }

                        override fun onStopped() {
                            reservation = null
                            if (continuation.isActive) {
                                continuation.resumeWithException(IOException("Local hotspot stopped before it was ready"))
                            }
                        }

                        override fun onFailed(reason: Int) {
                            reservation = null
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
        val current = reservation
        reservation = null
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
)

/** Non-destructive TuneBuds HTTP media importer. Device files are never deleted. */
class TuneBudsMediaSync(
    private val manager: TuneBudsManager,
    private val hotspot: TuneBudsLocalHotspot,
    private val temporaryDirectory: File,
) {
    companion object {
        private val CLIENT = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    suspend fun sync(
        onState: suspend (TuneBudsMediaSyncState) -> Unit = {},
        onProgress: suspend (TuneBudsMediaItem, Long, Long) -> Unit = { _, _, _ -> },
        onFile: suspend (TuneBudsMediaItem, File) -> Boolean,
    ): Result<Int> {
        var state = TuneBudsMediaSyncState(detail = "Starting phone hotspot")
        onState(state)
        temporaryDirectory.mkdirs()
        try {
            val credentials = hotspot.start()
            state = state.copy(detail = "Connecting TuneBuds to phone hotspot")
            onState(state)
            val endpoint = manager.startFileManager(
                hotspotSsid = credentials.ssid,
                hotspotPassword = credentials.password,
                channel = credentials.channel,
            ) ?: throw IOException("TuneBuds did not report its media server address")
            val baseUrl = normalizeBaseUrl(endpoint)
            state = state.copy(detail = "Reading TuneBuds media list")
            onState(state)
            val items = fetchManifest(baseUrl)
            state = state.copy(total = items.size, detail = if (items.isEmpty()) "No pending media" else "Downloading 0/${items.size}")
            onState(state)

            var completed = 0
            for (item in items) {
                coroutineContext.ensureActive()
                val file = download(baseUrl, item, onProgress)
                val imported = try {
                    onFile(item, file)
                } finally {
                    file.delete()
                }
                if (!imported) throw IOException("Could not import ${item.fileName}")
                completed++
                state = state.copy(completed = completed, detail = "Downloaded $completed/${items.size}")
                onState(state)
            }
            onState(state.copy(detail = "Completed"))
            return Result.success(completed)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            onState(state.copy(detail = "Failed", lastError = error.message ?: error.javaClass.simpleName))
            return Result.failure(error)
        } finally {
            manager.finishTransfer()
            hotspot.stop()
            temporaryDirectory.deleteRecursively()
        }
    }

    suspend fun fetchManifest(baseUrl: String): List<TuneBudsMediaItem> = withContext(Dispatchers.IO) {
        parseManifest(getText(normalizeBaseUrl(baseUrl) + "media.config"))
    }

    suspend fun downloadSingle(baseUrl: String, item: TuneBudsMediaItem): File = download(normalizeBaseUrl(baseUrl), item) { _, _, _ -> }

    fun parseManifest(body: String): List<TuneBudsMediaItem> {
        val trimmed = body.trim()
        val names = runCatching {
            when {
                trimmed.startsWith("[") -> stringsFromArray(JSONArray(trimmed))
                trimmed.startsWith("{") -> stringsFromArray(JSONObject(trimmed).optJSONArray("files") ?: JSONArray())
                else -> emptyList()
            }
        }.getOrDefault(emptyList()).ifEmpty {
            trimmed.lineSequence()
                .map(String::trim)
                .filter { it.isNotBlank() && !it.startsWith("HTTP/") && !it.contains(": ") }
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
        val request = Request.Builder().url(baseUrl + encodePath(item.remoteName)).get().build()
        CLIENT.newCall(request).execute().use { response ->
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
        }
        output
    }

    private fun getText(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("Connection", "close")
            .header("User-Agent", "TSClient/1.0")
            .get()
            .build()
        CLIENT.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("TuneBuds HTTP ${response.code} for media.config")
            return response.body?.string() ?: throw IOException("TuneBuds returned an empty media.config")
        }
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

    private fun normalizeBaseUrl(value: String): String {
        val trimmed = value.trim()
        require(trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            "TuneBuds returned an invalid media server address"
        }
        return if (trimmed.endsWith('/')) trimmed else "$trimmed/"
    }

    private fun encodePath(path: String): String = path.split('/')
        .filter(String::isNotBlank)
        .joinToString("/") { segment ->
            URLEncoder.encode(segment, StandardCharsets.UTF_8.name()).replace("+", "%20")
        }
}
