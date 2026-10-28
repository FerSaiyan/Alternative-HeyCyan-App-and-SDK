package com.fersaiyan.cyanbridge.shared.plugins.mentra

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Where a miniapp is published: an HTTPS folder serving `miniapp.json` (the author's manifest
 * plus `bundle: {url, sha256}`, written by `mentra-miniapp pack`) next to the immutable ZIP.
 * This ports the Mentra host's URL and domain rules (`cloud-v2/packages/protocol/src/miniapp-url.ts`)
 * so CyanBridge installs exactly what the Mentra App would.
 */
object MiniappSource {
    const val MAX_MANIFEST_BYTES = 64 * 1024
    const val MAX_BUNDLE_BYTES = 50 * 1024 * 1024
    private const val MAX_URL_LENGTH = 2_048
    private const val MENTRA_PACKAGE_PREFIX = "com.mentra."
    private const val MENTRA_DOMAIN_PREFIX = "com.mentraglass"
    private val SHA256_HEX = Regex("^[0-9a-f]{64}$")
    private val LOWERCASE_PACKAGE = Regex("^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+$")
    private val HOST_LABEL = Regex("^[a-z0-9_-]{1,63}$")
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Multi-label public suffixes (ICANN and private sections of the Public Suffix List) that miniapps
     * are commonly served under. A host under one of these owns one label more; any other host's
     * registrable domain is its last two labels. Shorter than the full list on purpose: a missing
     * suffix only makes the rule stricter for that host's neighbors, never looser for Mentra's names.
     */
    private val MULTI_LABEL_SUFFIXES = setOf(
        "co.uk", "org.uk", "ac.uk", "gov.uk", "com.au", "net.au", "org.au", "co.nz", "co.jp", "ne.jp", "or.jp",
        "com.br", "net.br", "org.br", "com.mx", "com.ar", "com.cn", "com.hk", "com.tw", "com.sg", "co.in", "co.kr",
        "co.za", "com.tr", "com.pl", "com.es",
        "github.io", "gitlab.io", "pages.dev", "workers.dev", "vercel.app", "netlify.app", "web.app",
        "firebaseapp.com", "herokuapp.com", "onrender.com", "fly.dev", "glitch.me", "surge.sh", "r2.dev",
        "s3.amazonaws.com", "cloudfront.net", "azurewebsites.net", "blob.core.windows.net", "appspot.com",
        "ngrok.io", "ngrok-free.app", "trycloudflare.com",
    )

    /** A miniapp folder URL: canonical (lowercase host, no default port, path ends in `/`), plus its host. */
    data class FolderUrl(val url: String, val host: String) {
        val manifestUrl: String get() = url + MiniappManifest.FILE_NAME
    }

    /**
     * Accepts what a user pastes: the folder URL, its `miniapp.json`, or an
     * `https://apps.mentraglass.com/open?url=…` link. Only public HTTPS is accepted (the Mentra
     * App's local-network dev installs are not supported). Throws [MiniappException].
     */
    fun parseFolderUrl(input: String): FolderUrl {
        var value = input.trim()
        openLinkTarget(value)?.let { value = it }
        if (value.endsWith("/" + MiniappManifest.FILE_NAME)) value = value.removeSuffix(MiniappManifest.FILE_NAME)
        if (value.length > MAX_URL_LENGTH || value.any { it.code <= 0x20 || it.code >= 0x7F } || '\\' in value) fail("This is not a miniapp URL")
        if ('?' in value || '#' in value) fail("Miniapp URLs have no query or fragment")
        if (!value.startsWith("https://", ignoreCase = true)) fail("Miniapps install from public https:// URLs only")
        val rest = value.substring("https://".length)
        val authority = rest.substringBefore('/')
        if ('@' in authority || authority.isEmpty()) fail("This is not a miniapp URL")
        val hostPart = authority.substringBefore(':').lowercase().trimEnd('.')
        val port = authority.substringAfter(':', "").takeIf { it.isNotEmpty() && it != "443" }
        if (port != null && port.toIntOrNull()?.takeIf { it in 1..65535 } == null) fail("Invalid port")
        if (registrableDomain(hostPart) == null) fail("Miniapps install from a public domain name")
        val path = "/" + rest.substringAfter('/', "")
        val folder = if (path.endsWith("/")) path else "$path/"
        if (folder.split('/').any { it == "." || it == ".." }) fail("This is not a miniapp URL")
        return FolderUrl("https://$hostPart${port?.let { ":$it" }.orEmpty()}$folder", hostPart)
    }

    /** The `url` parameter of an apps.mentraglass.com / apps.mentra.glass `open` link, decoded; null otherwise. */
    private fun openLinkTarget(value: String): String? {
        val prefixes = listOf("https://apps.mentraglass.com/open?", "https://apps.mentra.glass/open?")
        val query = prefixes.firstOrNull { value.startsWith(it, ignoreCase = true) }?.let { value.substring(it.length) } ?: return null
        val encoded = query.substringBefore('#').split('&').firstOrNull { it.startsWith("url=") }?.removePrefix("url=") ?: return null
        return percentDecode(encoded)
    }

    /** The registrable domain of [host] (see [MULTI_LABEL_SUFFIXES]); null for IPs, local names and bare suffixes. */
    fun registrableDomain(host: String): String? {
        val labels = host.lowercase().trimEnd('.').split('.')
        if (labels.size < 2 || labels.any { !HOST_LABEL.matches(it) }) return null
        if (labels.all { label -> label.all(Char::isDigit) }) return null
        if (labels.last() in setOf("local", "localhost", "internal", "arpa", "lan", "home")) return null
        val suffixLength = (labels.size - 1 downTo 2).firstOrNull { n -> labels.takeLast(n).joinToString(".") in MULTI_LABEL_SUFFIXES } ?: 1
        if (labels.size <= suffixLength) return null
        return labels.takeLast(suffixLength + 1).joinToString(".")
    }

    /** The package-name prefix [host]'s domain owns: the reversed registrable domain, hyphens as underscores. */
    fun packagePrefixForHost(host: String): String? {
        val domain = registrableDomain(host) ?: return null
        val parts = domain.split('.').reversed()
        // A label starting with a digit cannot be a package part; one with `_` could collide with a mapped `-`.
        if (parts.any { it.first().isDigit() || '_' in it }) return null
        return parts.joinToString(".") { it.replace('-', '_') }
    }

    /** The domain rule: [packageName] must extend the prefix [host] owns; `com.mentra.*` only from mentraglass.com. */
    fun isPackageNameAllowed(packageName: String, host: String): Boolean {
        if (!LOWERCASE_PACKAGE.matches(packageName)) return false
        val prefix = packagePrefixForHost(host) ?: return false
        if (packageName.startsWith(MENTRA_PACKAGE_PREFIX)) return prefix == MENTRA_DOMAIN_PREFIX
        return packageName.startsWith("$prefix.")
    }

    /** The bundle a served `miniapp.json` points at. */
    data class BundleRef(val url: String, val sha256: String)

    /** A served manifest: the validated manifest, its bundle, and the raw bytes' SHA-256. */
    data class ServedManifest(val manifest: MiniappManifest, val bundle: BundleRef, val manifestSha256: String)

    /**
     * Validates the `miniapp.json` served at [folder]: the manifest itself, the domain rule for
     * [finalHost] (where redirects ended), and `bundle {url, sha256}` resolved against the folder
     * (the bundle must stay on the same registrable domain).
     */
    fun parseServedManifest(bytes: ByteArray, folder: FolderUrl, finalHost: String = folder.host): ServedManifest {
        if (bytes.size > MAX_MANIFEST_BYTES) fail("miniapp.json is larger than 64 KB")
        val text = bytes.decodeToString()
        val manifest = MiniappManifest.parse(text)
        for (host in setOf(folder.host, finalHost)) {
            if (!isPackageNameAllowed(manifest.packageName, host)) {
                fail("${manifest.packageName} cannot be served from $host (the package name must start with the site's reversed domain)")
            }
        }
        val root = json.parseToJsonElement(text) as JsonObject
        val bundle = root["bundle"] as? JsonObject ?: fail("miniapp.json has no bundle (publish it with mentra-miniapp pack)")
        val rawUrl = (bundle["url"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: fail("bundle.url is missing")
        val sha = (bundle["sha256"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.lowercase()
            ?.takeIf(SHA256_HEX::matches) ?: fail("bundle.sha256 must be 64 hex characters")
        val url = resolve(folder, rawUrl)
        val bundleHost = url.removePrefix("https://").substringBefore('/').substringBefore(':')
        if (registrableDomain(bundleHost) != registrableDomain(folder.host)) fail("The bundle is hosted on another domain")
        return ServedManifest(manifest, BundleRef(url, sha), Sha256Hex.of(bytes))
    }

    private fun resolve(folder: FolderUrl, ref: String): String {
        if (ref.any { it.code <= 0x20 || it.code >= 0x7F } || '\\' in ref || '#' in ref) fail("Invalid bundle URL")
        return when {
            ref.startsWith("https://") -> ref.also { parseFolderUrl(it.substringBeforeLast('/') + "/") }
            ref.startsWith("http://") -> fail("The bundle must be served over https")
            ref.startsWith("//") || ref.contains("://") -> fail("The bundle must be served over https")
            ref.startsWith("/") -> {
                if (ref.split('/').drop(1).any { it == ".." || it == "." || it.isEmpty() }) fail("Invalid bundle URL")
                "https://" + folder.url.removePrefix("https://").substringBefore('/') + ref
            }
            else -> {
                if (ref.split('/').any { it == ".." || it == "." || it.isEmpty() }) fail("Invalid bundle URL")
                folder.url + ref
            }
        }
    }

    /** What to do with a newly fetched release of an installed miniapp. */
    enum class UpdateDecision { INSTALL, SAME_VERSION, DOWNGRADE_REFUSED }

    fun updateDecision(installedVersion: String?, offeredVersion: String): UpdateDecision {
        if (installedVersion == null) return UpdateDecision.INSTALL
        val order = compareSemver(offeredVersion, installedVersion)
        return when {
            order > 0 -> UpdateDecision.INSTALL
            order == 0 -> UpdateDecision.SAME_VERSION
            else -> UpdateDecision.DOWNGRADE_REFUSED
        }
    }

    /** SemVer 2.0.0 precedence (build metadata ignored). */
    fun compareSemver(a: String, b: String): Int {
        fun split(v: String): Pair<List<Int>, List<String>?> {
            val core = v.substringBefore('+')
            val main = core.substringBefore('-')
            val pre = core.substringAfter('-', "").takeIf { it.isNotEmpty() }?.split('.')
            return main.split('.').map { it.toIntOrNull() ?: 0 } to pre
        }
        val (am, ap) = split(a)
        val (bm, bp) = split(b)
        for (i in 0 until maxOf(am.size, bm.size)) {
            val c = (am.getOrElse(i) { 0 }).compareTo(bm.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        if (ap == null || bp == null) return if (ap == bp) 0 else if (ap == null) 1 else -1
        for (i in 0 until minOf(ap.size, bp.size)) {
            val x = ap[i]; val y = bp[i]
            val xn = x.toIntOrNull(); val yn = y.toIntOrNull()
            val c = when {
                xn != null && yn != null -> xn.compareTo(yn)
                xn != null -> -1
                yn != null -> 1
                else -> x.compareTo(y)
            }
            if (c != 0) return c
        }
        return ap.size.compareTo(bp.size)
    }

    private fun percentDecode(value: String): String {
        val out = mutableListOf<Byte>()
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '%' && i + 2 < value.length) {
                val byte = value.substring(i + 1, i + 3).toIntOrNull(16)
                if (byte != null) { out += byte.toByte(); i += 3; continue }
            }
            if (c == '+') out += ' '.code.toByte() else out += c.toString().encodeToByteArray().toList()
            i++
        }
        return out.toByteArray().decodeToString()
    }

    private fun fail(message: String): Nothing = throw MiniappException(message)
}

/** Lowercase hex SHA-256 (the shared implementation also used for `.mmpkg` packages). */
internal object Sha256Hex {
    fun of(bytes: ByteArray): String = com.fersaiyan.cyanbridge.shared.devices.memomind.webplugin.Sha256.hex(bytes)
}
