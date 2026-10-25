package com.fersaiyan.cyanbridge.shared.devices.memomind.webplugin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Builds `.mmpkg` fixtures the way PhoneSDK's packager lays them out (manifest + payload at the root). */
internal object MmpkgFixture {
    const val INDEX = "<!doctype html><script type=\"module\" src=\"plugin.js\"></script>"
    const val SCRIPT = "import { createGMPlugin } from './vendor/gm-plugin-web-sdk.esm.js';"

    /** The app-counter sample's layout and permissions. */
    fun counterFiles(): Map<String, ByteArray> = linkedMapOf(
        "index.html" to INDEX.encodeToByteArray(),
        "plugin.js" to SCRIPT.encodeToByteArray(),
        "style.css" to "body { margin: 0 }".encodeToByteArray(),
        "vendor/gm-plugin-web-sdk.esm.js" to "export function createGMPlugin() {}".encodeToByteArray(),
    )

    fun manifest(
        files: Map<String, ByteArray>,
        id: String = "com.memomind.demo.counter",
        permissions: String = COUNTER_PERMISSIONS,
        hashes: Map<String, String> = files.mapValues { Sha256.hex(it.value) },
    ): String = """
        {
          "schemaVersion": 2,
          "id": "$id",
          "name": "Memo Plugin Counter Demo",
          "version": "0.1.0",
          "entry": "index.html",
          "bridgeVersion": "2.0",
          "permissionPolicyVersion": 1,
          "permissions": $permissions,
          "deviceRequirements": {
            "preferredPluginId": "com.gm.example.web-bridge",
            "protocols": [{ "id": "gm.scene", "minVersion": "1.0" }, { "id": "gm.device-events", "minVersion": "1.0" }]
          },
          "files": { ${hashes.entries.joinToString(", ") { "\"${it.key}\": \"sha256:${it.value}\"" }} }
        }
    """.trimIndent()

    const val COUNTER_PERMISSIONS = """[
        { "name": "display", "required": true },
        { "name": "device.events", "required": true, "scope": { "types": ["rawImu", "button", "imuGesture", "connection"] } },
        { "name": "storage", "required": true },
        { "name": "device.info", "required": false },
        { "name": "device.messaging", "required": false, "scope": { "channels": [17992, 17991] } }
    ]"""

    fun archive(manifest: String, files: Map<String, ByteArray>): ByteArray =
        zip(listOf(Entry("manifest.json", manifest.encodeToByteArray())) + files.map { Entry(it.key, it.value) })

    class Entry(val name: String, val bytes: ByteArray, val unixMode: Int = 0x81A4)

    /** A STORED ZIP with Unix modes in the external attributes. */
    fun zip(entries: List<Entry>): ByteArray {
        val out = mutableListOf<Byte>()
        val central = mutableListOf<Byte>()
        fun MutableList<Byte>.u16(value: Int) { add(value.toByte()); add((value shr 8).toByte()) }
        fun MutableList<Byte>.u32(value: Long) { u16((value and 0xFFFF).toInt()); u16(((value shr 16) and 0xFFFF).toInt()) }
        entries.forEach { entry ->
            val name = entry.name.encodeToByteArray()
            val crc = Crc32.of(entry.bytes)
            val offset = out.size.toLong()
            out.u32(0x04034b50); out.u16(20); out.u16(0x0800); out.u16(0); out.u16(0); out.u16(0x21)
            out.u32(crc); out.u32(entry.bytes.size.toLong()); out.u32(entry.bytes.size.toLong()); out.u16(name.size); out.u16(0)
            out.addAll(name.toList()); out.addAll(entry.bytes.toList())
            central.u32(0x02014b50); central.u16(0x0314); central.u16(20); central.u16(0x0800); central.u16(0); central.u16(0); central.u16(0x21)
            central.u32(crc); central.u32(entry.bytes.size.toLong()); central.u32(entry.bytes.size.toLong()); central.u16(name.size)
            central.u16(0); central.u16(0); central.u16(0); central.u16(0); central.u32(entry.unixMode.toLong() shl 16); central.u32(offset)
            central.addAll(name.toList())
        }
        val directoryOffset = out.size.toLong()
        out.addAll(central)
        out.u32(0x06054b50); out.u16(0); out.u16(0); out.u16(entries.size); out.u16(entries.size)
        out.u32(central.size.toLong()); out.u32(directoryOffset); out.u16(0)
        return out.toByteArray()
    }

    val noInflate = RawInflater { _, _ -> error("fixtures are stored") }
}

class MmpkgPackageTest {
    private fun read(archive: ByteArray) = MmpkgPackage.read(archive, MmpkgFixture.noInflate)

    @Test
    fun sha256MatchesTheStandardVectors() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Sha256.hex("abc".encodeToByteArray()))
        assertEquals(
            "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
            Sha256.hex("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq".encodeToByteArray()),
        )
    }

    @Test
    fun aPackagerLayoutIsReadAndItsPermissionsNormalized() {
        val files = MmpkgFixture.counterFiles()
        val pkg = read(MmpkgFixture.archive(MmpkgFixture.manifest(files), files))

        val manifest = pkg.manifest
        assertEquals("com.memomind.demo.counter", manifest.id)
        assertEquals("index.html", manifest.entry)
        assertEquals(files.keys, pkg.files.keys)
        assertEquals(listOf("device.events", "device.info", "device.messaging", "display", "storage"), manifest.permissions.map { it.name })
        assertEquals(listOf("button", "connection", "imuGesture", "rawImu"), manifest.permissions.first().scope)
        assertEquals(listOf(17991, 17992), manifest.permissions.single { it.name == "device.messaging" }.channels)
        assertTrue(manifest.deviceRequirements!!.webBridgeCompatible)
    }

    @Test
    fun tamperedOrUnsafePackagesAreRejected() {
        val files = MmpkgFixture.counterFiles()
        val manifest = MmpkgFixture.manifest(files)
        fun rejected(archive: ByteArray, reason: String) {
            val error = assertFailsWith<MmpkgException> { read(archive) }
            assertTrue(reason in error.message.orEmpty(), "expected '$reason' in '${error.message}'")
        }

        rejected(MmpkgFixture.archive(manifest, files + ("plugin.js" to "alert(1)".encodeToByteArray())), "Hash mismatch")
        rejected(MmpkgFixture.archive(manifest, files + ("extra.js" to byteArrayOf(1))), "not listed")
        rejected(MmpkgFixture.archive(manifest, files - "style.css"), "missing")
        rejected(MmpkgFixture.archive(manifest, files + ("../escape.js" to byteArrayOf(1))), "Unsafe path")
        rejected(MmpkgFixture.archive(manifest, files + ("Plugin.js" to byteArrayOf(1))), "Duplicate")
        rejected(
            MmpkgFixture.zip(
                listOf(MmpkgFixture.Entry("manifest.json", manifest.encodeToByteArray())) +
                    files.map { MmpkgFixture.Entry(it.key, it.value, unixMode = if (it.key == "style.css") 0xA1FF else 0x81A4) },
            ),
            "regular files",
        )
        rejected(MmpkgFixture.archive(MmpkgFixture.manifest(files, id = "not an id"), files), "reverse-domain")
        rejected(
            MmpkgFixture.archive(MmpkgFixture.manifest(files, permissions = """[{ "name": "device.messaging", "required": true }]"""), files),
            "scope",
        )
        rejected(MmpkgFixture.archive(manifest.replace("\"schemaVersion\": 2", "\"schemaVersion\": 1"), files), "schemaVersion")
        rejected(MmpkgFixture.archive(manifest, files).copyOf(100), "Not a ZIP")
    }
}
