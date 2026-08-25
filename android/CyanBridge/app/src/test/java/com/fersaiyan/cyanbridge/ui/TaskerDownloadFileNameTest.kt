package com.fersaiyan.cyanbridge.ui

import org.junit.Assert.assertEquals
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
}
