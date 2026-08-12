package com.fersaiyan.cyanbridge.localmodels.storage

import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

object LocalModelFileUtils {
    private val unsafeChars = Regex("[^A-Za-z0-9._-]+")
    private val knownExtensions = listOf(".gguf", ".litertlm", ".task")
    private const val GGUF_UINT32 = 4
    private const val PRISM_Q1_0_FILE_TYPE = 40
    private const val PRISM_Q2_0_FILE_TYPE = 41
    private const val MAX_GGUF_METADATA_SCAN_BYTES = 32 * 1024 * 1024

    fun sanitizeFileName(fileName: String, defaultExtension: String = ".gguf"): String {
        val normalizedDefault = if (defaultExtension.startsWith(".")) {
            defaultExtension.lowercase()
        } else {
            ".${defaultExtension.lowercase()}"
        }
        val trimmed = fileName.trim().ifBlank { "model$normalizedDefault" }
        val replaced = trimmed.replace(unsafeChars, "_")
        return if (knownExtensions.any { replaced.endsWith(it, ignoreCase = true) }) {
            replaced
        } else {
            "$replaced$normalizedDefault"
        }
    }

    fun isGgufFile(file: File): Boolean {
        if (!file.exists() || !file.isFile || file.length() < 4) return false
        return runCatching {
            FileInputStream(file).use { input ->
                val header = ByteArray(4)
                if (input.read(header) != 4) return false
                header[0] == 'G'.code.toByte() &&
                    header[1] == 'G'.code.toByte() &&
                    header[2] == 'U'.code.toByte() &&
                    header[3] == 'F'.code.toByte()
            }
        }.getOrDefault(false)
    }

    fun llamaCppCompatibilityError(file: File): String? {
        if (!isGgufFile(file)) return null
        val fileType = readGgufUInt32Metadata(file, "general.file_type") ?: return null
        if (fileType != PRISM_Q1_0_FILE_TYPE && fileType != PRISM_Q2_0_FILE_TYPE) return null

        val quantization = if (fileType == PRISM_Q1_0_FILE_TYPE) "Q1_0" else "Q2_0"
        return "$quantization GGUF models, including Bonsai, require PrismML's custom llama.cpp runtime " +
            "and cannot run in this CyanBridge build yet. Loading this format can terminate the Android process. " +
            "Use a conventional Q4 GGUF, or run PrismML llama-server on a computer and configure CyanBridge's " +
            "Remote OpenAI server over LAN or Tailscale."
    }

    private fun readGgufUInt32Metadata(file: File, key: String): Int? {
        val keyBytes = key.toByteArray(Charsets.US_ASCII)
        val signature = ByteArray(Long.SIZE_BYTES + keyBytes.size).also { bytes ->
            bytes[0] = keyBytes.size.toByte()
            keyBytes.copyInto(bytes, destinationOffset = Long.SIZE_BYTES)
        }

        return runCatching {
            BufferedInputStream(FileInputStream(file)).use { input ->
                var matched = 0
                var scanned = 0
                while (scanned < MAX_GGUF_METADATA_SCAN_BYTES) {
                    val value = input.read()
                    if (value < 0) return@use null
                    scanned++
                    matched = when {
                        value.toByte() == signature[matched] -> matched + 1
                        value.toByte() == signature[0] -> 1
                        else -> 0
                    }
                    if (matched == signature.size) {
                        val valueType = readLittleEndianInt(input) ?: return@use null
                        if (valueType != GGUF_UINT32) return@use null
                        return@use readLittleEndianInt(input)
                    }
                }
                null
            }
        }.getOrNull()
    }

    private fun readLittleEndianInt(input: BufferedInputStream): Int? {
        var result = 0
        repeat(Int.SIZE_BYTES) { shift ->
            val value = input.read()
            if (value < 0) return null
            result = result or (value shl (shift * 8))
        }
        return result
    }

    fun isLiteRtPackageFile(file: File): Boolean {
        if (!file.exists() || !file.isFile || file.length() <= 1_048_576L) return false
        val name = file.name.lowercase()
        val extensionOk = name.endsWith(".litertlm") ||
            name.endsWith(".task") ||
            name.endsWith(".litertlm.part") ||
            name.endsWith(".task.part")
        if (!extensionOk) return false
        if (looksLikeTextOrHtml(file)) return false
        return true
    }

    private fun looksLikeTextOrHtml(file: File): Boolean {
        return runCatching {
            FileInputStream(file).use { input ->
                val sample = ByteArray(512)
                val n = input.read(sample)
                if (n <= 0) return@use true
                val head = String(sample, 0, n, Charsets.UTF_8).trimStart().lowercase()
                head.startsWith("<!doctype html") ||
                    head.startsWith("<html") ||
                    head.startsWith("<xml") ||
                    head.startsWith("{\"error\"") ||
                    head.startsWith("{\"message\"")
            }
        }.getOrDefault(true)
    }

    fun isSupportedModelFile(file: File): Boolean {
        return isGgufFile(file) || isLiteRtPackageFile(file)
    }

    fun isFileCompatibleWithFormat(file: File, format: String?): Boolean {
        return when (format?.lowercase()) {
            "gguf" -> isGgufFile(file)
            "litertlm", "task", "litert" -> isLiteRtPackageFile(file)
            else -> isSupportedModelFile(file)
        }
    }

    fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buf = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString(separator = "") { b -> "%02x".format(b) }
    }
}
