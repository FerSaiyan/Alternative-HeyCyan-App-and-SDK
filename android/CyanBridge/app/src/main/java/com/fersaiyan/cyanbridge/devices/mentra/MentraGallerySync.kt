package com.fersaiyan.cyanbridge.devices.mentra

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.fersaiyan.cyanbridge.network.GlassesNetworkSessionCoordinator
import com.fersaiyan.cyanbridge.shared.devices.mentra.MentraGalleryApi
import com.fersaiyan.cyanbridge.shared.devices.mentra.MentraGalleryItem
import com.fersaiyan.cyanbridge.shared.devices.mentra.MentraGalleryParser
import com.fersaiyan.cyanbridge.shared.devices.mentra.MentraGalleryProtocol
import com.fersaiyan.cyanbridge.shared.devices.mentra.MentraGallerySyncPlan
import com.fersaiyan.cyanbridge.shared.devices.mentra.MentraGallerySyncPlanner
import com.fersaiyan.cyanbridge.shared.devices.mentra.MentraMediaKind
import com.fersaiyan.cyanbridge.shared.devices.mentra.MentraPlannedFile
import com.mentra.bluetoothsdk.HotspotStatus
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

data class MentraGallerySyncProgress(
    val detail: String,
    val plan: MentraGallerySyncPlan? = null,
    val photosDone: Int = 0,
    val videosDone: Int = 0,
)

data class MentraGallerySyncResult(
    val downloaded: Int,
    val failed: Int,
    val alreadyOnPhone: Int,
    val unsupported: Int,
    /** Files offered for deletion (verified on the phone). */
    val deletable: Int,
    val deleted: Int,
    val deleteDeclined: Boolean,
)

/**
 * Downloads the Mentra Live gallery: turns the glasses hotspot on over Bluetooth, joins it
 * ([MentraHotspotNetwork]), lists the gallery, downloads each new photo and video, optionally
 * deletes verified files from the glasses, then leaves the hotspot and turns it off again (only
 * if this sync turned it on). Protocol details live in [MentraGalleryApi] / [MentraGalleryParser].
 */
class MentraGallerySync(
    context: Context,
    private val manager: MentraLiveManager,
    private val temporaryDirectory: File,
) {
    private val context = context.applicationContext

    /**
     * Runs one sync. [phoneGallery] lists what the phone gallery already holds
     * ([MentraGallerySyncPlanner.plan]); [onFile] imports a verified download and returns true
     * when it was saved; [confirmDelete] asks whether to delete that many files from the glasses.
     * Throws when the sync cannot start or the link drops.
     */
    suspend fun sync(
        phoneGallery: suspend () -> Map<String, Long?>,
        onState: suspend (MentraGallerySyncProgress) -> Unit,
        onProgress: suspend (MentraPlannedFile, Long, Long) -> Unit,
        onFile: suspend (MentraPlannedFile, File) -> Boolean,
        confirmDelete: suspend (Int) -> Boolean,
    ): MentraGallerySyncResult {
        check(manager.ready) { "Mentra Live is not ready" }
        val link = MentraHotspotNetwork(context)
        var openedHotspot = false
        var http: OkHttpClient? = null
        temporaryDirectory.mkdirs()
        try {
            onState(MentraGallerySyncProgress("Turning on the glasses hotspot"))
            val hotspot = manager.hotspot() ?: run {
                // A missing reply does not prove the hotspot stayed off, so it is ours to stop.
                openedHotspot = true
                val status = withTimeout(HOTSPOT_TIMEOUT_MS) { manager.setHotspot(true) }
                (status as? HotspotStatus.Enabled)?.also { delay(HOTSPOT_BROADCAST_DELAY_MS) }
                    ?: throw IOException("The glasses hotspot did not turn on (${status.state})")
            }
            Log.i(TAG, "Glasses hotspot ${hotspot.ssid} at ${hotspot.localIp} (openedBySync=$openedHotspot)")
            if (hotspot.ssid.isBlank() || hotspot.localIp.isBlank()) {
                throw IOException("The glasses did not report their hotspot name or address")
            }

            onState(MentraGallerySyncProgress("Joining ${hotspot.ssid}"))
            val network = link.join(hotspot.ssid, hotspot.password)
            val client = GlassesNetworkSessionCoordinator.routes(context).networkBoundHttpClient(BASE_CLIENT, network)
            http = client
            val base = MentraGalleryApi.baseUrl(hotspot.localIp)
            awaitServer(client, base, link)

            onState(MentraGallerySyncProgress("Reading the glasses gallery"))
            val (protocol, items) = list(client, base)
            val plan = MentraGallerySyncPlanner.plan(items, phoneGallery())
            Log.i(
                TAG,
                "Gallery $protocol: ${items.size} listed, ${plan.downloads.size} new " +
                    "(${plan.photoCount} photos, ${plan.videoCount} videos), " +
                    "${plan.alreadyOnPhone.size} already on the phone, ${plan.unsupported.size} unsupported",
            )
            plan.unsupported.forEach { Log.i(TAG, "Left on the glasses (unsupported): ${it.remoteFile}") }

            val verified = mutableSetOf<String>()
            var failed = 0
            var photosDone = 0
            var videosDone = 0
            onState(MentraGallerySyncProgress("Downloading 0/${plan.downloads.size}", plan))
            for (planned in plan.downloads) {
                coroutineContext.ensureActive()
                ensureLinked(link)
                try {
                    val file = download(client, base, planned, onProgress)
                    val saved = try {
                        onFile(planned, file)
                    } finally {
                        file.delete()
                    }
                    if (!saved) throw IOException("The phone gallery did not accept ${planned.localName}")
                    verified += planned.localName
                    if (planned.item.kind == MentraMediaKind.VIDEO) {
                        videosDone++
                    } else {
                        photosDone++
                    }
                    Log.i(TAG, "Saved ${planned.item.remoteFile} as ${planned.localName} (${planned.item.sizeBytes} bytes)")
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    failed++
                    Log.w(TAG, "Download of ${planned.item.remoteFile} failed: ${error.message}")
                    ensureLinked(link)
                }
                onState(
                    MentraGallerySyncProgress(
                        detail = "Downloaded ${verified.size}/${plan.downloads.size}" +
                            if (failed > 0) " ($failed failed)" else "",
                        plan = plan,
                        photosDone = photosDone,
                        videosDone = videosDone,
                    ),
                )
            }
            if (plan.downloads.isNotEmpty()) Log.i(TAG, "Downloads finished: ${verified.size} saved, $failed failed")

            val deletable = plan.deletable(verified)
            var deleted = 0
            var declined = false
            if (deletable.isNotEmpty()) {
                // The glasses stop their hotspot after about two minutes without HTTP traffic,
                // so keep it alive while the user decides.
                val confirmed = coroutineScope {
                    val keepAlive = launch(Dispatchers.IO) {
                        while (isActive) {
                            delay(KEEP_ALIVE_INTERVAL_MS)
                            runCatching { fetch(client, MentraGalleryApi.statusUrl(base)) }
                        }
                    }
                    try {
                        confirmDelete(deletable.size)
                    } finally {
                        keepAlive.cancel()
                    }
                }
                if (confirmed) {
                    ensureLinked(link)
                    onState(MentraGallerySyncProgress("Deleting ${deletable.size} files from the glasses", plan, photosDone, videosDone))
                    deleted = delete(client, base, protocol, deletable)
                    Log.i(TAG, "Deleted $deleted of ${deletable.size} verified files from the glasses ($protocol)")
                } else {
                    declined = true
                    Log.i(TAG, "Kept ${deletable.size} verified files on the glasses")
                }
            }
            return MentraGallerySyncResult(
                downloaded = verified.size,
                failed = failed,
                alreadyOnPhone = plan.alreadyOnPhone.size,
                unsupported = plan.unsupported.size,
                deletable = deletable.size,
                deleted = deleted,
                deleteDeclined = declined,
            )
        } finally {
            withContext(NonCancellable) {
                // Leave the phone side first, then stop the glasses AP (the MentraOS order).
                http?.connectionPool?.evictAll()
                link.close()
                if (openedHotspot) {
                    runCatching { withTimeout(HOTSPOT_TIMEOUT_MS) { manager.setHotspot(false) } }
                        .onSuccess { Log.i(TAG, "Glasses hotspot turned off (${it.state})") }
                        .onFailure { Log.w(TAG, "Could not turn the glasses hotspot off: ${it.message}") }
                }
                temporaryDirectory.deleteRecursively()
            }
        }
    }

    private fun ensureLinked(link: MentraHotspotNetwork) {
        if (!manager.connected) throw IOException("Mentra Live disconnected during the gallery sync")
        if (link.lost.value) throw IOException("The glasses hotspot connection was lost")
    }

    private suspend fun awaitServer(client: OkHttpClient, base: String, link: MentraHotspotNetwork) {
        repeat(SERVER_PROBE_ATTEMPTS) { attempt ->
            ensureLinked(link)
            val reachable = withContext(Dispatchers.IO) {
                runCatching { fetch(client, MentraGalleryApi.statusUrl(base)) != null }.getOrDefault(false)
            }
            if (reachable) {
                Log.i(TAG, "Gallery server reachable at $base after ${attempt + 1} attempt(s)")
                return
            }
            delay(SERVER_PROBE_INTERVAL_MS)
        }
        throw IOException("The glasses gallery server did not answer at $base")
    }

    /** v3 manifest when the firmware offers it, else `/api/sync`, else `/api/gallery`. */
    private suspend fun list(client: OkHttpClient, base: String): Pair<MentraGalleryProtocol, List<MentraGalleryItem>> =
        withContext(Dispatchers.IO) {
            // A failed probe must not break older firmware (MentraOS does the same).
            val capabilities = runCatching { fetch(client, MentraGalleryApi.capabilitiesUrl(base)) }
                .getOrNull()
                ?.let { runCatching { MentraGalleryParser.capabilities(it) }.getOrNull() }
            if (capabilities != null) {
                val items = mutableListOf<MentraGalleryItem>()
                var cursor: String? = null
                var pages = 0
                do {
                    val body = fetch(client, MentraGalleryApi.manifestUrl(base, capabilities.maxManifestPageSize, cursor))
                        ?: throw IOException("The glasses gallery manifest is missing")
                    val page = MentraGalleryParser.manifestPage(body)
                    items += page.items
                    cursor = page.nextCursor
                    pages++
                } while (cursor != null && pages < MAX_MANIFEST_PAGES)
                return@withContext MentraGalleryProtocol.V3_MANIFEST to items
            }
            val listing = fetch(client, MentraGalleryApi.syncUrl(base, CLIENT_ID))
                ?: fetch(client, MentraGalleryApi.galleryUrl(base))
                ?: throw IOException("The glasses offer no gallery listing")
            MentraGalleryParser.listing(listing)
        }

    private suspend fun download(
        client: OkHttpClient,
        base: String,
        planned: MentraPlannedFile,
        onProgress: suspend (MentraPlannedFile, Long, Long) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val expected = planned.item.sizeBytes
        val output = File(temporaryDirectory, planned.localName).apply { delete() }
        val request = Request.Builder()
            .url(MentraGalleryApi.downloadUrl(base, planned.item.remoteFile))
            .header("Accept", "*/*")
            .get()
            .build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code} for ${planned.item.remoteFile}")
                val body = response.body ?: throw IOException("Empty response for ${planned.item.remoteFile}")
                var copied = 0L
                var lastReportMs = 0L
                body.byteStream().use { input ->
                    output.outputStream().buffered(BUFFER_BYTES).use { target ->
                        val buffer = ByteArray(BUFFER_BYTES)
                        while (true) {
                            coroutineContext.ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            target.write(buffer, 0, count)
                            copied += count
                            val now = SystemClock.elapsedRealtime()
                            if (now - lastReportMs >= PROGRESS_INTERVAL_MS) {
                                lastReportMs = now
                                onProgress(planned, copied, expected)
                            }
                        }
                    }
                }
                if (!MentraGallerySyncPlanner.isCompleteDownload(expected, copied)) {
                    throw IOException("Incomplete download of ${planned.item.remoteFile}: $copied/$expected bytes")
                }
            }
            output
        } catch (error: Throwable) {
            output.delete()
            throw error
        }
    }

    private suspend fun delete(
        client: OkHttpClient,
        base: String,
        protocol: MentraGalleryProtocol,
        items: List<MentraGalleryItem>,
    ): Int = withContext(Dispatchers.IO) {
        when (protocol) {
            // Recoverable trash on the glasses, one capture at a time like MentraOS.
            MentraGalleryProtocol.V3_MANIFEST -> items.count { item ->
                runCatching {
                    MentraGalleryParser.ackAccepted(
                        post(client, MentraGalleryApi.ackUrl(base), MentraGalleryApi.ackBody(item.captureId, MentraGalleryApi.ackId(item))),
                    )
                }.getOrElse {
                    Log.w(TAG, "Glasses did not acknowledge ${item.captureId}: ${it.message}")
                    false
                }
            }
            MentraGalleryProtocol.V2_CAPTURES,
            MentraGalleryProtocol.LEGACY_FILES,
            -> runCatching {
                val names = items.map { it.captureId }
                val deleted = MentraGalleryParser.deletedFiles(
                    post(client, MentraGalleryApi.deleteFilesUrl(base), MentraGalleryApi.deleteFilesBody(names)),
                )
                names.count { it in deleted }
            }.getOrElse {
                Log.w(TAG, "Glasses did not delete the files: ${it.message}")
                0
            }
        }
    }

    /** GET as text; null for 404 (endpoint absent on this firmware). */
    private fun fetch(client: OkHttpClient, url: String): String? {
        val request = Request.Builder().url(url).header("Accept", "application/json").get().build()
        client.newCall(request).execute().use { response ->
            if (response.code == 404) return null
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} for $url")
            return response.body?.string() ?: throw IOException("Empty response for $url")
        }
    }

    private fun post(client: OkHttpClient, url: String, json: String): String {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .post(json.toRequestBody(JSON))
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} for $url")
            return response.body?.string() ?: throw IOException("Empty response for $url")
        }
    }

    companion object {
        const val TAG = "MentraGallery"
        private const val CLIENT_ID = "cyanbridge"
        private const val HOTSPOT_TIMEOUT_MS = 30_000L

        /** MentraOS waits this long after enabling for the AP to start broadcasting. */
        private const val HOTSPOT_BROADCAST_DELAY_MS = 3_000L
        private const val SERVER_PROBE_ATTEMPTS = 20
        private const val SERVER_PROBE_INTERVAL_MS = 500L
        private const val KEEP_ALIVE_INTERVAL_MS = 30_000L
        private const val MAX_MANIFEST_PAGES = 50
        private const val BUFFER_BYTES = 128 * 1024
        private const val PROGRESS_INTERVAL_MS = 250L
        private val JSON = "application/json".toMediaType()

        /** The glasses stop the hotspot after ~120 s idle, so a stalled read fails well before. */
        private val BASE_CLIENT = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
