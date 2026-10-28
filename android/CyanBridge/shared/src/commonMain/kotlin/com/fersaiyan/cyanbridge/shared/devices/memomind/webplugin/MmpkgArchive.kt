package com.fersaiyan.cyanbridge.shared.devices.memomind.webplugin

/**
 * Inflates one raw DEFLATE stream (ZIP method 8). Implementations must fail rather than produce
 * more than [expectedSize] bytes (zip-bomb guard), and return exactly [expectedSize] bytes.
 */
fun interface RawInflater {
    fun inflate(compressed: ByteArray, expectedSize: Int): ByteArray
}

/** A package rejected during installation; [message] says why (shown to the user). */
class MmpkgException(message: String) : Exception(message)

/** `.mmpkg` limits from the package format (`MMPKG_LIMITS`). */
object MmpkgLimits {
    const val MAX_PACKAGE_BYTES = 10 * 1024 * 1024
    const val MAX_EXTRACTED_BYTES = 30 * 1024 * 1024
    const val MAX_FILE_BYTES = 10 * 1024 * 1024
    const val MAX_FILES = 500
    const val MAX_PATH_LENGTH = 512
}

/** Size limits [MmpkgZip.read] enforces; the defaults are the `.mmpkg` ones. Messages name them in MB. */
data class ZipLimits(
    val maxPackageBytes: Int = MmpkgLimits.MAX_PACKAGE_BYTES,
    val maxExtractedBytes: Long = MmpkgLimits.MAX_EXTRACTED_BYTES.toLong(),
    val maxFileBytes: Int = MmpkgLimits.MAX_FILE_BYTES,
    val maxFiles: Int = MmpkgLimits.MAX_FILES,
)

/**
 * A strict reader for the ZIP container of an `.mmpkg`: one disk, no ZIP64, no encryption,
 * methods STORED and DEFLATE, regular files and directory entries only (symbolic links and
 * other Unix file types are refused), safe relative paths without duplicates (also
 * case-insensitive ones), CRC-32 checked, and the package size limits enforced from the
 * central directory before anything is inflated.
 */
internal object MmpkgZip {
    private const val LOCAL_HEADER = 0x04034b50
    private const val CENTRAL_HEADER = 0x02014b50
    private const val END_OF_CENTRAL_DIRECTORY = 0x06054b50
    private const val METHOD_STORED = 0
    private const val METHOD_DEFLATE = 8
    private const val UNIX_HOST = 3
    private const val UNIX_TYPE_MASK = 0xF000
    private const val UNIX_REGULAR = 0x8000
    private const val UNIX_DIRECTORY = 0x4000

    /** Path to content of every regular file, in archive order. */
    fun read(archive: ByteArray, inflater: RawInflater, limits: ZipLimits = ZipLimits()): LinkedHashMap<String, ByteArray> {
        if (archive.size > limits.maxPackageBytes) fail("The package is larger than ${limits.maxPackageBytes.mb()} MB")
        val end = findEnd(archive)
        if (u16(archive, end + 4) != 0 || u16(archive, end + 6) != 0) fail("Multi-disk archives are not supported")
        val count = u16(archive, end + 10)
        if (count != u16(archive, end + 8) || count == 0xFFFF) fail("Unsupported archive directory")
        val directorySize = u32(archive, end + 12)
        val directoryOffset = u32(archive, end + 16)
        if (directoryOffset + directorySize > end) fail("Corrupt archive directory")

        val entries = mutableListOf<Entry>()
        var cursor = directoryOffset.toInt()
        var total = 0L
        val seen = mutableSetOf<String>()
        repeat(count) {
            if (cursor + 46 > end || u32(archive, cursor) != CENTRAL_HEADER.toLong()) fail("Corrupt archive directory")
            val madeBy = u16(archive, cursor + 4)
            val flags = u16(archive, cursor + 8)
            val method = u16(archive, cursor + 10)
            val crc = u32(archive, cursor + 16)
            val compressedSize = u32(archive, cursor + 20)
            val size = u32(archive, cursor + 24)
            val nameLength = u16(archive, cursor + 28)
            val extraLength = u16(archive, cursor + 30)
            val commentLength = u16(archive, cursor + 32)
            val externalAttributes = u32(archive, cursor + 38)
            val localOffset = u32(archive, cursor + 42)
            val nameEnd = cursor + 46 + nameLength
            if (nameEnd > end) fail("Corrupt archive directory")
            val rawName = archive.copyOfRange(cursor + 46, nameEnd)
            cursor = nameEnd + extraLength + commentLength
            if (cursor > end) fail("Corrupt archive directory")

            val name = rawName.decodeToString()
            if (!name.encodeToByteArray().contentEquals(rawName)) fail("A file name is not valid UTF-8")
            if (flags and 0x0001 != 0) fail("Encrypted files are not supported")
            if (compressedSize == 0xFFFFFFFFL || size == 0xFFFFFFFFL || localOffset == 0xFFFFFFFFL) fail("ZIP64 is not supported")
            val unixType = if (madeBy shr 8 == UNIX_HOST) ((externalAttributes shr 16).toInt() and UNIX_TYPE_MASK) else 0
            val isDirectory = name.endsWith("/")
            if (isDirectory) {
                if (size != 0L || (unixType != 0 && unixType != UNIX_DIRECTORY)) fail("Invalid directory entry $name")
                requireSafePath(name.removeSuffix("/"))
                return@repeat
            }
            if (unixType != 0 && unixType != UNIX_REGULAR) fail("Only regular files are allowed ($name)")
            requireSafePath(name)
            if (!seen.add(name.lowercase())) fail("Duplicate file $name")
            if (method != METHOD_STORED && method != METHOD_DEFLATE) fail("Unsupported compression in $name")
            if (size > limits.maxFileBytes) fail("$name is larger than ${limits.maxFileBytes.mb()} MB")
            total += size
            if (total > limits.maxExtractedBytes) fail("The package expands to more than ${(limits.maxExtractedBytes / MB).toInt()} MB")
            if (entries.size >= limits.maxFiles) fail("The package has more than ${limits.maxFiles} files")
            entries += Entry(name, rawName, method, crc, compressedSize.toInt(), size.toInt(), localOffset.toInt())
        }

        val files = LinkedHashMap<String, ByteArray>()
        entries.forEach { entry ->
            val local = entry.localOffset
            if (local < 0 || local + 30 > directoryOffset || u32(archive, local) != LOCAL_HEADER.toLong()) fail("Corrupt entry ${entry.name}")
            val nameLength = u16(archive, local + 26)
            val extraLength = u16(archive, local + 28)
            val dataStart = local + 30 + nameLength + extraLength
            val dataEnd = dataStart.toLong() + entry.compressedSize
            if (dataEnd > directoryOffset) fail("Corrupt entry ${entry.name}")
            if (!archive.copyOfRange(local + 30, local + 30 + nameLength).contentEquals(entry.rawName)) fail("Corrupt entry ${entry.name}")
            val stored = archive.copyOfRange(dataStart, dataEnd.toInt())
            val content = when (entry.method) {
                METHOD_STORED -> stored.also { if (it.size != entry.size) fail("Corrupt entry ${entry.name}") }
                else -> runCatching { inflater.inflate(stored, entry.size) }.getOrNull()
                    ?.takeIf { it.size == entry.size } ?: fail("Corrupt entry ${entry.name}")
            }
            if (Crc32.of(content) != entry.crc) fail("Checksum mismatch in ${entry.name}")
            files[entry.name] = content
        }
        return files
    }

    /** The package path rules: relative, `/`-separated, no empty, `.` or `..` segments, no backslashes or controls. */
    fun isSafePath(path: String): Boolean {
        if (path.isEmpty() || path.length > MmpkgLimits.MAX_PATH_LENGTH || path.startsWith("/") || '\\' in path) return false
        if (path.any { it.code < 0x20 || it.code == 0x7F || it == ':' }) return false
        return path.split('/').none { it.isEmpty() || it == "." || it == ".." }
    }

    private fun requireSafePath(path: String) {
        if (!isSafePath(path)) fail("Unsafe path in package: $path")
    }

    private fun findEnd(archive: ByteArray): Int {
        val lowest = maxOf(0, archive.size - 22 - 0xFFFF)
        var offset = archive.size - 22
        while (offset >= lowest) {
            if (u32(archive, offset) == END_OF_CENTRAL_DIRECTORY.toLong() && offset + 22 + u16(archive, offset + 20) == archive.size) {
                return offset
            }
            offset--
        }
        fail("Not a ZIP archive")
    }

    private class Entry(
        val name: String,
        val rawName: ByteArray,
        val method: Int,
        val crc: Long,
        val compressedSize: Int,
        val size: Int,
        val localOffset: Int,
    )

    private fun u16(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)

    private fun u32(data: ByteArray, offset: Int): Long =
        u16(data, offset).toLong() or (u16(data, offset + 2).toLong() shl 16)

    private fun fail(message: String): Nothing = throw MmpkgException(message)

    private const val MB = 1024 * 1024
    private fun Int.mb() = this / MB
}

/** CRC-32 (IEEE 802.3), as used by ZIP. */
object Crc32 {
    private val table = IntArray(256) { index ->
        var value = index
        repeat(8) { value = if (value and 1 != 0) (value ushr 1) xor 0xEDB88320.toInt() else value ushr 1 }
        value
    }

    fun of(data: ByteArray): Long {
        var crc = -1
        for (byte in data) crc = table[(crc xor byte.toInt()) and 0xFF] xor (crc ushr 8)
        return (crc.inv().toLong()) and 0xFFFFFFFFL
    }
}

/** SHA-256 (FIPS 180-4), for the package `files` table. */
object Sha256 {
    private val k = longArrayOf(
        0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
        0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
        0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
        0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
        0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
        0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
        0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
        0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2,
    ).map { it.toInt() }.toIntArray()

    fun digest(data: ByteArray): ByteArray {
        val h = longArrayOf(
            0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19,
        ).map { it.toInt() }.toIntArray()
        val bitLength = data.size.toLong() * 8
        val paddedSize = ((data.size + 9 + 63) / 64) * 64
        val message = data.copyOf(paddedSize)
        message[data.size] = 0x80.toByte()
        for (i in 0 until 8) message[paddedSize - 1 - i] = (bitLength ushr (8 * i)).toByte()
        val w = IntArray(64)
        for (block in 0 until paddedSize step 64) {
            for (t in 0 until 16) {
                val i = block + t * 4
                w[t] = ((message[i].toInt() and 0xFF) shl 24) or ((message[i + 1].toInt() and 0xFF) shl 16) or
                    ((message[i + 2].toInt() and 0xFF) shl 8) or (message[i + 3].toInt() and 0xFF)
            }
            for (t in 16 until 64) {
                val s0 = w[t - 15].rotateRight(7) xor w[t - 15].rotateRight(18) xor (w[t - 15] ushr 3)
                val s1 = w[t - 2].rotateRight(17) xor w[t - 2].rotateRight(19) xor (w[t - 2] ushr 10)
                w[t] = w[t - 16] + s0 + w[t - 7] + s1
            }
            var a = h[0]; var b = h[1]; var c = h[2]; var d = h[3]
            var e = h[4]; var f = h[5]; var g = h[6]; var hh = h[7]
            for (t in 0 until 64) {
                val t1 = hh + (e.rotateRight(6) xor e.rotateRight(11) xor e.rotateRight(25)) + ((e and f) xor (e.inv() and g)) + k[t] + w[t]
                val t2 = (a.rotateRight(2) xor a.rotateRight(13) xor a.rotateRight(22)) + ((a and b) xor (a and c) xor (b and c))
                hh = g; g = f; f = e; e = d + t1; d = c; c = b; b = a; a = t1 + t2
            }
            h[0] += a; h[1] += b; h[2] += c; h[3] += d; h[4] += e; h[5] += f; h[6] += g; h[7] += hh
        }
        return ByteArray(32) { (h[it / 4] ushr (24 - 8 * (it % 4))).toByte() }
    }

    fun hex(data: ByteArray): String = digest(data).joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
}
