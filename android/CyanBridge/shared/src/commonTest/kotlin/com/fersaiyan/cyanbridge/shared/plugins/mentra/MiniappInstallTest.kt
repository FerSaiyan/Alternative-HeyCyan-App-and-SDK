package com.fersaiyan.cyanbridge.shared.plugins.mentra

import com.fersaiyan.cyanbridge.shared.devices.memomind.webplugin.MmpkgFixture
import com.fersaiyan.cyanbridge.shared.devices.memomind.webplugin.Sha256
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Manifest, URL/domain rules, bundle verification and the store feed, as the Mentra host applies them. */
class MiniappInstallTest {
    private val folder = MiniappSource.parseFolderUrl("https://miniapps.mentraglass.com/com.mentra.teleprompter/")

    private fun manifest(packageName: String = "com.mentra.teleprompter", version: String = "1.0.12", bundle: String? = null) = """
        {
          "packageName": "$packageName", "version": "$version", "name": "Teleprompter", "type": "standard",
          "sdkVersion": "0.3.0", "minHostVersion": "3.2.1",
          "entry": { "background": "background/index.js", "ui": "ui/index.html" },
          "permissions": [{ "type": "MICROPHONE", "description": "voice follow" }],
          "hardwareRequirements": [{ "type": "DISPLAY", "level": "REQUIRED" }, { "type": "MICROPHONE", "level": "OPTIONAL" }],
          "actions": [{ "id": "load_script", "description": "Load text" }]
          ${bundle?.let { ", \"bundle\": $it" }.orEmpty()}
        }
    """.trimIndent()

    @Test
    fun aPackedManifestIsParsed() {
        val parsed = MiniappManifest.parse(manifest())
        assertEquals("background/index.js", parsed.backgroundEntry)
        assertEquals("ui/index.html", parsed.uiEntry)
        assertTrue(parsed.requires(MiniappHardware.DISPLAY))
        assertEquals(true, parsed.permissions.single().required)
        assertEquals(1, parsed.actionCount)
        assertFailsWith<MiniappException> { MiniappManifest.parse(manifest().replace("background/index.js", "../escape.js")) }
        assertFailsWith<MiniappException> { MiniappManifest.parse(manifest().replace("MICROPHONE\", \"description", "SMS\", \"description")) }
    }

    @Test
    fun urlsAndTheDomainRule() {
        assertEquals("https://miniapps.mentraglass.com/com.mentra.teleprompter/miniapp.json", folder.manifestUrl)
        assertEquals(folder, MiniappSource.parseFolderUrl("https://MiniApps.mentraglass.com/com.mentra.teleprompter/miniapp.json"))
        assertEquals(
            "https://notes.example.com/app/",
            MiniappSource.parseFolderUrl("https://apps.mentraglass.com/open?url=https%3A%2F%2Fnotes.example.com%2Fapp").url,
        )
        listOf("http://example.com/app/", "https://192.168.1.4/app/", "https://example.com/app/?x=1", "https://u@example.com/").forEach {
            assertFailsWith<MiniappException>(it) { MiniappSource.parseFolderUrl(it) }
        }
        val cases = listOf(
            Triple("com.example.notes", "notes.example.com", true),
            Triple("com.my_site.app", "www.my-site.com", true),
            Triple("com.example", "example.com", false), // needs one more part
            Triple("com.other.notes", "notes.example.com", false),
            Triple("io.github.alice.app", "alice.github.io", true),
            Triple("io.github.bob.app", "alice.github.io", false),
            Triple("com.mentra.teleprompter", "miniapps.mentraglass.com", true),
            Triple("com.mentra.fake", "evil.example.com", false),
            Triple("com.mentraglass.thing", "x.mentraglass.com", true),
        )
        cases.forEach { (pkg, host, allowed) -> assertEquals(allowed, MiniappSource.isPackageNameAllowed(pkg, host), "$pkg from $host") }
    }

    @Test
    fun theServedManifestAndBundleAreVerified() {
        val ui = "<!doctype html>".encodeToByteArray()
        val background = "(()=>{})();".encodeToByteArray()
        val inner = manifest().encodeToByteArray()
        val zip = MmpkgFixture.zip(listOf(
            MmpkgFixture.Entry("miniapp.json", inner),
            MmpkgFixture.Entry("background/index.js", background),
            MmpkgFixture.Entry("ui/index.html", ui),
        ))
        val sha = Sha256.hex(zip)
        val served = MiniappSource.parseServedManifest(
            manifest(bundle = """{ "url": "com.mentra.teleprompter-1.0.12.zip", "sha256": "${sha.uppercase()}" }""").encodeToByteArray(),
            folder,
        )
        assertEquals("https://miniapps.mentraglass.com/com.mentra.teleprompter/com.mentra.teleprompter-1.0.12.zip", served.bundle.url)

        val bundle = MiniappBundle.read(zip, served, MmpkgFixture.noInflate)
        assertEquals(setOf("background/index.js", "ui/index.html"), bundle.files.keys)
        assertFalse(bundle.signed)

        assertFailsWith<MiniappException> { MiniappBundle.read(zip + 0, served, MmpkgFixture.noInflate) }
        val otherVersion = MiniappSource.parseServedManifest(
            manifest(version = "1.0.13", bundle = """{ "url": "b.zip", "sha256": "$sha" }""").encodeToByteArray(), folder,
        )
        assertFailsWith<MiniappException> { MiniappBundle.read(zip, otherVersion, MmpkgFixture.noInflate) }
        assertFailsWith<MiniappException> {
            MiniappSource.parseServedManifest(manifest(bundle = """{ "url": "https://cdn.other.net/b.zip", "sha256": "$sha" }""").encodeToByteArray(), folder)
        }
        assertFailsWith<MiniappException> { MiniappSource.parseServedManifest(manifest().encodeToByteArray(), folder) }
    }

    @Test
    fun updatesNeverDowngrade() {
        assertEquals(MiniappSource.UpdateDecision.INSTALL, MiniappSource.updateDecision(null, "1.0.0"))
        assertEquals(MiniappSource.UpdateDecision.INSTALL, MiniappSource.updateDecision("1.0.9", "1.0.10"))
        assertEquals(MiniappSource.UpdateDecision.INSTALL, MiniappSource.updateDecision("1.1.0-beta.2", "1.1.0"))
        assertEquals(MiniappSource.UpdateDecision.SAME_VERSION, MiniappSource.updateDecision("1.0.12", "1.0.12+build.7"))
        assertEquals(MiniappSource.UpdateDecision.DOWNGRADE_REFUSED, MiniappSource.updateDecision("1.0.12", "1.0.2"))
    }

    @Test
    fun theStoreFeedIsParsed() {
        val page = MiniappStoreCatalog.parsePage(
            """
            {"apps":[{"packageName":"com.mentra.captions","name":"Captions","subtitle":"Live captions","description":"d",
              "categories":["accessibility","communication"],"reviewTier":"community",
              "iconUrl":"https://api.apps.mentraglass.com/api/store/assets/6ac5","release":{"version":"1.0.23","installable":true,
              "bundleUrl":"https://api.apps.mentraglass.com/api/store/bundles/6ac/download",
              "bundleSha256":"68906f738fba30621b138ed4e2400e2baaddcc440cef0cdd2619dcf1948058fc",
              "permissions":[{"type":"MICROPHONE"}],"hardwareRequirements":[{"type":"DISPLAY","level":"OPTIONAL"}]}},
             {"packageName":"bad name","name":"x"}],
             "page":1,"limit":24,"total":2,"hasMore":false}
            """.trimIndent(),
        )
        val app = page.apps.single()
        assertEquals(listOf("accessibility", "communication"), app.categories)
        assertEquals("1.0.23", app.version)
        assertEquals(listOf(MiniappPermissionType.MICROPHONE), app.permissions)
        assertFalse(page.hasMore)
        assertEquals("https://api.apps.mentraglass.com/api/store/apps?limit=24&page=2&q=live%20captions", MiniappStoreCatalog.pageUrl(2, " live captions "))
    }
}
