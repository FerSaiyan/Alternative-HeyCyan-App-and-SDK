package com.fersaiyan.cyanbridge.shared.plugins.mentra

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** One entry of the Mentra miniapp directory (`GET /api/directory/search`). [url] is the miniapp folder. */
data class MiniappStoreListing(
    val url: String,
    val packageName: String,
    val name: String,
    val description: String?,
    val iconUrl: String?,
    val ageRating: String?,
    val category: String?,
)

/** One page of directory results; [nextCursor] is null on the last page. */
data class MiniappStorePage(val apps: List<MiniappStoreListing>, val nextCursor: String?)

/**
 * The public miniapp directory of apps.mentraglass.com (the store's own web client calls it).
 * Anonymous, cursor-paginated: `GET /api/directory/search?limit=50[&q=…][&cursor=…]` answers
 * `{miniapps:[{url, packageName, name, description, iconUrl, ageRating, category}], nextCursor}`.
 * Each `url` is a miniapp folder that the URL installer handles directly (see
 * docs/refactor/MENTRA_MINIAPPS.md §3). The older `/api/store/apps` feed is gone (404).
 */
object MiniappStoreCatalog {
    const val API_BASE = "https://api.apps.mentraglass.com"
    const val WEB_BASE = "https://apps.mentraglass.com"
    const val PAGE_SIZE = 50
    /** The store's web client follows at most this many pages. */
    const val MAX_PAGES = 100
    private const val MAX_QUERY_LENGTH = 200
    private const val MAX_CURSOR_LENGTH = 512
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Mentra's own miniapps, served from its public first-party host. Shown as a separate
     * "Mentra first-party apps" section while the directory lists nothing (it was empty on
     * 2026-11-17); each installs through the same URL installer as any other miniapp.
     */
    const val FIRST_PARTY_HOST = "https://miniapps.mentraglass.com/"
    val FIRST_PARTY_APPS: List<Pair<String, String>> = listOf(
        "com.mentra.captions" to "Captions",
        "com.mentra.livestreamer" to "Livestreamer",
        "com.mentra.ai" to "AI",
        "com.mentra.call" to "Call",
        "com.mentra.navigation" to "Navigation",
        "com.mentra.notes" to "Notes",
        "com.mentra.merge" to "Merge",
        "com.mentra.recorder" to "Recorder",
        "com.mentra.teleprompter" to "Teleprompter",
        "com.mentra.translation" to "Translation",
    )

    fun firstPartyListings(): List<MiniappStoreListing> = FIRST_PARTY_APPS.map { (packageName, name) ->
        MiniappStoreListing(
            url = "$FIRST_PARTY_HOST$packageName/",
            packageName = packageName,
            name = name,
            description = null,
            iconUrl = null,
            ageRating = null,
            category = null,
        )
    }

    fun searchUrl(query: String = "", cursor: String? = null): String {
        val q = query.trim().take(MAX_QUERY_LENGTH).takeIf { it.isNotEmpty() }?.let { "&q=" + percentEncode(it) }.orEmpty()
        val c = cursor?.takeIf { it.isNotEmpty() }?.let { "&cursor=" + percentEncode(it) }.orEmpty()
        return "$API_BASE/api/directory/search?limit=$PAGE_SIZE$q$c"
    }

    /** The public web page of a listing, for "open in browser". */
    fun listingWebUrl(packageName: String) = "$WEB_BASE/package/$packageName"

    /** Parses one directory page; entries with an invalid package name or a non-HTTPS URL are dropped. */
    fun parsePage(text: String): MiniappStorePage {
        val root = runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonObject
            ?: throw MiniappException("The store answered with something other than a catalog")
        val apps = (root["miniapps"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.let(::listing) }.orEmpty()
        val cursor = root.string("nextCursor")?.takeIf { it.length <= MAX_CURSOR_LENGTH }
        return MiniappStorePage(apps = apps, nextCursor = cursor)
    }

    private fun listing(obj: JsonObject): MiniappStoreListing? {
        val packageName = obj.string("packageName")?.takeIf { MiniappManifest.PACKAGE_NAME.matches(it) } ?: return null
        val url = obj.string("url")?.takeIf(::isPublicHttps) ?: return null
        return MiniappStoreListing(
            url = url,
            packageName = packageName,
            name = obj.string("name")?.take(80) ?: packageName,
            description = obj.string("description")?.take(4_000),
            iconUrl = obj.string("iconUrl")?.takeIf(::isPublicHttps),
            ageRating = obj.string("ageRating")?.take(20),
            category = obj.string("category")?.take(40),
        )
    }

    /** HTTPS, bounded, no whitespace and no userinfo in the authority. */
    private fun isPublicHttps(url: String): Boolean {
        if (!url.startsWith("https://") || url.length > 2_048 || url.any { it.isWhitespace() }) return false
        val authority = url.substring("https://".length).substringBefore('/').substringBefore('?').substringBefore('#')
        return authority.isNotEmpty() && '@' !in authority
    }

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
