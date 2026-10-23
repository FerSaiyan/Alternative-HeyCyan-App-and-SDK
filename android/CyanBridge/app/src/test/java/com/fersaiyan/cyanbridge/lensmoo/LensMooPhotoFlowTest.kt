package com.fersaiyan.cyanbridge.lensmoo

import org.junit.Assert.*
import org.junit.Test

class LensMooPhotoFlowTest {
    @Test fun originalTransferOnlyAfterCameraIsConfirmedIdle() {
        val calls=mutableListOf<String>(); var saved=false;var idle=false
        val bytes=LensMooPhotoFlow.capture(
            listPhotos={ calls+="list"; if(saved) setOf("old","new") else setOf("old") },
            shootAndWaitSaved={ calls+="shoot";saved=true },
            stopAndWaitIdle={ calls+="idle";idle=true },
            download={ name -> check(idle);assertEquals("new",name);calls+="download";byteArrayOf(1,2) },
        )
        assertArrayEquals(byteArrayOf(1,2),bytes)
        assertEquals(listOf("list","shoot","idle","list","download"),calls)
    }
    @Test fun failedIdleConfirmationPreventsMediaTransfer() {
        var lists=0;var downloads=0
        assertThrows(IllegalStateException::class.java) {
            LensMooPhotoFlow.capture({ lists++;emptySet() },{}, { error("idle timeout") }, { downloads++;byteArrayOf() })
        }
        assertEquals(1,lists);assertEquals(0,downloads)
    }
    @Test fun ambiguousNewFilesAreNeverDownloaded() {
        var lists=0;var downloads=0
        assertThrows(IllegalStateException::class.java) {
            LensMooPhotoFlow.capture({ if(lists++==0) emptySet() else setOf("a","b") },{}, {}, { downloads++;byteArrayOf() })
        }
        assertEquals(0,downloads)
    }
}
