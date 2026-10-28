package com.fersaiyan.cyanbridge.shared.plugins.mentra

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/** One listing of the Mentra Miniapp Store (`GET /api/store/apps`), as far as CyanBridge shows it. */
data class MiniappStoreListing(
    val packageName: String,
    val name: String,
    val subtitle: String?,
    val description: String,
    val categories: List<String>,
    val iconUrl: String?,
    /** `verified` or `community` (the store's review tier). */
    val reviewTier: String?,
    val version: String?,
    val bundleUrl: String?,
    val bundleSha256: String?,
    val manifestSha256: String?,
    val installable: Boolean,
    val permissions: List<MiniappPermissionType>,
    val hardware: List<MiniappHardwareRequirement>,
)

data class MiniappStorePage(val apps: List<MiniappStoreListing>, val page: Int, val hasMore: Boolean)

/**
 * The public catalog of apps.mentraglass.com. Browsing (`/api/store/apps?limit&page&q`) and the
 * artwork are anonymous; bundle downloads answer 401 without a Mentra store credential, so
 * CyanBridge lists the store and installs from a developer's HTTPS URL instead (see
 * docs/refactor/MENTRA_MINIAPPS.md).
 */
object MiniappStoreCatalog {
    const val API_BASE = "https://api.apps.mentraglass.com"
    const val WEB_BASE = "https://apps.mentraglass.com"
    const val PAGE_SIZE = 24
    private val json = Json { ignoreUnknownKeys = true }
    private val SHA256_HEX = Regex("^[0-9a-f]{64}$")

    fun pageUrl(page: Int, query: String = ""): String {
        val q = query.trim().takeIf { it.isNotEmpty() }?.let { "&q=" + percentEncode(it) }.orEmpty()
        return "$API_BASE/api/store/apps?limit=$PAGE_SIZE&page=$page$q"
    }

    /** The public web page of a listing, for "open in browser". */
    fun listingWebUrl(packageName: String) = "$WEB_BASE/app/$packageName"

    /** Parses one catalog page; listings with an invalid package name or icon URL are dropped. */
    fun parsePage(text: String): MiniappStorePage {
        val root = runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonObject
            ?: throw MiniappException("The store answered with something other than a catalog")
        val apps = (root["apps"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.let(::listing) }.orEmpty()
        return MiniappStorePage(
            apps = apps,
            page = (root["page"] as? JsonPrimitive)?.intOrNull ?: 1,
            hasMore = (root["hasMore"] as? JsonPrimitive)?.booleanOrNull ?: false,
        )
    }

    private fun listing(obj: JsonObject): MiniappStoreListing? {
        val packageName = obj.string("packageName")?.takeIf { MiniappManifest.PACKAGE_NAME.matches(it) } ?: return null
        val release = obj["release"] as? JsonObject
        return MiniappStoreListing(
            packageName = packageName,
            name = obj.string("name")?.take(80) ?: packageName,
            subtitle = obj.string("subtitle")?.take(200),
            description = obj.string("description")?.take(4_000).orEmpty(),
            categories = (obj["categories"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.take(40) }
                ?.take(8).orEmpty(),
            iconUrl = obj.string("iconUrl")?.takeIf(::isHttps),
            reviewTier = obj.string("reviewTier"),
            version = release?.string("version"),
            bundleUrl = release?.string("bundleUrl")?.takeIf(::isHttps),
            bundleSha256 = release?.string("bundleSha256")?.lowercase()?.takeIf(SHA256_HEX::matches),
            manifestSha256 = release?.string("manifestSha256")?.lowercase()?.takeIf(SHA256_HEX::matches),
            installable = (release?.get("installable") as? JsonPrimitive)?.booleanOrNull == true,
            permissions = (release?.get("permissions") as? JsonArray)?.mapNotNull { element ->
                val type = (element as? JsonObject)?.string("type")
                MiniappPermissionType.entries.firstOrNull { it.name == type }
            }.orEmpty(),
            hardware = (release?.get("hardwareRequirements") as? JsonArray)?.mapNotNull { element ->
                val entry = element as? JsonObject ?: return@mapNotNull null
                val type = MiniappHardware.entries.firstOrNull { it.name == entry.string("type") } ?: return@mapNotNull null
                MiniappHardwareRequirement(type, entry.string("level") == "REQUIRED", entry.string("description")?.take(300))
            }.orEmpty(),
        )
    }

    private fun isHttps(url: String) = url.startsWith("https://") && url.length <= 2_048 && url.none { it.isWhitespace() }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }

    private fun percentEncode(value: String): String = buildString {
        value.encodeToByteArray().forEach { byte ->
            val c = byte.toInt() and 0xFF
            if (c.toChar().isLetterOrDigit() && c < 0x80 || c.toChar() in "-._~") append(c.toChar())
            else append('%').append(c.toString(16).uppercase().padStart(2, '0'))
        }
    }
}
