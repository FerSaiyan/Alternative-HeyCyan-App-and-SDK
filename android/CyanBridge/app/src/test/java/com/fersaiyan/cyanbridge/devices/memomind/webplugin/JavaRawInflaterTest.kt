package com.fersaiyan.cyanbridge.devices.memomind.webplugin

import com.fersaiyan.cyanbridge.shared.devices.memomind.webplugin.MmpkgException
import com.fersaiyan.cyanbridge.shared.devices.memomind.webplugin.MmpkgPackage
import com.fersaiyan.cyanbridge.shared.devices.memomind.webplugin.Sha256
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** A deflated package, as PhoneSDK's packager writes it, through the Android inflater. */
class JavaRawInflaterTest {
    private val files = linkedMapOf(
        "index.html" to "<!doctype html><script type=\"module\" src=\"plugin.js\"></script>".repeat(20).toByteArray(),
        "plugin.js" to "const gm = createGMPlugin(); await gm.ready();".repeat(50).toByteArray(),
    )

    private fun manifest() = """
        {"schemaVersion":2,"id":"com.example.weather","name":"Weather","version":"1.0.0","entry":"index.html",
         "bridgeVersion":"2.0","permissionPolicyVersion":1,
         "permissions":[{"name":"display","required":true},{"name":"network","required":true,"reason":"Fetch a forecast"}],
         "files":{${files.entries.joinToString(",") { "\"${it.key}\":\"sha256:${Sha256.hex(it.value)}\"" }}}}
    """.trimIndent()

    private fun zip(entries: Map<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { zip ->
            zip.setLevel(Deflater.BEST_COMPRESSION)
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }.toByteArray()

    @Test
    fun deflatedPackagesAreReadAndVerified() {
        val pkg = MmpkgPackage.read(zip(mapOf("manifest.json" to manifest().toByteArray()) + files), JavaRawInflater)

        assertEquals("com.example.weather", pkg.manifest.id)
        assertEquals(files.keys, pkg.files.keys)
        assertEquals(String(files.getValue("plugin.js")), String(pkg.files.getValue("plugin.js")))
    }

    @Test
    fun inflationStopsAtTheDeclaredSize() {
        val bomb = ByteArray(1 shl 20)
        val deflater = Deflater(Deflater.BEST_COMPRESSION, true).apply { setInput(bomb); finish() }
        val compressed = ByteArray(8192).let { buffer -> buffer.copyOf(deflater.deflate(buffer)) }

        assertEquals(bomb.size, JavaRawInflater.inflate(compressed, bomb.size).size)
        assertThrows(MmpkgException::class.java) { JavaRawInflater.inflate(compressed, 1024) }
    }
}
