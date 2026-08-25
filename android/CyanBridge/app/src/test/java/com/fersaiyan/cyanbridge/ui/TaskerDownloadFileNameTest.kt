package com.fersaiyan.cyanbridge.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TaskerDownloadFileNameTest {
    @Test
    fun keepsTaskerProjectSuffixAfterUniqueId() {
        assertEquals(
            "Tasker_AI_123.prj.xml",
            taskerDownloadFileName("Tasker_AI.prj.xml", 123L),
        )
    }

    @Test
    fun givesGenericXmlAProjectSuffix() {
        assertEquals(
            "Tasker_AI_123.prj.xml",
            taskerDownloadFileName("Tasker_AI.xml", 123L),
        )
    }

    @Test
    fun acceptsTaskerProjectXml() {
        requireValidTaskerProject(
            "<TaskerData sr=\"\"><Project sr=\"proj0\"><name>CyanBridge</name></Project></TaskerData>",
        )
    }

    @Test
    fun rejectsHtmlDownload() {
        assertThrows(IllegalArgumentException::class.java) {
            requireValidTaskerProject("<!doctype html><html><body>Download</body></html>")
        }
    }

    @Test
    fun rejectsProfilePresentedAsProject() {
        assertThrows(IllegalArgumentException::class.java) {
            requireValidTaskerProject("<TaskerData><Profile sr=\"prof0\" /></TaskerData>")
        }
    }
}
