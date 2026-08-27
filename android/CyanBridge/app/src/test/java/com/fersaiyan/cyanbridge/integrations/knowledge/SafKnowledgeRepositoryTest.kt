package com.fersaiyan.cyanbridge.integrations.knowledge

import org.junit.Assert.assertEquals
import org.junit.Test

class SafKnowledgeRepositoryTest {
    @Test
    fun sanitizesMeetingNoteFilenameForDocumentProviders() {
        assertEquals(
            "Planning - launch - review",
            SafKnowledgeRepository.sanitizeFileTitle(" Planning / launch : review "),
        )
    }
}
