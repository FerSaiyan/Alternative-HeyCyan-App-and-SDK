package com.fersaiyan.cyanbridge.devices.eyevue

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

enum class EyevueMediaType(val progressLabel: String) {
    PHOTO("photo"),
    VIDEO("video"),
    AUDIO("audio"),
}

data class EyevueMediaProfile(
    val mode: EyevueWifiMode,
    val baseIp: String,
    val project: String,
) {
    companion object {
        fun fromProject(project: String?): EyevueMediaProfile {
            val normalized = project.orEmpty().uppercase()
            return when {
                "SK" in normalized -> EyevueMediaProfile(
                    mode = EyevueWifiMode.AP,
                    baseIp = "192.168.1.254",
                    project = project.orEmpty(),
                )
                "T" in normalized -> EyevueMediaProfile(
                    mode = EyevueWifiMode.AP,
                    baseIp = "192.168.169.1",
                    project = project.orEmpty(),
                )
                else -> EyevueMediaProfile(
                    mode = EyevueWifiMode.P2P,
                    baseIp = "192.168.49.207",
                    project = project.orEmpty(),
                )
            }
        }
    }

    val isTSeries: Boolean
        get() = mode == EyevueWifiMode.AP && baseIp == "192.168.169.1"

    val liveControlUrl: String?
        get() = if (isTSeries) null else "http://$baseIp/?custom=1&cmd=3001&par=1"

    val liveStreamUrl: String
        get() = if (isTSeries) "rtsp://$baseIp/h264" else "rtsp://$baseIp/xxx.mov"
}

object EyevueLivePreviewPolicy {
    const val MIN_ANDROID_SDK = 29

    fun isSupported(androidSdk: Int): Boolean = androidSdk >= MIN_ANDROID_SDK
}

data class EyevueMediaItem(
    val remoteName: String,
    val fileName: String,
    val type: EyevueMediaType,
    val sizeBytes: Long = -1L,
    val timecode: Long = 0L,
)

data class EyevueMediaSyncState(
    val isActive: Boolean = false,
    val detail: String = "Idle",
    val completed: Int = 0,
    val total: Int = 0,
    val lastError: String? = null,
)

/** Eyevue media manifest and HTTP downloader. BLE/P2P ownership stays outside this class. */
class EyevueMediaSync(
    private val manager: EyevueManager,
    private val transport: EyevueWifiTransport,
    private val temporaryDirectory: File,
) {
    companion object {
        private const val TAG = "EyevueMedia"
        private val CLIENT = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    suspend fun sync(
        profile: EyevueMediaProfile,
        onState: suspend (EyevueMediaSyncState) -> Unit = {},
        onProgress: suspend (EyevueMediaItem, Long, Long) -> Unit = { _, _, _ -> },
        onFile: suspend (EyevueMediaItem, File) -> Boolean,
    ): Result<Int> {
        var state = EyevueMediaSyncState(isActive = true, detail = "Requesting Eyevue Wi-Fi information")
        onState(state)
        temporaryDirectory.mkdirs()
        try {
            // The reverse-engineered vendor flow activates the glasses Wi-Fi with CMD_APP_LIVE
            // (0x67, AP=0x30 / P2P=0x31) and then receives the SSID in command 0x25.
            // CMD_GET_WIFI_INFO (0x39) only queries information and did not reliably bring the
            // transfer network up on hardware.
            val ssid = manager.startLiveAndAwaitSsid(ap = profile.mode == EyevueWifiMode.AP)
                ?: throw IOException("Eyevue did not report a Wi-Fi SSID after starting Wi-Fi mode")
            state = state.copy(detail = "Connecting to ${profile.mode.name} Wi-Fi")
            onState(state)
            transport.connect(
                mode = profile.mode,
                ssid = ssid,
                password = "12345678",
                baseIp = profile.baseIp,
            ).getOrElse { throw it }

            val items = fetchManifest(profile)
            if (items.isEmpty()) throw IOException("Eyevue returned an empty media list")
            state = state.copy(total = items.size, detail = "Downloading 0/${items.size}")
            onState(state)

            var completed = 0
            for (item in items) {
                coroutineContext.ensureActive()
                val file = download(profile, item, onProgress)
                val imported = try {
                    onFile(item, file)
                } finally {
                    file.delete()
                }
                if (!imported) throw IOException("Could not import ${item.fileName}")
                completed++
                state = state.copy(
                    completed = completed,
                    detail = "Downloaded $completed/${items.size}",
                )
                onState(state)
            }
            state = state.copy(isActive = false, detail = "Completed")
            onState(state)
            return Result.success(completed)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            state = state.copy(
                isActive = false,
                detail = "Failed",
                lastError = error.message ?: error.javaClass.simpleName,
            )
            onState(state)
            Log.e(TAG, "Eyevue media sync failed", error)
            return Result.failure(error)
        } finally {
            // Match the vendor cleanup order: tell the glasses to leave transfer/live mode
            // before tearing down the Android Wi-Fi route.
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                manager.stopLiveBlocking()
                transport.disconnect()
                temporaryDirectory.deleteRecursively()
            }
        }
    }

    suspend fun fetchManifest(profile: EyevueMediaProfile): List<EyevueMediaItem> =
        withContext(Dispatchers.IO) {
            val url = if (profile.isTSeries) {
                "http://${profile.baseIp}/app/getfilelist"
            } else {
                "http://${profile.baseIp}/?custom=1&cmd=3015"
            }
            val body = getText(url)
            Log.d(TAG, "Eyevue manifest response from $url: ${body.take(300)}")
            if (profile.isTSeries) parseTManifest(body) else parseXmlManifest(body)
        }

    private suspend fun download(
        profile: EyevueMediaProfile,
        item: EyevueMediaItem,
        onProgress: suspend (EyevueMediaItem, Long, Long) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val url = mediaUrl(profile, item)
        val output = File(temporaryDirectory, item.fileName).apply {
            parentFile?.mkdirs()
            delete()
        }
        val request = Request.Builder().url(url).get().build()
        CLIENT.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Eyevue HTTP ${response.code} for $url")
            val body = response.body ?: throw IOException("Eyevue returned an empty body for ${item.fileName}")
            val total = body.contentLength()
            var copied = 0L
            body.byteStream().use { input ->
                output.outputStream().buffered(128 * 1024).use { outputStream ->
                    val buffer = ByteArray(128 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = input.read(buffer)
                        if (count <= 0) break
                        outputStream.write(buffer, 0, count)
                        copied += count
                        onProgress(item, copied, total)
                    }
                }
            }
        }
        output
    }

    private fun getText(url: String): String {
        val request = Request.Builder().url(url).get().build()
        CLIENT.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Eyevue HTTP ${response.code} for $url")
            return response.body?.string() ?: throw IOException("Eyevue returned an empty response for $url")
        }
    }

    private fun parseTManifest(body: String): List<EyevueMediaItem> {
        val root = JSONObject(body)
        val groups = root.optJSONArray("info") ?: return emptyList()
        val items = mutableListOf<EyevueMediaItem>()
        for (groupIndex in 0 until groups.length()) {
            val files = groups.optJSONObject(groupIndex)?.optJSONArray("files") ?: continue
            for (fileIndex in 0 until files.length()) {
                val file = files.optJSONObject(fileIndex) ?: continue
                val remote = normalizeRemoteName(file.optString("name"))
                mediaItem(
                    remoteName = remote,
                    sizeBytes = file.optLong("size", -1L),
                    timecode = file.optString("createtimestr").toLongOrNull() ?: 0L,
                )?.let(items::add)
            }
        }
        return items.distinctBy { it.remoteName }
    }

    private fun parseXmlManifest(body: String): List<EyevueMediaItem> {
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(body.reader())
        val items = mutableListOf<EyevueMediaItem>()
        var current: MutableMap<String, String>? = null
        var event = parser.eventType
        while (event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            when (event) {
                org.xmlpull.v1.XmlPullParser.START_TAG -> {
                    if (parser.name.equals("File", ignoreCase = true)) {
                        current = linkedMapOf()
                    } else if (current != null) {
                        val tag = parser.name
                        val text = parser.nextText().trim()
                        current?.set(tag, text)
                    }
                }
                org.xmlpull.v1.XmlPullParser.END_TAG -> {
                    if (parser.name.equals("File", ignoreCase = true)) {
                        val values = current
                        val remote = normalizeRemoteName(values?.get("NAME").orEmpty())
                        mediaItem(
                            remoteName = remote,
                            sizeBytes = values?.get("SIZE")?.toLongOrNull() ?: -1L,
                            timecode = values?.get("TIMECODE")?.toLongOrNull() ?: 0L,
                        )?.let(items::add)
                        current = null
                    }
                }
            }
            event = parser.next()
        }
        return items.distinctBy { it.remoteName }
    }

    private fun mediaItem(remoteName: String, sizeBytes: Long, timecode: Long): EyevueMediaItem? {
        if (remoteName.isBlank()) return null
        val fileName = File(remoteName).name
        val lower = fileName.lowercase()
        val type = when {
            lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> EyevueMediaType.PHOTO
            lower.endsWith(".mp4") -> EyevueMediaType.VIDEO
            lower.endsWith(".opus") || lower.endsWith(".ogg") || lower.endsWith(".wav") -> EyevueMediaType.AUDIO
            else -> return null
        }
        return EyevueMediaItem(remoteName, fileName, type, sizeBytes, timecode)
    }

    private fun mediaUrl(profile: EyevueMediaProfile, item: EyevueMediaItem): String {
        val path = item.remoteName.trimStart('/')
        return if (profile.isTSeries) {
            "http://${profile.baseIp}/${encodePath(path)}"
        } else {
            val relative = if (path.startsWith("DCIM/", ignoreCase = true)) {
                path
            } else {
                "DCIM/100HUNTI/$path"
            }
            "http://${profile.baseIp}/${encodePath(relative)}"
        }
    }

    private fun normalizeRemoteName(value: String): String = value
        .trim()
        .replace('\\', '/')
        .replace(Regex("^[A-Za-z]:/"), "")
        .trimStart('/')

    private fun encodePath(path: String): String = path.split('/')
        .filter { it.isNotBlank() }
        .joinToString("/") { segment ->
            URLEncoder.encode(segment, StandardCharsets.UTF_8.name()).replace("+", "%20")
        }
}
