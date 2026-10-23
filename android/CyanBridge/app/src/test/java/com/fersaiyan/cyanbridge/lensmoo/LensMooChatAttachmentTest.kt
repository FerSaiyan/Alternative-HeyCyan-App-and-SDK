package com.fersaiyan.cyanbridge.lensmoo

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class LensMooChatAttachmentTest {
    @Test fun originalJpegBytesPreservedAndDistinctFilesCreated() {
        val directory = Files.createTempDirectory("lensmoo-chat").toFile()
        try {
            val original = byteArrayOf(0xff.toByte(),0xd8.toByte(),1,2,3,0xff.toByte(),0xd9.toByte())
            val first=LensMooChatAttachment.copyOriginal(original.inputStream(),directory)
            val second=LensMooChatAttachment.copyOriginal(original.inputStream(),directory)
            assertArrayEquals(original,first.readBytes())
            assertNotEquals(first.name,second.name)
        } finally { directory.deleteRecursively() }
    }
    @Test fun invalidAttachmentLeavesNoPartialFile() {
        val directory = Files.createTempDirectory("lensmoo-chat").toFile()
        try {
            assertThrows(IllegalStateException::class.java) {
                LensMooChatAttachment.copyOriginal(byteArrayOf(1,2,3).inputStream(),directory)
            }
            assertTrue(directory.listFiles().orEmpty().isEmpty())
        } finally { directory.deleteRecursively() }
    }
}
