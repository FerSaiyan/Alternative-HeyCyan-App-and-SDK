package com.fersaiyan.cyanbridge.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NightlyNoteEnricherTest {
    @Test
    fun `parses and normalizes bounded JSON tags`() {
        val tags = NightlyNoteEnricher.parseTags(
            "prefix {\"tags\":[\"Machine Learning\",\"#Project-X\",\"machine learning\",\"\"]} suffix",
        )

        assertEquals(listOf("machine-learning", "project-x"), tags)
    }

    @Test
    fun `content checkpoint changes when searchable note changes`() {
        val first = NightlyNoteEnricher.contentHash("Title", "Body", "tag")
        val same = NightlyNoteEnricher.contentHash("Title", "Body", "tag")
        val changed = NightlyNoteEnricher.contentHash("Title", "Updated body", "tag")

        assertEquals(first, same)
        assertNotEquals(first, changed)
    }
}
