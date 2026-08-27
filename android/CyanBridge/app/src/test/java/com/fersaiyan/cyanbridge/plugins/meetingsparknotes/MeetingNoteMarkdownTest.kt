package com.fersaiyan.cyanbridge.plugins.meetingsparknotes

import org.junit.Assert.assertTrue
import org.junit.Test

class MeetingNoteMarkdownTest {
    @Test
    fun rendersObsidianMetadataAndStructuredSummary() {
        val markdown = MeetingNoteMarkdown.render(
            summaryMarkdown = "# Planning Sync\n\n## Summary\n- Ship Friday",
            captureSessionId = 42L,
            createdAtMs = 0L,
        )

        assertTrue(markdown.startsWith("---\nsource: cyanbridge"))
        assertTrue(markdown.contains("type: meeting-note"))
        assertTrue(markdown.contains("capture_session_id: 42"))
        assertTrue(markdown.contains("tags: [meeting, meeting-spark-notes]"))
        assertTrue(markdown.contains("# Planning Sync\n\n## Summary\n- Ship Friday"))
    }
}
