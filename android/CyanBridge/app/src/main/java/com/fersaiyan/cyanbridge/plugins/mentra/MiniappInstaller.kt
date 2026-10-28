package com.fersaiyan.cyanbridge.plugins.mentra

import com.fersaiyan.cyanbridge.devices.memomind.webplugin.JavaRawInflater
import com.fersaiyan.cyanbridge.shared.plugins.mentra.MiniappBundle
import com.fersaiyan.cyanbridge.shared.plugins.mentra.MiniappException
import com.fersaiyan.cyanbridge.shared.plugins.mentra.MiniappSource
import com.fersaiyan.cyanbridge.shared.plugins.mentra.MiniappStoreCatalog
import com.fersaiyan.cyanbridge.shared.plugins.mentra.MiniappStoreListing
import com.fersaiyan.cyanbridge.shared.plugins.mentra.MiniappStorePage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** A release ready to install: its served manifest, where it came from, and the installed copy, if any. */
class MiniappOffer(
    val served: MiniappSource.ServedManifest,
    val folder: MiniappSource.FolderUrl,
    val etag: String?,
    val installed: InstalledMiniapp?,
) {
    val decision: MiniappSource.UpdateDecision
        get() = MiniappSource.updateDecision(installed?.manifest?.version, served.manifest.version)
}

/**
 * Network side of installing Mentra miniapps, following the Mentra App's rules
 * (`installMiniapp.ts`, `miniappFetch.ts`): HTTPS only, no credentials or cookies, size caps,
 * redirects followed by hand (at most 3) and re-checked against the domain rule, the bundle's
 * SHA-256 against `bundle.sha256`, conditional GETs with the stored ETag for updates.
 */
class MiniappInstaller(private val store: MiniappStore) {

    /** Fetches and validates the miniapp at [input] (a folder URL, its miniapp.json, or an apps.mentraglass.com open link). */
    suspend fun fetchOffer(input: String): MiniappOffer = withContext(Dispatchers.IO) {
        val folder = MiniappSource.parseFolderUrl(input)
        val response = get(folder.manifestUrl, MiniappSource.MAX_MANIFEST_BYTES, etag = null)
        offerFrom(folder, response, installed = null)
    }

    /** Downloads, verifies and installs [offer]. Refuses downgrades. */
    suspend fun install(offer: MiniappOffer): InstalledMiniapp = withContext(Dispatchers.IO) {
        if (offer.decision == MiniappSource.UpdateDecision.DOWNGRADE_REFUSED) {
            throw MiniappException("${offer.served.manifest.name} ${offer.installed?.manifest?.version} is newer than ${offer.served.manifest.version}")
        }
        val bytes = get(offer.served.bundle.url, MiniappSource.MAX_BUNDLE_BYTES, etag = null).body
            ?: throw MiniappException("The bundle download was empty")
        val bundle = MiniappBundle.read(bytes, offer.served, JavaRawInflater)
        store.install(bundle, offer.folder.url, offer.etag)
    }

    /**
     * Conditional GET of [app]'s miniapp.json (`If-None-Match`). Returns an offer only for a
     * newer version; a 304 or the same version just records the check.
     */
    suspend fun checkUpdate(app: InstalledMiniapp): MiniappOffer? = withContext(Dispatchers.IO) {
        val source = app.source ?: return@withContext null
        val folder = MiniappSource.parseFolderUrl(source.folderUrl)
        val response = get(folder.manifestUrl, MiniappSource.MAX_MANIFEST_BYTES, etag = source.etag)
        if (response.status == HttpURLConnection.HTTP_NOT_MODIFIED) {
            store.markChecked(app, response.etag)
            return@withContext null
        }
        val offer = offerFrom(folder, response, app)
        if (offer.served.manifest.packageName != app.manifest.packageName) throw MiniappException("The miniapp URL now serves another package")
        if (offer.decision != MiniappSource.UpdateDecision.INSTALL) {
            store.markChecked(app, response.etag)
            null
        } else {
            offer
        }
    }

    /** One page of the Mentra Miniapp Store's public catalog. */
    suspend fun storePage(page: Int, query: String): MiniappStorePage = withContext(Dispatchers.IO) {
        val body = get(MiniappStoreCatalog.pageUrl(page, query), MAX_CATALOG_BYTES, etag = null).body
            ?: throw MiniappException("The store returned nothing")
        MiniappStoreCatalog.parsePage(body.decodeToString())
    }

    /**
     * Where a store listing is published as a miniapp URL. Store bundles themselves need a Mentra
     * store credential, so CyanBridge asks the public directory (listed URL miniapps) and falls
     * back to Mentra's public first-party host for `com.mentra.*`.
     */
    suspend fun folderForListing(listing: MiniappStoreListing): String = withContext(Dispatchers.IO) {
        val lookup = runCatching {
            get("${MiniappStoreCatalog.API_BASE}/api/directory/miniapps/${listing.packageName}", 16 * 1024, etag = null)
        }.getOrNull()
        val listed = lookup?.body?.decodeToString()?.let { text ->
            Regex("\"url\"\\s*:\\s*\"(https://[^\"]+)\"").find(text)?.groupValues?.get(1)
        }
        listed ?: if (listing.packageName.startsWith("com.mentra.")) {
            "$FIRST_PARTY_HOST${listing.packageName}/"
        } else {
            throw MiniappException("${listing.name} is not published at a public miniapp URL. Install it from the Mentra App instead.")
        }
    }

    /** A store icon (public PNG), capped. */
    suspend fun icon(url: String): ByteArray? = withContext(Dispatchers.IO) {
        runCatching { get(url, MAX_ICON_BYTES, etag = null).body }.getOrNull()
    }

    private fun offerFrom(folder: MiniappSource.FolderUrl, response: Response, installed: InstalledMiniapp?): MiniappOffer {
        val body = response.body ?: throw MiniappException("miniapp.json was empty")
        val finalHost = URL(response.finalUrl).host.lowercase()
        val served = MiniappSource.parseServedManifest(body, folder, finalHost)
        return MiniappOffer(served, folder, response.etag, installed ?: store.find(served.manifest.packageName))
    }

    private class Response(val status: Int, val body: ByteArray?, val etag: String?, val finalUrl: String)

    private fun get(url: String, maxBytes: Int, etag: String?): Response {
        var current = url
        repeat(MAX_REDIRECTS + 1) {
            if (!current.startsWith("https://")) throw MiniappException("Only https:// URLs are fetched")
            val connection = URL(current).openConnection() as HttpURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.useCaches = false
                connection.connectTimeout = 15_000
                connection.readTimeout = 45_000
                connection.setRequestProperty("Accept", "application/json, application/zip, */*")
                connection.setRequestProperty("Cache-Control", "no-cache")
                etag?.let { connection.setRequestProperty("If-None-Match", it) }
                val status = connection.responseCode
                when (status) {
                    in 300..399 -> {
                        if (status == HttpURLConnection.HTTP_NOT_MODIFIED) return Response(status, null, connection.getHeaderField("ETag") ?: etag, current)
                        val location = connection.getHeaderField("Location") ?: throw MiniappException("Redirect without a location")
                        val next = URL(URL(current), location).toString()
                        val fromDomain = MiniappSource.registrableDomain(URL(current).host)
                        if (MiniappSource.registrableDomain(URL(next).host) != fromDomain) throw MiniappException("The miniapp URL redirects to another site")
                        current = next
                        return@repeat
                    }
                    401, 403 -> throw MiniappException("This miniapp is private (it needs a Mentra sign-in)")
                    404 -> throw MiniappException("No miniapp was found at this URL")
                    410 -> throw MiniappException("This miniapp was archived by its publisher")
                    !in 200..299 -> throw MiniappException("The server answered HTTP $status")
                }
                val declared = connection.contentLengthLong
                if (declared > maxBytes) throw MiniappException("The download is larger than ${maxBytes / 1024} KB")
                val out = ByteArrayOutputStream()
                connection.inputStream.use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        if (out.size() > maxBytes) throw MiniappException("The download is larger than ${maxBytes / 1024} KB")
                    }
                }
                return Response(status, out.toByteArray(), connection.getHeaderField("ETag"), current)
            } catch (error: IOException) {
                throw MiniappException("Network error: ${error.message ?: error.javaClass.simpleName}")
            } finally {
                connection.disconnect()
            }
        }
        throw MiniappException("Too many redirects")
    }

    companion object {
        /** Mentra's public host for its own miniapps (the Mentra App updates them from here). */
        const val FIRST_PARTY_HOST = "https://miniapps.mentraglass.com/"
        private const val MAX_REDIRECTS = 3
        private const val MAX_CATALOG_BYTES = 2 * 1024 * 1024
        private const val MAX_ICON_BYTES = 512 * 1024
    }
}
