package com.fersaiyan.cyanbridge.plugins.walkingaid

import android.content.Context
import android.util.Log
import com.fersaiyan.cyanbridge.localmodels.engine.NpuArtifactDownloader
import com.fersaiyan.cyanbridge.localmodels.engine.NpuArtifacts
import com.fersaiyan.cyanbridge.plugins.walkingaid.ocr.OcrModelStore
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

data class WalkingAidModelDownloadProgress(
    val downloadedBytes: Long,
    val totalBytes: Long,
) {
    val percent: Int
        get() = if (totalBytes <= 0L) 0 else ((downloadedBytes * 100L) / totalBytes).toInt().coerceIn(0, 100)
}

object WalkingAidModelInstaller {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .build()

    fun installedFile(context: Context, entry: WalkingAidModelCatalogEntry): File =
        File(context.filesDir, entry.expectedFilename)

    fun isInstalled(context: Context, entry: WalkingAidModelCatalogEntry): Boolean =
        installedFile(context, entry).let { it.exists() && it.length() >= 1024L }

    suspend fun install(
        context: Context,
        entry: WalkingAidModelCatalogEntry,
        onProgress: (WalkingAidModelDownloadProgress) -> Unit,
    ) {
        val target = installedFile(context, entry)
        val partial = File(target.parentFile, "${target.name}.part")
        val requiredBytes = entry.sizeBytes + 250L * 1024L * 1024L
        check(context.filesDir.usableSpace >= requiredBytes) { "Not enough free storage for ${entry.displayName}" }
        partial.delete()

        client.newCall(Request.Builder().url(entry.sourceUrl).build()).execute().use { response ->
            check(response.isSuccessful) { "Download failed: HTTP ${response.code}" }
            val body = requireNotNull(response.body) { "Download failed: empty response" }
            val totalBytes = body.contentLength().takeIf { it > 0L } ?: entry.sizeBytes
            body.byteStream().use { input ->
                FileOutputStream(partial).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var downloadedBytes = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count <= 0) break
                        output.write(buffer, 0, count)
                        downloadedBytes += count
                        onProgress(WalkingAidModelDownloadProgress(downloadedBytes, totalBytes))
                    }
                    output.flush()
                    check(downloadedBytes >= 1024L) { "Downloaded model is incomplete" }
                    if (body.contentLength() > 0L) {
                        check(downloadedBytes == body.contentLength()) { "Downloaded model is incomplete" }
                    }
                }
            }
        }

        check(partial.length() == entry.sizeBytes) {
            "Downloaded ${entry.displayName} has unexpected size ${partial.length()} (expected ${entry.sizeBytes})"
        }
        check(sha256(partial).equals(entry.sha256, ignoreCase = true)) {
            "Downloaded ${entry.displayName} failed SHA-256 verification"
        }

        if (target.exists()) target.delete()
        check(partial.renameTo(target)) { "Could not install ${entry.displayName}" }
        installNpuCompanion(context, entry) { percent -> onProgress(WalkingAidModelDownloadProgress(percent.toLong(), 100L)) }
        if (entry.id in NPU_MODELS) installReader(context) { percent -> onProgress(WalkingAidModelDownloadProgress(percent.toLong(), 100L)) }
    }

    /**
     * The NPU build of [entry] for this phone (`<detector stem>_<Vendor>_<SoC>.tflite`, which LiteRtVisionBackend
     * picks with the NPU backend), plus the NPU runtime. Not fatal: Walking Aid falls back to the GPU/CPU model.
     */
    fun installNpuCompanion(
        context: Context,
        entry: WalkingAidModelCatalogEntry,
        onProgress: (Int) -> Unit = {},
    ): Boolean {
        val model = NPU_MODELS[entry.id] ?: return false
        val artifact = NpuArtifacts.forDevice(context, model) ?: return false
        return runCatching {
            NpuArtifactDownloader.install(context, artifact, File(context.filesDir, artifact.localName), onProgress = onProgress)
        }.onFailure { Log.w(TAG, "NPU build of ${entry.displayName} not installed: ${it.message}") }.isSuccess
    }

    /**
     * For detectors installed before their NPU builds existed (2.5.0 and older): fetch them in the background, plus
     * the ride/bus reader models that go with YOLO11n.
     */
    fun ensureNpuCompanions(context: Context) {
        WalkingAidModelCatalog.entries.filter { it.id in NPU_MODELS && isInstalled(context, it) }
            .forEach { installNpuCompanion(context, it) }
        installReader(context)
    }

    /** The sign and plate reader (PP-OCRv6, CPU + this phone's NPU builds) used by the ride and bus goals. */
    private fun installReader(context: Context, onProgress: (Int) -> Unit = {}) {
        if (!isInstalled(context, WalkingAidModelCatalog.detectorFor(WalkingAidPreferences.MODEL_TYPE_YOLO11))) return
        runCatching { OcrModelStore.install(context, onProgress) }
            .onFailure { Log.w(TAG, "Reader models not installed: ${it.message}") }
    }

    private const val TAG = "WalkingAidModelInstaller"

    /** Walking Aid catalog entry -> its per-SoC NPU builds. */
    private val NPU_MODELS = mapOf("yolo11n-tflite" to NpuArtifacts.Model.YOLO11N)

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count <= 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
