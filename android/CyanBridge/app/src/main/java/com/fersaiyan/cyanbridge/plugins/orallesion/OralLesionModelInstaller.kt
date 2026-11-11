package com.fersaiyan.cyanbridge.plugins.orallesion

import android.content.Context
import android.util.Log
import com.fersaiyan.cyanbridge.localmodels.settings.LocalModelSettingsRepository
import com.fersaiyan.cyanbridge.localmodels.storage.LocalModelStorageRepository
import com.fersaiyan.cyanbridge.shared.plugins.orallesion.OralLesionModel
import com.fersaiyan.cyanbridge.shared.plugins.orallesion.OralLesionModelState
import com.fersaiyan.cyanbridge.shared.prefs.PluginPrefs
import com.fersaiyan.cyanbridge.shared.prefs.get
import com.fersaiyan.cyanbridge.shared.prefs.set
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/**
 * Installs the bench's `.litertlm` files: the pinned resolve URL, the app's Hugging Face token as a
 * Bearer header (huggingface.co only; the CDN redirect drops it), resume of a partial download,
 * then size and SHA-256 checks, both mandatory. A 401/403 is reported as no access to the gated
 * repo; there is no fallback to another source. A stock model the app already installed is reused
 * once its SHA-256 matches.
 */
internal object OralLesionModelInstaller {
    private const val TAG = "OralLesionModels"
    private const val HEADROOM_BYTES = 300L * 1024 * 1024
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .build()

    private fun dir(context: Context) = File(context.filesDir, "oral_lesion_models").apply { mkdirs() }

    fun file(context: Context, model: OralLesionModel) = File(dir(context), "${model.id}__${model.fileName}")

    private fun marker(file: File) = File(file.path + ".sha256")

    suspend fun state(context: Context, model: OralLesionModel): OralLesionModelState = withContext(Dispatchers.IO) {
        val own = file(context, model)
        if (own.isFile && own.length() == model.sizeBytes && marker(own).takeIf { it.isFile }?.readText()?.trim() == model.sha256) {
            return@withContext OralLesionModelState.Installed(own.absolutePath)
        }
        appCatalogCopy(context, model)?.let { return@withContext OralLesionModelState.Installed(it.absolutePath) }
        if (model.gated && token(context).isEmpty()) OralLesionModelState.NeedsToken else OralLesionModelState.NotInstalled
    }

    /** The app's own install of the same stock file, if its SHA-256 matches (checked once, then cached). */
    private fun appCatalogCopy(context: Context, model: OralLesionModel): File? {
        val catalogId = model.appCatalogId ?: return null
        val installed = LocalModelStorageRepository.findByCatalogId(context, catalogId) ?: return null
        val file = File(installed.absolutePath).takeIf { it.isFile && it.length() == model.sizeBytes } ?: return null
        val key = "${file.length()}:${file.lastModified()}"
        val cache = runCatching { JSONObject(PluginPrefs.OralLesionBench.verifiedFilesJson[context]) }.getOrElse { JSONObject() }
        val cached = cache.optString(file.absolutePath)
        val sha = if (cached.startsWith("$key:")) cached.substringAfterLast(':') else sha256(file).also {
            cache.put(file.absolutePath, "$key:$it")
            PluginPrefs.OralLesionBench.verifiedFilesJson[context] = cache.toString()
        }
        return file.takeIf { sha.equals(model.sha256, ignoreCase = true) }
    }

    private fun token(context: Context) = LocalModelSettingsRepository.getHuggingFaceToken(context)

    suspend fun install(context: Context, model: OralLesionModel, onState: (OralLesionModelState) -> Unit) = withContext(Dispatchers.IO) {
        val token = token(context)
        if (model.gated && token.isEmpty()) return@withContext onState(OralLesionModelState.NeedsToken)
        val target = file(context, model)
        val partial = File(target.path + ".part")
        if (partial.length() > model.sizeBytes) partial.delete()
        val needed = model.sizeBytes - partial.length() + HEADROOM_BYTES
        if (dir(context).usableSpace < needed) {
            return@withContext onState(OralLesionModelState.Failed("Not enough free storage (${needed / 1_000_000} MB needed)"))
        }
        onState(OralLesionModelState.Downloading(percent(partial.length(), model.sizeBytes)))
        val request = Request.Builder().url(model.url).apply {
            if (token.isNotEmpty() && model.url.startsWith("https://huggingface.co/")) header("Authorization", "Bearer $token")
            if (partial.length() > 0) header("Range", "bytes=${partial.length()}-")
        }.build()
        client.newCall(request).execute().use { response ->
            if (response.code == 401 || response.code == 403) {
                Log.w(TAG, "No access to ${model.repo} (HTTP ${response.code})")
                return@withContext onState(OralLesionModelState.AccessDenied)
            }
            check(response.isSuccessful) { "Download failed: HTTP ${response.code}" }
            val append = response.code == 206
            val body = requireNotNull(response.body) { "Download failed: empty response" }
            var written = if (append) partial.length() else 0L
            var lastPercent = -1
            body.byteStream().use { input ->
                FileOutputStream(partial, append).use { output ->
                    val buffer = ByteArray(1 shl 16)
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        written += count
                        val now = percent(written, model.sizeBytes)
                        if (now != lastPercent) {
                            lastPercent = now
                            onState(OralLesionModelState.Downloading(now))
                        }
                    }
                }
            }
        }
        check(partial.length() == model.sizeBytes) {
            "Downloaded ${partial.length()} bytes, expected ${model.sizeBytes}; try again to resume"
        }
        onState(OralLesionModelState.Verifying)
        val sha = sha256(partial)
        if (!sha.equals(model.sha256, ignoreCase = true)) {
            partial.delete()
            return@withContext onState(OralLesionModelState.Failed("SHA-256 mismatch; the file was deleted"))
        }
        if (target.exists()) check(target.delete()) { "Could not replace ${target.name}" }
        check(partial.renameTo(target)) { "Could not install ${target.name}" }
        marker(target).writeText(sha)
        onState(OralLesionModelState.Installed(target.absolutePath))
    }

    private fun percent(done: Long, total: Long) = if (total <= 0) 0 else ((done * 100) / total).toInt().coerceIn(0, 100)

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 20)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
