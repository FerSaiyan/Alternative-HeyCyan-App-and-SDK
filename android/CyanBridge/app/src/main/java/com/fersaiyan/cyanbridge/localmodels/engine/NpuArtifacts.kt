package com.fersaiyan.cyanbridge.localmodels.engine

import android.content.Context
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Per-SoC NPU builds of the app's small models (Walking Aid YOLO11n, the voice filter's WeSpeaker, the Walking Aid
 * OCR reader), published in the public Hugging Face repo Fertroll10/cyanbridge-npu-models (folder app-2.5.1/,
 * pinned to a commit) and verified by size + SHA-256. Each one is an AOT compile of the CPU model the feature
 * already uses, with the same input/output tensors, so the feature only swaps the file.
 *
 * Files are stored under [localName] next to their CPU model; [NpuArtifactDownloader] also fetches this phone's
 * NPU runtime (Hexagon skel on Snapdragon) so the feature never waits for it on first use.
 */
internal object NpuArtifacts {
    enum class Model(val id: String) {
        YOLO11N("yolo11n"),
        WESPEAKER("wespeaker-npu"),
        OCR_DET("ppocrv6-small-det"),
        OCR_REC("ppocrv6-small-rec"),
        /** Recognizer dictionary (CPU files only; its output spec holds the class count it belongs to). */
        OCR_DICT("ppocrv6-dict"),
    }

    data class Artifact(
        val model: Model,
        /** Null for the CPU/GPU files hosted next to the NPU builds. */
        val vendor: LiteRtNpuSupport.Vendor?,
        /** Canonical SoC id, as [LiteRtNpuSupport.Status.socModel]; "ANY" for CPU/GPU files. */
        val soc: String,
        /** File name on the phone: `<cpu model stem>_<Vendor>_<SoC>.tflite`. */
        val localName: String,
        val url: String,
        val sha256: String,
        val bytes: Long,
        /** The file's own tensors (read from the flatbuffer when the catalog was generated), in signature order. */
        val inputs: List<TensorSpec>,
        val outputs: List<TensorSpec>,
        /** WeSpeaker only: builds of different variants give embeddings that are not comparable. */
        val variant: String? = null,
    )

    /** One tensor: [scale]/[zeroPoint] are the affine quantization of 8/16-bit tensors (real = scale * (q - zp)). */
    data class TensorSpec(
        val name: String,
        val shape: IntArray,
        val dtype: NpuDType,
        val scale: Float = 0f,
        val zeroPoint: Int = 0,
    ) {
        val elements: Int get() = shape.fold(1) { acc, d -> acc * d }
    }

    private const val BASE = "https://huggingface.co/Fertroll10/cyanbridge-npu-models/resolve"

    /** Filled from the published catalog (app-2.5.1/CATALOG.json); only device-tested builds are listed. */
    internal val all: List<Artifact> = NpuArtifactList.entries

    internal fun url(revision: String, path: String) = "$BASE/$revision/$path"

    /** The build of [model] for this phone, when there is one and its NPU runtime is (or can be) installed. */
    fun forDevice(context: Context, model: Model): Artifact? {
        val npu = runCatching { LiteRtNpuSupport.status(context) }.getOrNull() ?: return null
        if (!npu.available && !npu.skelDownloadable) return null
        return find(model, npu.vendor, npu.socModel)
    }

    internal fun find(model: Model, vendor: LiteRtNpuSupport.Vendor?, soc: String?): Artifact? =
        all.firstOrNull { it.model == model && it.vendor == vendor && it.soc.equals(soc, ignoreCase = true) }
}

/** Downloads one [NpuArtifacts.Artifact] (and this phone's NPU runtime) with size + SHA-256 checks. */
internal object NpuArtifactDownloader {
    private const val TAG = "NpuArtifactDownloader"
    private val client by lazy {
        OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS).readTimeout(2, TimeUnit.MINUTES).build()
    }

    fun isInstalled(target: File, artifact: NpuArtifacts.Artifact): Boolean =
        target.isFile && target.length() == artifact.bytes

    /**
     * Installs [artifact] at [target] (blocking; call off the main thread). Fetches the Hexagon skel first on
     * Snapdragon; a skel failure is not fatal (NpuRuntimePrefetch retries it). [onProgress] gets 0..100.
     */
    fun install(
        context: Context,
        artifact: NpuArtifacts.Artifact,
        target: File,
        cancelled: () -> Boolean = { false },
        onProgress: (Int) -> Unit = {},
    ): File {
        prefetchRuntime(context, cancelled)
        if (isInstalled(target, artifact)) return target
        target.parentFile?.mkdirs()
        val partial = File(target.parentFile, "${target.name}.part")
        partial.delete()
        try {
            client.newCall(Request.Builder().url(artifact.url).build()).execute().use { response ->
                check(response.isSuccessful) { "NPU model download failed: HTTP ${response.code}" }
                val body = checkNotNull(response.body) { "NPU model download failed: empty response" }
                val digest = MessageDigest.getInstance("SHA-256")
                var done = 0L
                body.byteStream().use { input ->
                    partial.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            if (cancelled()) throw java.util.concurrent.CancellationException("NPU model download cancelled")
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            digest.update(buffer, 0, read)
                            done += read
                            check(done <= artifact.bytes) { "NPU model download is larger than expected" }
                            onProgress((done * 100 / artifact.bytes).toInt())
                        }
                    }
                }
                val hash = digest.digest().joinToString("") { "%02x".format(it) }
                check(done == artifact.bytes && hash == artifact.sha256) { "NPU model download did not match its checksum" }
            }
            if (target.exists()) target.delete()
            check(partial.renameTo(target)) { "Could not save ${target.name}" }
            Log.i(TAG, "Installed ${target.name} (${artifact.bytes} bytes)")
            return target
        } finally {
            if (partial.exists()) partial.delete()
        }
    }

    private fun prefetchRuntime(context: Context, cancelled: () -> Boolean) {
        val npu = runCatching { LiteRtNpuSupport.status(context) }.getOrNull() ?: return
        val hexagon = npu.hexagon
        if (npu.available || !npu.skelDownloadable || hexagon == null) return
        runCatching { QnnSkels.ensure(context, hexagon, cancelled) }
            .onFailure { Log.w(TAG, "NPU runtime download failed; the model still installs and the runtime retries later", it) }
    }
}
