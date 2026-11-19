package com.fersaiyan.cyanbridge.ai.speaker

import android.content.Context
import android.util.Log
import com.fersaiyan.cyanbridge.localmodels.storage.LocalModelFileUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/**
 * Installed speaker models live in `filesDir/speaker_models/`. A model is installed either by
 * [download] from [SpeakerModelSpec.sourceUrl] (once it is hosted) or by staging the file in the
 * app's external files directory, which needs no storage permission:
 *
 * ```
 * adb push ecapa_spkrec_voxceleb_2s.tflite \
 *   /sdcard/Android/data/com.fersaiyan.cyanbridge/files/speaker_models/
 * ```
 *
 * Both paths verify size and SHA-256 before the file is moved into private storage.
 */
object SpeakerModelStore {
    private const val TAG = "SpeakerModelStore"
    private const val DIR = "speaker_models"

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.MINUTES)
            .build()
    }

    fun installedFile(context: Context, spec: SpeakerModelSpec): File =
        File(File(context.filesDir, DIR), spec.fileName)

    fun isInstalled(context: Context, spec: SpeakerModelSpec): Boolean =
        installedFile(context, spec).let { it.isFile && it.length() == spec.sizeBytes }

    fun stagedFile(context: Context, spec: SpeakerModelSpec): File? =
        context.getExternalFilesDir(DIR)?.let { File(it, spec.fileName) }

    /** True when [download] can fetch [spec]. */
    fun isDownloadable(spec: SpeakerModelSpec): Boolean = spec.sourceUrl != null

    /** Returns the installed file, installing a verified staged copy first when one exists. */
    fun resolve(context: Context, spec: SpeakerModelSpec): File? {
        if (isInstalled(context, spec)) return installedFile(context, spec)
        return runCatching { installStaged(context, spec) }
            .onFailure { Log.w(TAG, "Staged ${spec.id} not installed: ${it.message}") }
            .getOrNull()
    }

    fun installStaged(context: Context, spec: SpeakerModelSpec): File? {
        val staged = stagedFile(context, spec)?.takeIf { it.isFile } ?: return null
        val target = installedFile(context, spec)
        target.parentFile?.mkdirs()
        val partial = File(target.parentFile, "${target.name}.part")
        try {
            staged.copyTo(partial, overwrite = true)
            verifyAndInstall(partial, target, spec)
        } finally {
            partial.delete()
        }
        staged.delete()
        Log.i(TAG, "Installed staged ${spec.id} bytes=${target.length()}")
        return target
    }

    /** Downloads [spec] into private storage; [onProgress] gets 0..1. Returns the installed file. */
    suspend fun download(context: Context, spec: SpeakerModelSpec, onProgress: (Float) -> Unit = {}): File {
        resolve(context, spec)?.let { return it }
        val url = checkNotNull(spec.sourceUrl) { "${spec.id} is not hosted yet" }
        return downloadTo(installedFile(context, spec), spec, url, client, onProgress)
            .also { Log.i(TAG, "Downloaded ${spec.id} bytes=${it.length()}") }
    }

    /** Context-free download, size and SHA-256 check, then an atomic rename onto [target]. */
    internal suspend fun downloadTo(
        target: File,
        spec: SpeakerModelSpec,
        url: String,
        client: OkHttpClient,
        onProgress: (Float) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        target.parentFile?.mkdirs()
        val partial = File(target.parentFile, "${target.name}.part")
        try {
            client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                check(response.isSuccessful) { "Download failed: HTTP ${response.code}" }
                val body = checkNotNull(response.body) { "Download failed: empty response" }
                body.byteStream().use { input ->
                    partial.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            coroutineContext.ensureActive()
                            val n = input.read(buffer)
                            if (n < 0) break
                            output.write(buffer, 0, n)
                            done += n
                            check(done <= spec.sizeBytes) { "${spec.fileName} is larger than expected" }
                            onProgress(done.toFloat() / spec.sizeBytes)
                        }
                    }
                }
            }
            verifyAndInstall(partial, target, spec)
        } finally {
            partial.delete()
        }
        target
    }

    private fun verifyAndInstall(partial: File, target: File, spec: SpeakerModelSpec) {
        check(partial.length() == spec.sizeBytes) {
            "${spec.fileName} has ${partial.length()} bytes, expected ${spec.sizeBytes}"
        }
        check(LocalModelFileUtils.sha256Hex(partial).equals(spec.sha256, ignoreCase = true)) {
            "${spec.fileName} failed SHA-256 verification"
        }
        if (target.exists()) target.delete()
        check(partial.renameTo(target)) { "Could not install ${spec.fileName}" }
    }
}

/**
 * One background download of the default speaker model at a time, outliving the screen that
 * started it. Once installed, an enrolled profile's template is built right away so the first
 * question does not pay for it.
 */
object SpeakerModelDownloads {
    private const val TAG = "SpeakerModelStore"
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
    private val running = java.util.concurrent.atomic.AtomicBoolean(false)

    val isRunning: Boolean get() = running.get()

    /** Starts the download when [spec] is hosted and not installed yet; [onDone] gets success. */
    fun start(context: Context, spec: SpeakerModelSpec = SpeakerModels.DEFAULT, onDone: (Boolean) -> Unit = {}): Boolean {
        val app = context.applicationContext
        if (!SpeakerModelStore.isDownloadable(spec) || SpeakerModelStore.isInstalled(app, spec)) return false
        if (!running.compareAndSet(false, true)) return true
        scope.launch {
            val ok = try {
                SpeakerModelStore.download(app, spec)
                if (VoiceProfile.isEnrolled(app)) VoiceProfile.ensureTemplate(app, spec)
                true
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w(TAG, "Download of ${spec.id} failed: ${error.message}")
                false
            } finally {
                running.set(false)
            }
            onDone(ok)
        }
        return true
    }
}
