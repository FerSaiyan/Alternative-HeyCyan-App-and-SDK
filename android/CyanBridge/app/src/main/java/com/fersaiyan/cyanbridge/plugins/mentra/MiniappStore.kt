package com.fersaiyan.cyanbridge.plugins.mentra

import android.content.Context
import android.util.Log
import android.webkit.WebStorage
import com.fersaiyan.cyanbridge.shared.devices.memomind.webplugin.Sha256
import com.fersaiyan.cyanbridge.shared.plugins.mentra.MiniappApprovals
import com.fersaiyan.cyanbridge.shared.plugins.mentra.MiniappBundle
import com.fersaiyan.cyanbridge.shared.plugins.mentra.MiniappKeyValueStore
import com.fersaiyan.cyanbridge.shared.plugins.mentra.MiniappManifest
import com.fersaiyan.cyanbridge.shared.plugins.mentra.MiniappPermissionType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.io.IOException

/** Where an installed miniapp came from, for update checks (`source.json`). */
data class MiniappSourceRecord(
    val folderUrl: String,
    val etag: String?,
    val bundleSha256: String,
    val signed: Boolean,
    val lastCheckedAtMs: Long,
)

/** An installed miniapp; [key] names its private directory and its UI WebView origin. */
class InstalledMiniapp(
    val key: String,
    val manifest: MiniappManifest,
    val packageDir: File,
    val dataDir: File,
    val source: MiniappSourceRecord?,
) {
    val host: String get() = "$key.$ORIGIN_DOMAIN"
    val origin: String get() = "https://$host"
    val uiUrl: String? get() = manifest.uiEntry?.let { "$origin/$it" }
    val iconFile: File? get() = manifest.icon?.takeIf(MiniappManifest::isSafePath)?.let { File(packageDir, it) }?.takeIf { it.isFile }

    companion object {
        /** Requests to this domain are answered from the package and never reach the network. */
        const val ORIGIN_DOMAIN = "mentra-miniapp.androidplatform.net"
    }
}

/**
 * Installed Mentra miniapps in app-private storage: `files/mentra/miniapps/<key>/` holds
 * `package/` (the verified bundle, replaced atomically on update), `data/` (storage,
 * localStorage, the user's permission choices) and `source.json`. A miniapp never gets a path
 * into this tree; the UI is served file by file from `package/`.
 */
class MiniappStore(context: Context) {
    private val root = File(context.applicationContext.filesDir, "mentra/miniapps")
    private val json = Json { ignoreUnknownKeys = true }

    fun list(): List<InstalledMiniapp> =
        root.listFiles()?.filter { it.isDirectory && KEY.matches(it.name) }?.mapNotNull(::load)
            ?.sortedBy { it.manifest.name.lowercase() }.orEmpty()

    fun find(packageName: String): InstalledMiniapp? = File(root, keyOf(packageName)).takeIf { it.isDirectory }?.let(::load)

    /** Installs or replaces the miniapp; its data directory is kept. */
    fun install(bundle: MiniappBundle, folderUrl: String, etag: String?): InstalledMiniapp {
        val dir = File(root, keyOf(bundle.manifest.packageName)).apply { mkdirs() }
        val staging = File(dir, "package.tmp-${System.nanoTime()}")
        try {
            bundle.files.forEach { (path, bytes) ->
                val target = File(staging, path)
                check(target.canonicalPath.startsWith(staging.canonicalPath + File.separator)) { "unsafe path $path" }
                target.parentFile?.mkdirs()
                target.writeBytes(bytes)
            }
            File(staging, MiniappManifest.FILE_NAME).writeBytes(bundle.manifestBytes)
            val current = File(dir, PACKAGE)
            val previous = File(dir, "package.old-${System.nanoTime()}")
            if (current.exists() && !current.renameTo(previous)) throw IOException("Cannot replace the installed miniapp")
            if (!staging.renameTo(current)) {
                previous.renameTo(current)
                throw IOException("Cannot install the miniapp")
            }
            previous.deleteRecursively()
        } finally {
            staging.deleteRecursively()
        }
        File(dir, DATA).mkdirs()
        writeAtomically(File(dir, SOURCE), MiniappSourceRecord(folderUrl, etag, bundle.sha256, bundle.signed, System.currentTimeMillis()).toJson())
        return load(dir) ?: throw IOException("The installed miniapp cannot be read")
    }

    /** Records an update check that found nothing new (a 304, or the same version). */
    fun markChecked(app: InstalledMiniapp, etag: String?) {
        val source = app.source ?: return
        writeAtomically(File(app.packageDir.parentFile, SOURCE), source.copy(etag = etag ?: source.etag, lastCheckedAtMs = System.currentTimeMillis()).toJson())
    }

    fun remove(app: InstalledMiniapp) {
        File(root, app.key).deleteRecursively()
        runCatching { WebStorage.getInstance().deleteOrigin(app.origin) }
    }

    fun approvals(app: InstalledMiniapp): MiniappApprovals {
        val stored = readJson(File(app.dataDir, APPROVALS)) as? JsonObject ?: return MiniappApprovals()
        fun set(key: String) = (stored[key] as? JsonArray)?.mapNotNull { element ->
            MiniappPermissionType.entries.firstOrNull { it.name == (element as? JsonPrimitive)?.content }
        }?.toSet().orEmpty()
        val declared = app.manifest.permissions.map { it.type }.toSet()
        return MiniappApprovals(granted = set("granted") intersect declared, decided = set("decided") intersect declared)
    }

    fun saveApprovals(app: InstalledMiniapp, approvals: MiniappApprovals) {
        writeAtomically(File(app.dataDir, APPROVALS), buildJsonObject {
            put("granted", buildJsonArray { approvals.granted.forEach { add(JsonPrimitive(it.name)) } })
            put("decided", buildJsonArray { approvals.decided.forEach { add(JsonPrimitive(it.name)) } })
        }.toString())
    }

    /** `session.storage.*` ([LOCAL_STORAGE] for the polyfill's `localStorage`), persisted off the main thread. */
    fun keyValueStore(app: InstalledMiniapp, fileName: String = STORAGE): MiniappKeyValueStore {
        val file = File(app.dataDir, fileName)
        val initial = runCatching { file.takeIf { it.isFile }?.readText() }.getOrNull()
        return MiniappKeyValueStore(initial) { content -> writer.launch { writeAtomically(file, content) } }
    }

    /** The package file for [path]; null outside the package or for the signature directory. */
    fun payloadFile(app: InstalledMiniapp, path: String): File? {
        if (!MiniappManifest.isSafePath(path) || path.startsWith("META-INF/")) return null
        val file = File(app.packageDir, path)
        return file.takeIf { it.isFile && it.canonicalPath.startsWith(app.packageDir.canonicalPath + File.separator) }
    }

    fun readBackground(app: InstalledMiniapp): String =
        payloadFile(app, app.manifest.backgroundEntry)?.readText() ?: throw IOException("The background script is missing")

    private fun load(dir: File): InstalledMiniapp? {
        val packageDir = File(dir, PACKAGE)
        val manifest = runCatching { MiniappManifest.parse(File(packageDir, MiniappManifest.FILE_NAME).readText()) }
            .onFailure { Log.w(TAG, "Skipping ${dir.name}: ${it.message}") }
            .getOrNull() ?: return null
        if (keyOf(manifest.packageName) != dir.name) return null
        val source = (readJson(File(dir, SOURCE)) as? JsonObject)?.let { obj ->
            val url = (obj["folderUrl"] as? JsonPrimitive)?.content ?: return@let null
            MiniappSourceRecord(
                folderUrl = url,
                etag = (obj["etag"] as? JsonPrimitive)?.takeIf { it.isString }?.content,
                bundleSha256 = (obj["bundleSha256"] as? JsonPrimitive)?.content.orEmpty(),
                signed = (obj["signed"] as? JsonPrimitive)?.content == "true",
                lastCheckedAtMs = (obj["lastCheckedAtMs"] as? JsonPrimitive)?.longOrNull ?: 0L,
            )
        }
        return InstalledMiniapp(dir.name, manifest, packageDir, File(dir, DATA).apply { mkdirs() }, source)
    }

    private fun MiniappSourceRecord.toJson() = buildJsonObject {
        put("folderUrl", folderUrl)
        etag?.let { put("etag", it) }
        put("bundleSha256", bundleSha256)
        put("signed", signed)
        put("lastCheckedAtMs", lastCheckedAtMs)
    }.toString()

    private fun readJson(file: File) = runCatching { json.parseToJsonElement(file.readText()) }.getOrNull()

    private fun writeAtomically(file: File, content: String) {
        runCatching {
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, "${file.name}.tmp")
            temp.writeText(content)
            if (!temp.renameTo(file)) throw IOException("rename failed")
        }.onFailure { Log.w(TAG, "Could not write ${file.name}: ${it.message}") }
    }

    companion object {
        private const val TAG = "MentraMiniapps"
        private const val PACKAGE = "package"
        private const val DATA = "data"
        private const val SOURCE = "source.json"
        private const val APPROVALS = "approvals.json"
        const val STORAGE = "storage.json"
        const val LOCAL_STORAGE = "local-storage.json"
        private val KEY = Regex("^[0-9a-f]{32}$")

        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        private val writer = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

        /** A stable directory and origin label for a package name (hex, so it is a valid host label). */
        fun keyOf(packageName: String): String = Sha256.hex(packageName.encodeToByteArray()).take(32)
    }
}
