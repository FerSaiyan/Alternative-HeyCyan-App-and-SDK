package com.fersaiyan.cyanbridge.localmodels

import com.fersaiyan.cyanbridge.localmodels.storage.LocalModelFileUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LocalModelFileUtilsTest {
    @Test
    fun sanitize_filename_normalizes_extension_and_chars() {
        val clean = LocalModelFileUtils.sanitizeFileName(" qwen 2.5@mobile ")
        assertTrue(clean.endsWith(".gguf"))
        assertFalse(clean.contains(" "))
        assertFalse(clean.contains("@"))
    }

    @Test
    fun gguf_header_detection_works() {
        val tmp = File.createTempFile("local-model", ".gguf")
        tmp.writeBytes(byteArrayOf('G'.code.toByte(), 'G'.code.toByte(), 'U'.code.toByte(), 'F'.code.toByte(), 1, 2))
        assertTrue(LocalModelFileUtils.isGgufFile(tmp))
        tmp.delete()
    }

    @Test
    fun prism_q1_gguf_is_rejected_before_native_loading() {
        val tmp = File.createTempFile("bonsai", ".gguf")
        val key = "general.file_type".toByteArray(Charsets.US_ASCII)
        tmp.outputStream().use { output ->
            output.write(byteArrayOf('G'.code.toByte(), 'G'.code.toByte(), 'U'.code.toByte(), 'F'.code.toByte()))
            output.write(ByteArray(20))
            output.write(byteArrayOf(key.size.toByte(), 0, 0, 0, 0, 0, 0, 0))
            output.write(key)
            output.write(byteArrayOf(4, 0, 0, 0))
            output.write(byteArrayOf(40, 0, 0, 0))
        }

        val error = LocalModelFileUtils.llamaCppCompatibilityError(tmp)
        assertTrue(error?.contains("Bonsai") == true)
        assertTrue(error?.contains("Q1_0") == true)
        tmp.delete()
    }

    @Test
    fun prism_q2_is_rejected_while_conventional_q4_is_allowed() {
        assertTrue(compatibilityErrorForFileType(41)?.contains("Q2_0") == true)
        assertEquals(null, compatibilityErrorForFileType(15))
    }

    private fun compatibilityErrorForFileType(fileType: Int): String? {
        val tmp = File.createTempFile("model", ".gguf")
        val key = "general.file_type".toByteArray(Charsets.US_ASCII)
        tmp.outputStream().use { output ->
            output.write(byteArrayOf('G'.code.toByte(), 'G'.code.toByte(), 'U'.code.toByte(), 'F'.code.toByte()))
            output.write(ByteArray(20))
            output.write(byteArrayOf(key.size.toByte(), 0, 0, 0, 0, 0, 0, 0))
            output.write(key)
            output.write(byteArrayOf(4, 0, 0, 0))
            output.write(byteArrayOf(fileType.toByte(), 0, 0, 0))
        }
        return LocalModelFileUtils.llamaCppCompatibilityError(tmp).also { tmp.delete() }
    }

    @Test
    fun sha256_is_stable() {
        val tmp = File.createTempFile("local-model", ".gguf")
        tmp.writeText("abc")
        val first = LocalModelFileUtils.sha256Hex(tmp)
        val second = LocalModelFileUtils.sha256Hex(tmp)
        assertEquals(first, second)
        tmp.delete()
    }
}
