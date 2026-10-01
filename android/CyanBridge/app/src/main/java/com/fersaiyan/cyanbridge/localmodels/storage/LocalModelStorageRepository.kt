package com.fersaiyan.cyanbridge.localmodels.storage

import android.content.Context
import android.net.Uri
import android.os.StatFs
import com.fersaiyan.cyanbridge.localmodels.catalog.LocalModelCatalogEntry
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

data class InstalledLocalModel(
    val id: String,
    val catalogId: String?,
    val displayName: String,
    val fileName: String,
    val absolutePath: String,
    val sizeBytes: Long,
    val sha256: String?,
    val quantization: String?,
    val promptTemplateId: String?,
    val sourceUrl: String?,
    val licenseTermsNote: String?,
    val importedAtMs: Long,
)

object LocalModelStorageRepository {
    private const val PREFS = "local_models_registry"
    private const val KEY_INSTALLED_MODELS = "installed_models"
    private const val KEY_SELECTED_MODEL_ID = "selected_model_id"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun baseDir(context: Context): File = File(context.filesDir, "local_models")
    fun modelsDir(context: Context): File = File(baseDir(context), "models")
    fun tempDir(context: Context): File = File(baseDir(context), "tmp")

    fun ensureDirs(context: Context) {
        modelsDir(context).mkdirs()
        tempDir(context).mkdirs()
    }

    fun availableStorageBytes(context: Context): Long {
        val statFs = StatFs(baseDir(context).absolutePath)
        return statFs.availableBytes
    }

    fun listInstalled(context: Context): List<InstalledLocalModel> {
        val raw = prefs(context).getString(KEY_INSTALLED_MODELS, "[]") ?: "[]"
        val arr = runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
        val models = mutableListOf<InstalledLocalModel>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val model = InstalledLocalModel(
                id = obj.optString("id"),
                catalogId = obj.optString("catalog_id").ifBlank { null },
                displayName = obj.optString("display_name"),
                fileName = obj.optString("file_name"),
                absolutePath = obj.optString("absolute_path"),
                sizeBytes = obj.optLong("size_bytes"),
                sha256 = obj.optString("sha256").ifBlank { null },
                quantization = obj.optString("quantization").ifBlank { null },
                promptTemplateId = obj.optString("prompt_template_id").ifBlank { null },
                sourceUrl = obj.optString("source_url").ifBlank { null },
                licenseTermsNote = obj.optString("license_terms_note").ifBlank { null },
                importedAtMs = obj.optLong("imported_at_ms", 0L),
            )
            models += model
        }
        return models
            .filter { it.id.isNotBlank() }
            .sortedByDescending { it.importedAtMs }
    }

    fun getInstalled(context: Context, id: String?): InstalledLocalModel? {
        if (id.isNullOrBlank()) return null
        return listInstalled(context).firstOrNull { it.id == id }
    }

    fun findByCatalogId(context: Context, catalogId: String): InstalledLocalModel? {
        return listInstalled(context).firstOrNull { it.catalogId == catalogId }
    }

    fun persistInstalled(context: Context, model: InstalledLocalModel) {
        val current = listInstalled(context).toMutableList()
        val idx = current.indexOfFirst { it.id == model.id }
        if (idx >= 0) {
            current[idx] = model
        } else {
            current += model
        }
        saveInstalledList(context, current)
    }

    fun removeInstalled(context: Context, id: String): Boolean {
        val current = listInstalled(context).toMutableList()
        val model = current.firstOrNull { it.id == id } ?: return false
        current.removeAll { it.id == id }
        saveInstalledList(context, current)
        if (getSelectedModelId(context) == id) {
            setSelectedModelId(context, current.firstOrNull()?.id)
        }
        runCatching { File(model.absolutePath).delete() }
        LocalModelProjectorStore.remove(context, model.id)
        return true
    }

    fun cleanupMissingModels(context: Context): Int {
        val current = listInstalled(context)
        val filtered = current.filter { File(it.absolutePath).exists() }
        if (filtered.size == current.size) return 0
        saveInstalledList(context, filtered)
        if (getSelectedModelId(context)?.let { sid -> filtered.none { it.id == sid } } == true) {
            setSelectedModelId(context, filtered.firstOrNull()?.id)
        }
        return current.size - filtered.size
    }

    fun getSelectedModelId(context: Context): String? {
        return prefs(context).getString(KEY_SELECTED_MODEL_ID, null)
    }

    fun setSelectedModelId(context: Context, modelId: String?) {
        prefs(context).edit().putString(KEY_SELECTED_MODEL_ID, modelId).apply()
    }

    fun resolveSelectedModel(context: Context): InstalledLocalModel? {
        cleanupMissingModels(context)
        val all = listInstalled(context)
        if (all.isEmpty()) return null
        val selected = getSelectedModelId(context)
        val picked = all.firstOrNull { it.id == selected } ?: all.first()
        if (picked.id != selected) {
            setSelectedModelId(context, picked.id)
        }
        return picked
    }

    fun registerCatalogModel(context: Context, entry: LocalModelCatalogEntry, file: File): InstalledLocalModel {
        val sha = LocalModelFileUtils.sha256Hex(file)
        val model = InstalledLocalModel(
            id = entry.id,
            catalogId = entry.id,
            displayName = entry.displayName,
            fileName = file.name,
            absolutePath = file.absolutePath,
            sizeBytes = file.length(),
            sha256 = sha,
            quantization = entry.quantization,
            promptTemplateId = entry.promptTemplateId,
            sourceUrl = entry.sourceUrl,
            licenseTermsNote = entry.licenseTermsNote,
            importedAtMs = System.currentTimeMillis(),
        )
        persistInstalled(context, model)
        setSelectedModelId(context, model.id)
        return model
    }

    fun registerImportedModel(
        context: Context,
        displayName: String,
        file: File,
        quantization: String? = null,
    ): InstalledLocalModel {
        val sha = LocalModelFileUtils.sha256Hex(file)
        val id = "import-${sha.take(12)}"
        val model = InstalledLocalModel(
            id = id,
            catalogId = null,
            displayName = displayName,
            fileName = file.name,
            absolutePath = file.absolutePath,
            sizeBytes = file.length(),
            sha256 = sha,
            quantization = quantization,
            promptTemplateId = null,
            sourceUrl = null,
            licenseTermsNote = null,
            importedAtMs = System.currentTimeMillis(),
        )
        persistInstalled(context, model)
        setSelectedModelId(context, model.id)
        return model
    }

    fun copyUriToManagedModelFile(
        context: Context,
        uri: Uri,
        preferredName: String,
        onProgress: ((copiedBytes: Long) -> Unit)? = null,
    ): File {
        ensureDirs(context)
        val cleanName = LocalModelFileUtils.sanitizeFileName(preferredName)
        val target = uniqueFileIn(modelsDir(context), cleanName)
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Cannot open import file" }
            FileOutputStream(target).use { output ->
                val buf = ByteArray(DEFAULT_BUFFER_SIZE)
                var copied = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    output.write(buf, 0, n)
                    copied += n
                    onProgress?.invoke(copied)
                }
                output.flush()
            }
        }
        return target
    }

    private fun saveInstalledList(context: Context, items: List<InstalledLocalModel>) {
        val arr = JSONArray()
        items.forEach { model ->
            arr.put(
                JSONObject()
                    .put("id", model.id)
                    .put("catalog_id", model.catalogId.orEmpty())
                    .put("display_name", model.displayName)
                    .put("file_name", model.fileName)
                    .put("absolute_path", model.absolutePath)
                    .put("size_bytes", model.sizeBytes)
                    .put("sha256", model.sha256.orEmpty())
                    .put("quantization", model.quantization.orEmpty())
                    .put("prompt_template_id", model.promptTemplateId.orEmpty())
                    .put("source_url", model.sourceUrl.orEmpty())
                    .put("license_terms_note", model.licenseTermsNote.orEmpty())
                    .put("imported_at_ms", model.importedAtMs),
            )
        }
        prefs(context).edit().putString(KEY_INSTALLED_MODELS, arr.toString()).apply()
    }

    private fun uniqueFileIn(dir: File, fileName: String): File {
        var attempt = 0
        val cleanName = LocalModelFileUtils.sanitizeFileName(fileName)
        val dot = cleanName.lastIndexOf('.')
        val base = if (dot > 0) cleanName.substring(0, dot) else cleanName
        val ext = if (dot > 0) cleanName.substring(dot) else ""
        while (true) {
            val candidate = if (attempt == 0) {
                File(dir, cleanName)
            } else {
                File(dir, "${base}_$attempt$ext")
            }
            if (!candidate.exists()) return candidate
            attempt += 1
        }
    }
}
