package com.fersaiyan.cyanbridge.lensmoo

import java.io.File
import java.io.InputStream
import java.util.UUID

/** Copies the captured original into the existing chat attachment lifetime, without re-encoding. */
object LensMooChatAttachment {
    const val MAX_JPEG_BYTES = 24 * 1024 * 1024

    fun copyOriginal(input: InputStream, directory: File): File {
        directory.mkdirs()
        val output = File(directory, "lensmoo_${UUID.randomUUID()}.jpg")
        try {
            input.use { source ->
                output.outputStream().use { target ->
                    val header = ByteArray(2)
                    java.io.DataInputStream(source).readFully(header)
                    check(header[0] == 0xff.toByte() && header[1] == 0xd8.toByte()) { "Invalid JPEG attachment" }
                    target.write(header)
                    val buffer = ByteArray(8192)
                    var total = 2
                    while (true) {
                        val count = source.read(buffer)
                        if (count < 0) break
                        total += count
                        check(total <= MAX_JPEG_BYTES) { "JPEG attachment is too large" }
                        target.write(buffer, 0, count)
                    }
                }
            }
            return output
        } catch (failure: Exception) {
            output.delete()
            throw failure
        }
    }
}
