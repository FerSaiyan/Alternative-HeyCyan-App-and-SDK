package com.fersaiyan.cyanbridge.shared.devices.mentra

import com.fersaiyan.cyanbridge.shared.json.optJsonArray
import com.fersaiyan.cyanbridge.shared.json.optJsonObject
import com.fersaiyan.cyanbridge.shared.json.optStringOrNull
import com.fersaiyan.cyanbridge.shared.json.parseJsonObject
import com.fersaiyan.cyanbridge.shared.json.toBooleanOrNull
import com.fersaiyan.cyanbridge.shared.json.toIntOrNull
import com.fersaiyan.cyanbridge.shared.json.toLongOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/*
 * Mentra Live gallery download: the glasses' camera web server, reachable only on their own
 * Wi-Fi hotspot (`setHotspotState(true)` reports the SSID, password and the glasses' IP). The
 * protocol follows the official MentraOS app (`asgCameraApi.ts`, `gallerySyncService.ts`):
 *
 * - v3 firmware: `GET /api/v3/capabilities`, paged `GET /api/v3/manifest`, delete by
 *   `POST /api/v3/ack` (moves the capture to recoverable trash on the glasses).
 * - v2 firmware: `GET /api/sync` lists capture groups; `POST /api/delete-files` deletes them.
 * - older firmware: `/api/sync` lists plain files (`changed_files`), or `GET /api/gallery`.
 *
 * Every file downloads from `GET /api/download?file=<name>`. The server has no authentication;
 * the WPA2 hotspot is the only gate. This file holds the platform-free parts: URLs, JSON parsing,
 * file naming and the sync plan.
 */

enum class MentraGalleryProtocol {
    V3_MANIFEST,
    V2_CAPTURES,
    LEGACY_FILES,
}

enum class MentraMediaKind(val extension: String) {
    PHOTO("jpg"),
    VIDEO("mp4"),
}

/**
 * One capture on the glasses. [captureId] names the capture folder (v2/v3) or, for older
 * firmware, the file itself; it is what deletion addresses. [remoteFile] is the file to
 * download. [kind] is null for files the phone gallery cannot store as-is (they are left alone).
 */
data class MentraGalleryItem(
    val captureId: String,
    val remoteFile: String,
    val sizeBytes: Long,
    val kind: MentraMediaKind?,
    val takenAtMs: Long?,
    val etag: String? = null,
)

data class MentraGalleryCapabilities(
    val apiVersion: Int,
    val maxManifestPageSize: Int,
)

data class MentraManifestPage(
    val items: List<MentraGalleryItem>,
    val nextCursor: String?,
)

object MentraGalleryApi {
    /** The glasses camera server port (`AsgCameraApiClient` default). */
    const val PORT = 8089
    const val DEFAULT_PAGE_SIZE = 100

    fun baseUrl(glassesIp: String): String = "http://${glassesIp.trim()}:$PORT"

    fun statusUrl(base: String): String = "$base/api/status"

    fun capabilitiesUrl(base: String): String = "$base/api/v3/capabilities"

    fun manifestUrl(base: String, limit: Int, cursor: String?): String =
        "$base/api/v3/manifest?limit=$limit" + (cursor?.let { "&cursor=${encodeComponent(it)}" } ?: "")

    fun syncUrl(base: String, clientId: String): String =
        "$base/api/sync?client_id=${encodeComponent(clientId)}&include_thumbnails=false"

    fun galleryUrl(base: String): String = "$base/api/gallery"

    fun downloadUrl(base: String, remoteFile: String): String =
        "$base/api/download?file=${encodeComponent(remoteFile)}"

    fun deleteFilesUrl(base: String): String = "$base/api/delete-files"

    fun ackUrl(base: String): String = "$base/api/v3/ack"

    fun deleteFilesBody(names: List<String>): String =
        buildJsonObject { put("files", JsonArray(names.map(::JsonPrimitive))) }.toString()

    fun ackBody(captureId: String, ackId: String): String =
        buildJsonObject {
            put("capture_id", captureId)
            put("ack_id", ackId)
        }.toString()

    /** Same idempotency key for a retry of the same capture generation. */
    fun ackId(item: MentraGalleryItem): String =
        "cyanbridge-${item.captureId}-${item.etag ?: item.sizeBytes.toString()}"

    /** `encodeURIComponent`: UTF-8 percent-encoding of everything but the unreserved set. */
    fun encodeComponent(value: String): String = buildString {
        value.encodeToByteArray().forEach { byte ->
            val c = (byte.toInt() and 0xFF)
            val ch = c.toChar()
            if (c < 0x80 && (ch.isAsciiLetterOrDigit() || ch in "-_.!~*'()")) {
                append(ch)
            } else {
                append('%')
                append(HEX[c shr 4])
                append(HEX[c and 0x0F])
            }
        }
    }

    private const val HEX = "0123456789ABCDEF"
}

object MentraGalleryParser {
    /** `/api/v3/capabilities`; null when the firmware does not speak v3. */
    fun capabilities(json: String): MentraGalleryCapabilities? {
        val data = payload(json)
        val version = data["api_version"].toIntOrNull() ?: return null
        if (version < 3) return null
        val pageSize = data["max_manifest_page_size"].toIntOrNull()?.takeIf { it > 0 }
            ?: MentraGalleryApi.DEFAULT_PAGE_SIZE
        return MentraGalleryCapabilities(version, pageSize)
    }

    /** One `/api/v3/manifest` page. */
    fun manifestPage(json: String): MentraManifestPage {
        val data = payload(json)
        val items = data.optJsonArray("captures").objects().mapNotNull(::captureItem)
        val hasMore = data["has_more"].toBooleanOrNull() == true
        val cursor = data.optStringOrNull("next_cursor")?.takeIf { hasMore && it.isNotBlank() }
        return MentraManifestPage(items, cursor)
    }

    /**
     * `/api/sync` (capture groups from api_version 2, plain files before) or `/api/gallery`
     * (`photos`). Returns the protocol the response used and its items.
     */
    fun listing(json: String): Pair<MentraGalleryProtocol, List<MentraGalleryItem>> {
        val data = payload(json)
        val apiVersion = data["api_version"].toIntOrNull() ?: 0
        val captures = data.optJsonArray("captures").objects()
        if (apiVersion >= 2 && captures.isNotEmpty()) {
            return MentraGalleryProtocol.V2_CAPTURES to captures.mapNotNull(::captureItem)
        }
        val files = (data.optJsonArray("changed_files") ?: data.optJsonArray("photos")).objects()
        return MentraGalleryProtocol.LEGACY_FILES to files.mapNotNull(::legacyItem)
    }

    /** Names `/api/delete-files` reports as deleted. */
    fun deletedFiles(json: String): Set<String> {
        val root = parseJsonObject(json)
        val results = root.optJsonObject("data")?.optJsonArray("results")
        if (results != null) {
            return results.objects()
                .filter { it["success"].toBooleanOrNull() == true }
                .mapNotNull { it.optStringOrNull("file") }
                .toSet()
        }
        // Older servers answer `{deleted: [...], failed: [...]}`.
        return root.optJsonArray("deleted")?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty().toSet()
    }

    /** `/api/v3/ack`: true when the capture is in the glasses' trash (now or already). */
    fun ackAccepted(json: String): Boolean {
        val data = payload(json)
        return data["success"].toBooleanOrNull() == true || data["already_trashed"].toBooleanOrNull() == true
    }

    private fun payload(json: String): JsonObject {
        val root = parseJsonObject(json)
        return root.optJsonObject("data") ?: root
    }

    private fun JsonArray?.objects(): List<JsonObject> = this?.mapNotNull { it as? JsonObject }.orEmpty()

    /** A capture group: its primary file is the one the gallery keeps (HDR brackets are skipped). */
    private fun captureItem(capture: JsonObject): MentraGalleryItem? {
        val captureId = capture.optStringOrNull("capture_id")?.takeIf { it.isNotBlank() } ?: return null
        val files = capture.optJsonArray("files").objects()
        val primary = files.firstOrNull { it.optStringOrNull("role") == "primary" }
            ?: files.firstOrNull { kindOf(it.optStringOrNull("name").orEmpty(), null) != null }
            ?: return null
        val name = primary.optStringOrNull("name")?.takeIf { it.isNotBlank() } ?: return null
        val typeHint = capture.optStringOrNull("type")
        return MentraGalleryItem(
            captureId = captureId,
            remoteFile = name,
            sizeBytes = primary["size"].toLongOrNull() ?: 0L,
            kind = kindOf(name, typeHint),
            takenAtMs = epochMillis(capture["timestamp"].toLongOrNull() ?: primary["modified"].toLongOrNull()),
            etag = primary.optStringOrNull("etag")?.takeIf { it.isNotBlank() },
        )
    }

    private fun legacyItem(file: JsonObject): MentraGalleryItem? {
        val name = file.optStringOrNull("name")?.takeIf { it.isNotBlank() } ?: return null
        val video = file["is_video"].toBooleanOrNull() == true ||
            file.optStringOrNull("mime_type")?.startsWith("video/") == true
        return MentraGalleryItem(
            captureId = name,
            remoteFile = name,
            sizeBytes = file["size"].toLongOrNull() ?: 0L,
            kind = kindOf(name, if (video) "video" else null),
            takenAtMs = epochMillis(file["modified"].toLongOrNull()),
        )
    }

    /**
     * The phone gallery stores JPEG photos and MP4 videos with fixed MIME types, so other
     * formats (AVIF, HEIC, MOV, sidecars) are reported as unsupported instead of mislabelled.
     */
    private fun kindOf(name: String, typeHint: String?): MentraMediaKind? {
        val extension = name.substringAfterLast('/').substringAfterLast('.', "").lowercase()
        val kind = when (extension) {
            "jpg", "jpeg" -> MentraMediaKind.PHOTO
            "mp4" -> MentraMediaKind.VIDEO
            else -> return null
        }
        return when (typeHint?.lowercase()) {
            "photo" -> kind.takeIf { it == MentraMediaKind.PHOTO }
            "video" -> kind.takeIf { it == MentraMediaKind.VIDEO }
            else -> kind
        }
    }

    /** Timestamps arrive in milliseconds; values that only fit seconds are scaled. */
    private fun epochMillis(value: Long?): Long? = when {
        value == null || value <= 0L -> null
        value < 100_000_000_000L -> value * 1000L
        else -> value
    }
}

/** A gallery item and the name it gets in the phone gallery. */
data class MentraPlannedFile(
    val item: MentraGalleryItem,
    val localName: String,
    /** Already in the phone gallery with the same size, so it may be deleted from the glasses. */
    val verifiedOnPhone: Boolean = false,
)

data class MentraGallerySyncPlan(
    val downloads: List<MentraPlannedFile>,
    /** Already in the phone gallery under the same name; not downloaded again. */
    val alreadyOnPhone: List<MentraPlannedFile>,
    /** Formats the phone gallery cannot store as-is, or entries without a size; left on the glasses. */
    val unsupported: List<MentraGalleryItem>,
) {
    val photoCount: Int get() = downloads.count { it.item.kind == MentraMediaKind.PHOTO }
    val videoCount: Int get() = downloads.count { it.item.kind == MentraMediaKind.VIDEO }

    /**
     * What may be deleted from the glasses: files whose download was verified ([verifiedLocalNames])
     * plus files already on the phone with a matching size. Nothing else is ever offered.
     */
    fun deletable(verifiedLocalNames: Set<String>): List<MentraGalleryItem> =
        (downloads.filter { it.localName in verifiedLocalNames } + alreadyOnPhone.filter { it.verifiedOnPhone })
            .map { it.item }
}

object MentraGallerySyncPlanner {
    const val LOCAL_PREFIX = "MentraLive_"

    /**
     * Stable phone-gallery name for [item], so a later sync recognizes it: the capture folder
     * (or file) name, reduced to `[A-Za-z0-9_-]`, with the media extension.
     */
    fun localFileName(item: MentraGalleryItem): String {
        val kind = item.kind ?: MentraMediaKind.PHOTO
        val leaf = item.captureId.trimEnd('/').substringAfterLast('/')
        val stem = leaf.substringBeforeLast('.').takeIf { leaf.contains('.') } ?: leaf
        val safe = stem.map { if (it.isAsciiLetterOrDigit() || it == '_' || it == '-') it else '_' }
            .joinToString("")
            .take(96)
            .ifBlank { "capture" }
        return "$LOCAL_PREFIX$safe.${kind.extension}"
    }

    /**
     * Splits a listing into downloads, items already in the phone gallery and unsupported
     * ones. [phoneGallery] maps lower-case display names (with [LOCAL_PREFIX]) to their size,
     * null when unknown. Duplicate listing entries collapse to the first.
     */
    fun plan(items: List<MentraGalleryItem>, phoneGallery: Map<String, Long?>): MentraGallerySyncPlan {
        val downloads = mutableListOf<MentraPlannedFile>()
        val present = mutableListOf<MentraPlannedFile>()
        val unsupported = mutableListOf<MentraGalleryItem>()
        val seen = mutableSetOf<String>()
        for (item in items) {
            if (item.kind == null || item.sizeBytes <= 0L) {
                unsupported += item
                continue
            }
            val name = localFileName(item)
            if (!seen.add(name.lowercase())) continue
            val key = name.lowercase()
            if (phoneGallery.containsKey(key)) {
                present += MentraPlannedFile(item, name, verifiedOnPhone = phoneGallery[key] == item.sizeBytes)
            } else {
                downloads += MentraPlannedFile(item, name)
            }
        }
        return MentraGallerySyncPlan(downloads, present, unsupported)
    }

    /** A download is complete only when it delivered exactly the size the glasses listed. */
    fun isCompleteDownload(expectedBytes: Long, receivedBytes: Long): Boolean =
        expectedBytes > 0L && receivedBytes == expectedBytes
}

private fun Char.isAsciiLetterOrDigit(): Boolean = this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'
