package com.fersaiyan.cyanbridge.media.sync

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import com.fersaiyan.cyanbridge.shared.media.OggOpusWrapper
import com.fersaiyan.cyanbridge.media.SyncedMediaFolder
import com.fersaiyan.cyanbridge.media.autocapture.GlassesSyncedAudioIngestor
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale

internal enum class VendorMediaType(val progressLabel: String) {
    PHOTO("photo"),
    VIDEO("video"),
    AUDIO("audio"),
}

internal data class VendorMediaItem(
    val fileName: String,
    val type: VendorMediaType,
    /** Capture time reported by the glasses; when null it is read from the file name. */
    val takenTimeMs: Long? = null,
)

internal data class GallerySaveResult(
    val success: Boolean,
    val uri: String?,
    val bytes: Long,
)

/** Saves media synced from the glasses into the shared gallery and audio library. */
internal class GlassesMediaLibrary(private val context: Context) {

    suspend fun importVendorMediaFile(file: File, item: VendorMediaItem): Boolean {
        val takenMs = item.takenTimeMs
            ?: parseTakenTimeMillisFromFilename(item.fileName)
            ?: System.currentTimeMillis()
        return when (item.type) {
            VendorMediaType.PHOTO -> file.inputStream().use { input ->
                saveJpegToGallery(input, item.fileName, takenMs).success
            }

            VendorMediaType.VIDEO -> file.inputStream().use { input ->
                saveMp4ToGallery(
                    input = input,
                    displayName = item.fileName,
                    takenTimeMs = takenMs,
                    contentLength = file.length(),
                ).success
            }

            VendorMediaType.AUDIO -> {
                val rawBytes = runCatching { file.readBytes() }.getOrNull() ?: return false
                // Eyevue records standard WAV files: store them as-is. Only Opus
                // payloads go through the Ogg wrapper; wrapping WAV bytes as Opus
                // produces garbage and must never happen.
                if (item.fileName.endsWith(".wav", ignoreCase = true)) {
                    val saved = saveOpusToLibrary(
                        payloadBytes = rawBytes,
                        rawBytesSize = rawBytes.size,
                        payloadNote = "wav-passthrough",
                        displayName = item.fileName,
                        takenTimeMs = takenMs,
                        mimeType = "audio/wav",
                    )
                    if (saved.success) {
                        runCatching {
                            GlassesSyncedAudioIngestor.persistDownloadedAudio(
                                context = context.applicationContext,
                                displayName = item.fileName,
                                payloadBytes = rawBytes,
                                takenTimeMs = takenMs,
                            )
                        }.onFailure {
                            Log.e("DataDownload", "Failed to persist synced audio session for ${item.fileName}", it)
                        }
                    }
                    return saved.success
                }
                val wrapped = OggOpusWrapper.wrapOpusIfNeeded(rawBytes)
                val saved = saveOpusToLibrary(
                    payloadBytes = wrapped.first,
                    rawBytesSize = rawBytes.size,
                    payloadNote = wrapped.second,
                    displayName = item.fileName,
                    takenTimeMs = takenMs,
                )
                if (saved.success) {
                    runCatching {
                        GlassesSyncedAudioIngestor.persistDownloadedAudio(
                            context = context.applicationContext,
                            displayName = item.fileName,
                            payloadBytes = wrapped.first,
                            takenTimeMs = takenMs,
                        )
                    }.onFailure {
                        Log.e("DataDownload", "Failed to persist synced audio session for ${item.fileName}", it)
                    }
                }
                saved.success
            }
        }
    }

    /**
     * Photos and videos this app saved whose display name starts with [namePrefix], keyed by
     * lower-case display name, with their size (null when MediaStore does not know it).
     */
    fun syncedMediaSizes(namePrefix: String): Map<String, Long?> {
        val sizes = mutableMapOf<String, Long?>()
        val projection = arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE)
        val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?"
        val args = arrayOf("$namePrefix%")
        listOf(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, MediaStore.Video.Media.EXTERNAL_CONTENT_URI).forEach { uri ->
            runCatching {
                context.contentResolver.query(uri, projection, selection, args, null)?.use { cursor ->
                    val nameIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                    val sizeIdx = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
                    while (cursor.moveToNext()) {
                        if (nameIdx < 0 || cursor.isNull(nameIdx)) continue
                        val size = if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) cursor.getLong(sizeIdx) else null
                        sizes[cursor.getString(nameIdx).lowercase(Locale.ROOT)] = size
                    }
                }
            }.onFailure { Log.w("DataDownload", "Could not list synced media named $namePrefix*: ${it.message}") }
        }
        return sizes
    }

    fun saveJpegToGallery(
        input: InputStream,
        displayName: String,
        takenTimeMs: Long,
        contentLength: Long = -1L,
    ): GallerySaveResult {
        return try {
            val resolver = context.contentResolver

            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.DATE_TAKEN, takenTimeMs)
                put(MediaStore.Images.Media.DATE_ADDED, takenTimeMs / 1000)
                put(MediaStore.Images.Media.DATE_MODIFIED, takenTimeMs / 1000)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, SyncedMediaFolder.relativePath)
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }

            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: return GallerySaveResult(false, null, 0)

            var bytes = 0L
            try {
                resolver.openOutputStream(uri, "w")?.use { out ->
                    val buffer = ByteArray(8 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        out.write(buffer, 0, read)
                        bytes += read
                    }
                    out.flush()
                } ?: run {
                    resolver.delete(uri, null, null)
                    return GallerySaveResult(false, null, bytes)
                }

                if (bytes <= 0L || (contentLength >= 0L && bytes != contentLength)) {
                    resolver.delete(uri, null, null)
                    Log.w(
                        "DataDownload",
                        "Rejecting incomplete JPEG $displayName: bytes=$bytes expected=$contentLength",
                    )
                    return GallerySaveResult(false, uri.toString(), bytes)
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val done = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
                    resolver.update(uri, done, null, null)
                } else {
                    // Pre-Android 10: some galleries need an explicit media scan.
                    MediaScannerConnection.scanFile(
                        context,
                        arrayOf(uri.toString()),
                        arrayOf("image/jpeg"),
                        null
                    )
                }

                GallerySaveResult(true, uri.toString(), bytes)
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                Log.e("DataDownload", "Gallery write failed for $displayName: ${e.message}", e)
                GallerySaveResult(false, uri.toString(), bytes)
            }
        } catch (e: Exception) {
            Log.e("DataDownload", "saveJpegToGallery failed for $displayName: ${e.message}", e)
            GallerySaveResult(false, null, 0)
        }
    }

    fun saveMp4ToGallery(
        input: InputStream,
        displayName: String,
        takenTimeMs: Long,
        contentLength: Long,
        onBytesCopied: ((Long, Long) -> Unit)? = null,
    ): GallerySaveResult {
        return try {
            val resolver = context.contentResolver

            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.DATE_TAKEN, takenTimeMs)
                put(MediaStore.Video.Media.DATE_ADDED, takenTimeMs / 1000)
                put(MediaStore.Video.Media.DATE_MODIFIED, takenTimeMs / 1000)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    // Keep videos in the same DCIM/CyanBridge folder as photos.
                    put(MediaStore.Video.Media.RELATIVE_PATH, SyncedMediaFolder.relativePath)
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
            }

            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: return GallerySaveResult(false, null, 0)

            var bytes = 0L
            try {
                resolver.openOutputStream(uri, "w")?.use { out ->
                    val buffer = ByteArray(128 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        out.write(buffer, 0, read)
                        bytes += read
                        onBytesCopied?.invoke(bytes, contentLength)
                    }
                    out.flush()
                } ?: run {
                    resolver.delete(uri, null, null)
                    return GallerySaveResult(false, null, bytes)
                }

                if (bytes <= 0L || (contentLength >= 0L && bytes != contentLength)) {
                    resolver.delete(uri, null, null)
                    Log.w(
                        "DataDownload",
                        "Rejecting incomplete MP4 $displayName: bytes=$bytes expected=$contentLength",
                    )
                    return GallerySaveResult(false, uri.toString(), bytes)
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val done = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
                    resolver.update(uri, done, null, null)
                }

                GallerySaveResult(true, uri.toString(), bytes)
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                Log.e("DataDownload", "Gallery video write failed for $displayName: ${e.message}", e)
                GallerySaveResult(false, uri.toString(), bytes)
            }
        } catch (e: Exception) {
            Log.e("DataDownload", "saveMp4ToGallery failed for $displayName: ${e.message}", e)
            GallerySaveResult(false, null, 0)
        }
    }

    fun saveOpusToLibrary(
        payloadBytes: ByteArray,
        rawBytesSize: Int,
        payloadNote: String,
        displayName: String,
        takenTimeMs: Long,
        mimeType: String = "audio/ogg",
    ): GallerySaveResult {
        return try {
            val resolver = context.contentResolver

            val headHex = bytesToHex(payloadBytes, 24)
            Log.i(
                "DataDownload",
                "OPUS save: name=$displayName, raw=$rawBytesSize bytes, out=${payloadBytes.size} bytes, mode=$payloadNote, head=$headHex"
            )

            val title = displayName.substringBeforeLast('.', displayName)
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Audio.Media.MIME_TYPE, mimeType)
                put(MediaStore.Audio.Media.TITLE, title)
                put(MediaStore.Audio.Media.IS_MUSIC, 0)
                put(MediaStore.MediaColumns.DATE_ADDED, takenTimeMs / 1000)
                put(MediaStore.MediaColumns.DATE_MODIFIED, takenTimeMs / 1000)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    // Audio collections reject DCIM; recordings go under Music/CyanBridge.
                    put(MediaStore.MediaColumns.RELATIVE_PATH, SyncedMediaFolder.relativeAudioPath)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            }

            val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
                ?: return GallerySaveResult(false, null, 0)

            var bytes = 0L
            try {
                resolver.openOutputStream(uri, "w")?.use { out ->
                    out.write(payloadBytes)
                    bytes = payloadBytes.size.toLong()
                    out.flush()
                } ?: run {
                    resolver.delete(uri, null, null)
                    return GallerySaveResult(false, null, bytes)
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                    resolver.update(uri, done, null, null)
                }

                GallerySaveResult(true, uri.toString(), bytes)
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                Log.e("DataDownload", "Gallery audio write failed for $displayName: ${e.message}", e)
                GallerySaveResult(false, uri.toString(), bytes)
            }
        } catch (e: Exception) {
            Log.e("DataDownload", "saveOpusToLibrary failed for $displayName: ${e.message}", e)
            GallerySaveResult(false, null, 0)
        }
    }

    companion object {
        fun parseTakenTimeMillisFromFilename(fileName: String): Long? {
            // The glasses filenames look like: yyyyMMddHHmmssSSS?.jpg
            // Example: 20260127095159018.jpg
            val digits = fileName.takeWhile { it.isDigit() }
            if (digits.length < 14) return null

            return try {
                val base = digits.substring(0, 14)
                val sdf = SimpleDateFormat("yyyyMMddHHmmss", Locale.US)
                val baseDate = sdf.parse(base) ?: return null
                val msPart = digits.substring(14).take(3)
                val extraMs = msPart.toIntOrNull() ?: 0
                baseDate.time + extraMs
            } catch (_: Exception) {
                null
            }
        }

        fun readAllBytes(input: InputStream): ByteArray {
            val bos = ByteArrayOutputStream()
            val buffer = ByteArray(32 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                bos.write(buffer, 0, read)
            }
            return bos.toByteArray()
        }

        fun bytesToHex(bytes: ByteArray, max: Int): String {
            val n = minOf(bytes.size, max)
            val sb = StringBuilder(n * 2)
            for (i in 0 until n) {
                sb.append(String.format("%02x", bytes[i]))
            }
            if (bytes.size > max) sb.append("...")
            return sb.toString()
        }
    }
}
