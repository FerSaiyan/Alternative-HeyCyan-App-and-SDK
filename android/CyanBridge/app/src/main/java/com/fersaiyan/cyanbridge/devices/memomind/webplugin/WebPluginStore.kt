package com.fersaiyan.cyanbridge.devices.memomind.webplugin

import android.content.Context
import android.net.Uri
import android.util.Log
import android.webkit.WebStorage
import com.fersaiyan.cyanbridge.shared.devices.memomind.webplugin.MmpkgException
import com.fersaiyan.cyanbridge.shared.devices.memomind.webplugin.MmpkgLimits
import com.fersaiyan.cyanbridge.shared.devices.memomind.webplugin.MmpkgManifest
import com.fersaiyan.cyanbridge.shared.devices.memomind.webplugin.MmpkgPackage
import com.fersaiyan.cyanbridge.shared.devices.memomind.webplugin.RawInflater
import com.fersaiyan.cyanbridge.shared.devices.memomind.webplugin.Sha256
import com.fersaiyan.cyanbridge.shared.devices.memomind.webplugin.WebPluginApprovals
import com.fersaiyan.cyanbridge.shared.devices.memomind.webplugin.WebPluginStorage
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
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.Inflater

/** An installed Web plugin; [key] names its private directory and its WebView origin. */
class InstalledWebPlugin(val key: String, val manifest: MmpkgManifest, val packageDir: File, val dataDir: File) {
    val host: String get() = "$key.$ORIGIN_DOMAIN"
    val origin: String get() = "https://$host"
    val entryUrl: String get() = "$origin/${manifest.entry}"

    companion object {
        /** Requests to this domain are answered from the package and never reach the network. */
        const val ORIGIN_DOMAIN = "mmplugin.androidplatform.net"
    }
}

/**
 * Installed MemoMind Web plugins in app-private storage: `files/memomind/webplugins/<key>/`
 * holds `package/` (the verified payload and manifest, replaced atomically on update) and
 * `data/` (the plugin's storage and the user's permission choices). Plugins never get a path
 * into this tree: the runtime serves only the files listed in the manifest.
 */
class WebPluginStore(context: Context) {
    private val root = File(context.applicationContext.filesDir, "memomind/webplugins")
    private val json = Json { ignoreUnknownKeys = true }

    fun list(): List<InstalledWebPlugin> =
        root.listFiles()?.filter { it.isDirectory && KEY.matches(it.name) }?.mapNotNull(::load)
            ?.sortedBy { it.manifest.name.lowercase() }.orEmpty()

    fun find(key: String): InstalledWebPlugin? = File(root, key).takeIf { KEY.matches(key) && it.isDirectory }?.let(::load)

    /** Reads and verifies a picked `.mmpkg` (size-capped before verification). */
    fun read(context: Context, uri: Uri): MmpkgPackage {
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                out.write(buffer, 0, read)
                if (out.size() > MmpkgLimits.MAX_PACKAGE_BYTES) throw MmpkgException("The package is larger than 10 MB")
            }
            out.toByteArray()
        } ?: throw IOException("Cannot open $uri")
        return MmpkgPackage.read(bytes, JavaRawInflater)
    }

    /** The installed plugin with the same ID, if any. */
    fun installedVersionOf(pkg: MmpkgPackage): InstalledWebPlugin? = find(keyOf(pkg.manifest.id))

    /** Installs or replaces the plugin; its data directory is kept. */
    fun install(pkg: MmpkgPackage): InstalledWebPlugin {
        val dir = File(root, keyOf(pkg.manifest.id)).apply { mkdirs() }
        val staging = File(dir, "package.tmp-${System.nanoTime()}")
        try {
            pkg.files.forEach { (path, bytes) ->
                val target = File(staging, path)
                check(target.canonicalPath.startsWith(staging.canonicalPath + File.separator)) { "unsafe path $path" }
                target.parentFile?.mkdirs()
                target.writeBytes(bytes)
            }
            File(staging, MmpkgManifest.MANIFEST).writeBytes(pkg.manifestBytes)
            val current = File(dir, PACKAGE)
            val previous = File(dir, "package.old-${System.nanoTime()}")
            if (current.exists() && !current.renameTo(previous)) throw IOException("Cannot replace the installed package")
            if (!staging.renameTo(current)) {
                previous.renameTo(current)
                throw IOException("Cannot install the package")
            }
            previous.deleteRecursively()
        } finally {
            staging.deleteRecursively()
        }
        File(dir, DATA).mkdirs()
        return load(dir) ?: throw IOException("The installed package cannot be read")
    }

    fun remove(plugin: InstalledWebPlugin) {
        File(root, plugin.key).deleteRecursively()
        // Web storage (localStorage, IndexedDB) of the plugin's own origin.
        runCatching { WebStorage.getInstance().deleteOrigin(plugin.origin) }
    }

    /** The user's choices, and the permission names they decided on (granted or declined). */
    fun approvals(plugin: InstalledWebPlugin): Pair<WebPluginApprovals, Set<String>> {
        val stored = readJson(File(plugin.dataDir, APPROVALS)) as? JsonObject ?: return WebPluginApprovals() to emptySet()
        val decided = (stored["decided"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }?.toSet().orEmpty()
        return WebPluginApprovals.fromJson(stored["granted"], plugin.manifest.permissions) to decided
    }

    fun saveApprovals(plugin: InstalledWebPlugin, approvals: WebPluginApprovals) {
        val content = buildJsonObject {
            put("granted", approvals.toJson())
            put("decided", buildJsonArray { plugin.manifest.permissions.forEach { add(JsonPrimitive(it.name)) } })
        }
        writeAtomically(File(plugin.dataDir, APPROVALS), content.toString())
    }

    /** `storage.*` for [plugin], persisted in its data directory off the main thread. */
    fun storage(plugin: InstalledWebPlugin): WebPluginStorage {
        val file = File(plugin.dataDir, STORAGE)
        val initial = runCatching { file.takeIf { it.isFile }?.readText() }.getOrNull()
        return WebPluginStorage(initial, persist = { content -> writer.launch { writeAtomically(file, content) } })
    }

    /** The package file for [path] when the manifest lists it; null for anything else. */
    fun payloadFile(plugin: InstalledWebPlugin, path: String): File? {
        if (path !in plugin.manifest.files) return null
        val file = File(plugin.packageDir, path)
        return file.takeIf { it.isFile && it.canonicalPath.startsWith(plugin.packageDir.canonicalPath + File.separator) }
    }

    private fun load(dir: File): InstalledWebPlugin? {
        val packageDir = File(dir, PACKAGE)
        val manifest = runCatching { MmpkgManifest.parse(File(packageDir, MmpkgManifest.MANIFEST).readText()) }
            .onFailure { Log.w(TAG, "Skipping ${dir.name}: ${it.message}") }
            .getOrNull() ?: return null
        if (keyOf(manifest.id) != dir.name) return null
        return InstalledWebPlugin(dir.name, manifest, packageDir, File(dir, DATA).apply { mkdirs() })
    }

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
        private const val TAG = "MemoMindWebPlugins"
        private const val PACKAGE = "package"
        private const val DATA = "data"
        private const val APPROVALS = "approvals.json"
        private const val STORAGE = "storage.json"
        private val KEY = Regex("^[0-9a-f]{32}$")

        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        private val writer = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

        /** A stable directory and origin label for a plugin ID (hex, so it is always a valid host label). */
        fun keyOf(id: String): String = Sha256.hex(id.encodeToByteArray()).take(32)
    }
}

/** Raw DEFLATE with [Inflater], refusing output beyond the size the archive declares. */
internal object JavaRawInflater : RawInflater {
    override fun inflate(compressed: ByteArray, expectedSize: Int): ByteArray {
        val inflater = Inflater(true)
        try {
            // nowrap streams may need one trailing byte to signal their end.
            inflater.setInput(compressed + 0.toByte())
            val out = ByteArray(expectedSize)
            var written = 0
            while (written < expectedSize) {
                val count = inflater.inflate(out, written, expectedSize - written)
                if (count == 0 && (inflater.finished() || inflater.needsInput() || inflater.needsDictionary())) break
                written += count
            }
            if (written != expectedSize) throw MmpkgException("Corrupt compressed data")
            if (!inflater.finished() && inflater.inflate(ByteArray(1)) > 0) throw MmpkgException("Compressed data is larger than declared")
            return out
        } finally {
            inflater.end()
        }
    }
}
