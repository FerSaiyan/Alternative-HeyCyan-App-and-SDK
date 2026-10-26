package com.fersaiyan.cyanbridge.shared.devices.mentra

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Response shapes from the MentraOS app's gallery client (asgCameraApi.ts, types/asg.ts). */
class MentraGalleryTest {
    @Test
    fun v2SyncCapturesUseThePrimaryFileAndCaptureTimestamp() {
        val (protocol, items) = MentraGalleryParser.listing(
            """
            {"status":"success","data":{"api_version":2,"client_id":"cyanbridge","server_time":1740926000000,
             "captures":[
              {"capture_id":"IMG_20250302_143022_456_123","type":"photo","timestamp":1740925822456,"total_size":9,
               "files":[{"name":"IMG_20250302_143022_456_123/ev-2.jpg","size":4,"role":"bracket"},
                        {"name":"IMG_20250302_143022_456_123/base.jpg","size":5,"role":"primary"}]},
              {"capture_id":"VID_20250302_143100_001","type":"video","timestamp":1740925860,
               "files":[{"name":"VID_20250302_143100_001/video.mp4","size":2048,"role":"primary"}]}],
             "changed_files":[],"deleted_files":[]}}
            """.trimIndent(),
        )
        assertEquals(MentraGalleryProtocol.V2_CAPTURES, protocol)
        assertEquals(
            listOf(
                MentraGalleryItem(
                    "IMG_20250302_143022_456_123", "IMG_20250302_143022_456_123/base.jpg", 5,
                    MentraMediaKind.PHOTO, 1740925822456,
                ),
                MentraGalleryItem(
                    "VID_20250302_143100_001", "VID_20250302_143100_001/video.mp4", 2048,
                    MentraMediaKind.VIDEO, 1740925860000,
                ),
            ),
            items,
        )
    }

    @Test
    fun legacyFilesAndV3ManifestPagesParse() {
        val (protocol, legacy) = MentraGalleryParser.listing(
            """{"status":"success","data":{"photos":[
                {"name":"IMG_1.jpg","size":10,"modified":"1740925822456","is_video":false},
                {"name":"clip","size":20,"mime_type":"video/mp4"},
                {"name":"IMG_2.avif","size":30}]}}""",
        )
        assertEquals(MentraGalleryProtocol.LEGACY_FILES, protocol)
        assertEquals(listOf("IMG_1.jpg", "clip", "IMG_2.avif"), legacy.map { it.remoteFile })
        assertEquals(listOf(MentraMediaKind.PHOTO, null, null), legacy.map { it.kind })
        assertEquals(1740925822456, legacy.first().takenAtMs)

        assertEquals(
            50,
            MentraGalleryParser.capabilities("""{"data":{"api_version":3,"max_manifest_page_size":50}}""")
                ?.maxManifestPageSize,
        )
        assertNull(MentraGalleryParser.capabilities("""{"api_version":2}"""))
        val page = MentraGalleryParser.manifestPage(
            """{"status":"success","data":{"api_version":3,"has_more":true,"next_cursor":"c2","captures":[
                {"capture_id":"IMG_A","type":"photo","timestamp":1,"files":[
                 {"name":"IMG_A/base.jpg","size":7,"role":"primary","etag":"\"7-1\""}]}]}}""",
        )
        assertEquals("c2", page.nextCursor)
        assertEquals("\"7-1\"", page.items.single().etag)
    }

    @Test
    fun planSkipsWhatThePhoneHasAndOnlyOffersVerifiedFilesForDeletion() {
        val photo = MentraGalleryItem("IMG_20250302_143022_456_123", "IMG_20250302_143022_456_123/base.jpg", 5, MentraMediaKind.PHOTO, 1)
        val video = MentraGalleryItem("VID_X", "VID_X/video.mp4", 9, MentraMediaKind.VIDEO, 2)
        val legacy = MentraGalleryItem("IMG 7.jpg", "IMG 7.jpg", 3, MentraMediaKind.PHOTO, null)
        val resized = MentraGalleryItem("IMG_R", "IMG_R/base.jpg", 4, MentraMediaKind.PHOTO, null)
        val unsupported = MentraGalleryItem("IMG_H", "IMG_H/base.heic", 4, null, null)
        assertEquals("MentraLive_IMG_20250302_143022_456_123.jpg", MentraGallerySyncPlanner.localFileName(photo))
        assertEquals("MentraLive_IMG_7.jpg", MentraGallerySyncPlanner.localFileName(legacy))

        val plan = MentraGallerySyncPlanner.plan(
            listOf(photo, video, legacy, legacy, resized, unsupported),
            phoneGallery = mapOf("mentralive_img_7.jpg" to 3L, "mentralive_img_r.jpg" to 99L),
        )
        assertEquals(listOf(photo, video), plan.downloads.map { it.item })
        assertEquals(listOf(legacy, resized), plan.alreadyOnPhone.map { it.item })
        assertEquals(listOf(unsupported), plan.unsupported)
        // The video download failed verification; the resized copy on the phone does not match.
        assertEquals(
            listOf(photo, legacy),
            plan.deletable(setOf("MentraLive_IMG_20250302_143022_456_123.jpg")),
        )
        assertTrue(MentraGallerySyncPlanner.isCompleteDownload(5, 5))
        assertTrue(!MentraGallerySyncPlanner.isCompleteDownload(5, 4))
    }

    @Test
    fun urlsAndDeleteRepliesMatchTheGlassesServer() {
        val base = MentraGalleryApi.baseUrl("192.168.43.1")
        assertEquals(
            "http://192.168.43.1:8089/api/download?file=IMG_1%2Fbase%20%C3%A9.jpg",
            MentraGalleryApi.downloadUrl(base, "IMG_1/base é.jpg"),
        )
        assertEquals("""{"files":["IMG_1","a.jpg"]}""", MentraGalleryApi.deleteFilesBody(listOf("IMG_1", "a.jpg")))
        assertEquals(
            setOf("IMG_1"),
            MentraGalleryParser.deletedFiles(
                """{"status":"success","data":{"results":[{"file":"IMG_1","success":true},{"file":"a.jpg","success":false}]}}""",
            ),
        )
        assertTrue(MentraGalleryParser.ackAccepted("""{"data":{"success":false,"already_trashed":true}}"""))
    }
}
