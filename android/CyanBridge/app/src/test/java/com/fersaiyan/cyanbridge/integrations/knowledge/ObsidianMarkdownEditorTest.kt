package com.fersaiyan.cyanbridge.integrations.knowledge

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.fersaiyan.cyanbridge.shared.ui.notes.MarkdownEditorActions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ObsidianMarkdownEditorTest {
    @Test
    fun renderAndParsePreserveManagedMetadata() {
        val rendered = ObsidianMarkdownCodec.render(
            ObsidianManagedDraft(
                title = "Project plan",
                tags = "#cyanbridge, research research",
                body = "- [ ] Ship it\n- [x] Test it",
                createdAt = "2026-08-20 10:00",
            ),
            now = "2026-08-26 01:30",
        )

        assertTrue(rendered.contains("created: \"2026-08-20 10:00\""))
        assertTrue(rendered.contains("updated: \"2026-08-26 01:30\""))
        assertTrue(rendered.contains("tags: [cyanbridge, research]"))

        val parsed = ObsidianMarkdownCodec.parse("Project plan.md", rendered)
        assertEquals("Project plan", parsed.title)
        assertEquals("cyanbridge, research", parsed.tags)
        assertEquals("2026-08-20 10:00", parsed.createdAt)
        assertTrue(parsed.body.contains("- [ ] Ship it"))
    }

    @Test
    fun wrapsSelectedMarkdownText() {
        val original = TextFieldValue("hello world", selection = TextRange(6, 11))
        val bold = MarkdownEditorActions.wrap(original, "**", "**", "bold")
        assertEquals("hello **world**", bold.text)
    }

    @Test
    fun prefixesCurrentLineForTaskLists() {
        val original = TextFieldValue("first\nsecond", selection = TextRange(9))
        val task = MarkdownEditorActions.prefixCurrentLine(original, "- [ ] ")
        assertEquals("first\n- [ ] second", task.text)
    }
}
