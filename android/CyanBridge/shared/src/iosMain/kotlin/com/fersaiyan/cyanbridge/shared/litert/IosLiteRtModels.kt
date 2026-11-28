package com.fersaiyan.cyanbridge.shared.litert

import com.fersaiyan.cyanbridge.shared.platform.PlatformLogger
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.CoreCrypto.CC_SHA256
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSData
import platform.Foundation.NSDataReadingMappedIfSafe
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSize
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSNumber
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.Foundation.NSURLSession
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.downloadTaskWithURL
import platform.Foundation.writeToFile
import platform.posix.memcpy
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The LiteRT runtime for iOS, implemented in Swift (`LiteRtModelBridgeImpl` in the host app, over LiteRT-Swift 2.4.0
 * in `ios/LiteRtModels`) and registered in [LiteRtModelRegistry] at launch. Kotlin owns which files, where they live
 * and what to do with the outputs; Swift only opens and runs models. Tensors cross as float32 bytes in one copy.
 */
interface LiteRtModelBridge {
    fun gpuAvailable(): Boolean

    /**
     * Opens the .tflite at [path] on "gpu" (Metal) or "cpu". [gpuPrecision] is [LiteRtGpuPrecision.code] or -1;
     * [cacheDir] enables the GPU program cache. A GPU open that fails falls back to the CPU. Returns a handle > 0,
     * or 0 when even the CPU open failed (see [lastError]).
     */
    fun open(path: String, accelerator: String, gpuPrecision: Int, cacheDir: String?): Long

    /** "gpu" or "cpu": where [handle] really runs. */
    fun acceleratorOf(handle: Long): String

    /** One inference: float32 bytes of input 0 -> float32 bytes per output, or null on failure (see [lastError]). */
    fun run(handle: Long, input: NSData): List<NSData>?

    fun close(handle: Long)

    /** The last open/run error message of this bridge, or null. */
    fun lastError(): String?
}

object LiteRtModelRegistry {
    var bridge: LiteRtModelBridge? = null
}

/** [LiteRtModelRunner] over the Swift [LiteRtModelBridge]. */
@OptIn(ExperimentalForeignApi::class)
class IosLiteRtModelRunner(private val bridge: LiteRtModelBridge) : LiteRtModelRunner {
    override val gpuAvailable: Boolean get() = bridge.gpuAvailable()

    override fun open(path: String, accelerator: LiteRtAccelerator, precision: LiteRtGpuPrecision?, cacheDir: String?): LiteRtSession {
        val wanted = if (accelerator == LiteRtAccelerator.GPU) "gpu" else "cpu"
        val handle = bridge.open(path, wanted, precision?.code ?: -1, cacheDir)
        check(handle > 0) { bridge.lastError() ?: "LiteRT could not open $path" }
        val used = if (bridge.acceleratorOf(handle) == "gpu") LiteRtAccelerator.GPU else LiteRtAccelerator.CPU
        return object : LiteRtSession {
            override val accelerator: LiteRtAccelerator = used

            override fun run(input: FloatArray): List<FloatArray> {
                val outputs = bridge.run(handle, input.toFloatNSData()) ?: error(bridge.lastError() ?: "LiteRT run failed")
                return outputs.map { it.toFloatArray() }
            }

            override fun close() = bridge.close(handle)
        }
    }
}

/** Downloads catalog files into Application Support/litert-models (kept out of iCloud backups), verified by size + SHA-256. */
@OptIn(ExperimentalForeignApi::class, ExperimentalUnsignedTypes::class)
class IosLiteRtModelInstaller(private val directory: String = defaultDirectory()) : LiteRtModelInstaller {
    override fun installedPath(file: LiteRtModelFile): String? = pathOf(file).takeIf { sizeOf(it) == file.bytes }

    override suspend fun install(file: LiteRtModelFile): String {
        installedPath(file)?.let { return it }
        val destination = pathOf(file)
        val url = NSURL.URLWithString(file.url) ?: error("bad URL ${file.url}")
        return suspendCancellableCoroutine { continuation ->
            val task = NSURLSession.sharedSession.downloadTaskWithURL(url) { location, response, error ->
                // The temporary file disappears when this handler returns: verify and move it here.
                val result = runCatching {
                    if (error != null) error("download of ${file.path} failed: ${error.localizedDescription}")
                    val status = (response as? NSHTTPURLResponse)?.statusCode?.toInt() ?: 0
                    val temp = location?.path
                    check(temp != null && status in 200..299) { "download of ${file.path} failed (HTTP $status)" }
                    val size = sizeOf(temp)
                    check(size == file.bytes) { "${file.path}: got $size bytes, expected ${file.bytes}" }
                    val digest = sha256Hex(temp)
                    check(digest == file.sha256) { "${file.path}: SHA-256 $digest does not match the catalog" }
                    val manager = NSFileManager.defaultManager
                    manager.removeItemAtPath(destination, null)
                    check(manager.moveItemAtPath(temp, destination, null)) { "could not save ${file.localName}" }
                    destination
                }
                result.fold({ continuation.resume(it) }, { continuation.resumeWithException(it) })
            }
            continuation.invokeOnCancellation { task.cancel() }
            task.resume()
        }
    }

    override fun programCacheDir(): String {
        val base = NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, true).first() as String
        val path = "$base/litert-gpu-cache"
        NSFileManager.defaultManager.createDirectoryAtPath(path, true, null, null)
        return path
    }

    private fun pathOf(file: LiteRtModelFile) = "$directory/${file.localName}"

    companion object {
        fun defaultDirectory(): String {
            val base = NSSearchPathForDirectoriesInDomains(NSApplicationSupportDirectory, NSUserDomainMask, true).first() as String
            val path = "$base/litert-models"
            NSFileManager.defaultManager.createDirectoryAtPath(path, true, null, null)
            // Re-downloadable: keep them out of iCloud backups.
            NSURL.fileURLWithPath(path).setResourceValue(true, NSURLIsExcludedFromBackupKey, null)
            return path
        }

        internal fun sizeOf(path: String): Long =
            (NSFileManager.defaultManager.attributesOfItemAtPath(path, null)?.get(NSFileSize) as? NSNumber)?.longLongValue ?: -1L

        /** SHA-256 of a file, memory-mapped (the model files are at most a few tens of MB). */
        internal fun sha256Hex(path: String): String {
            val data = NSData.dataWithContentsOfFile(path, NSDataReadingMappedIfSafe, null) ?: error("cannot read $path")
            val digest = UByteArray(CC_SHA256_DIGEST_LENGTH)
            digest.usePinned { out -> CC_SHA256(data.bytes, data.length.convert(), out.addressOf(0)) }
            return digest.joinToString("") { it.toString(16).padStart(2, '0') }
        }
    }
}

/** Entry points for the Swift host: launch-time self-test and the shared installer/runner. */
object IosLiteRtModels {
    private const val TAG = "LiteRtModels"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val installer: IosLiteRtModelInstaller by lazy { IosLiteRtModelInstaller() }

    /** The runner over the registered Swift bridge, or null when the host registered none. */
    fun runner(): LiteRtModelRunner? = LiteRtModelRegistry.bridge?.let(::IosLiteRtModelRunner)

    /**
     * Downloads every iOS file of [LiteRtModelCatalog] and runs each model once on the CPU and on its hinted
     * accelerator ([LiteRtSelfTest]). Logs each line, writes the report to Documents/litert-selftest.txt and passes
     * it to [completion] (called on a background thread). The report's first line is "PASS" or "FAIL".
     */
    fun runSelfTest(completion: (String) -> Unit) {
        scope.launch {
            val report = runCatching {
                val runner = runner() ?: error("the iOS host did not register LiteRtModelBridge")
                PlatformLogger.i(TAG, "Self-test: catalog @ ${LiteRtModelCatalog.REVISION.take(12)}, gpu=${runner.gpuAvailable}")
                val rows = LiteRtSelfTest(installer, runner, log = { PlatformLogger.i(TAG, it) }).run()
                val verdict = if (rows.all { it.ok }) "PASS" else "FAIL"
                (listOf("$verdict litert self-test, catalog ${LiteRtModelCatalog.REVISION}, gpu=${runner.gpuAvailable}") +
                    rows.map { it.summary() }).joinToString("\n")
            }.getOrElse { "FAIL litert self-test: ${it.message ?: it}" }
            PlatformLogger.i(TAG, report.lineSequence().first())
            writeReport(report)
            completion(report)
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun writeReport(text: String) {
        val documents = NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true).first() as String
        @Suppress("CAST_NEVER_SUCCEEDS")
        (text as NSString).writeToFile("$documents/litert-selftest.txt", true, NSUTF8StringEncoding, null)
    }
}

@OptIn(ExperimentalForeignApi::class)
internal fun FloatArray.toFloatNSData(): NSData {
    if (isEmpty()) return NSData()
    return usePinned { pinned -> NSData.create(bytes = pinned.addressOf(0), length = (size * 4).toULong()) }
}

@OptIn(ExperimentalForeignApi::class)
internal fun NSData.toFloatArray(): FloatArray {
    val count = (length.toLong() / 4).toInt()
    val out = FloatArray(count)
    if (count > 0) out.usePinned { pinned -> memcpy(pinned.addressOf(0), bytes, (count * 4).toULong()) }
    return out
}
