package com.fersaiyan.cyanbridge.notes

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NightlyNoteEnricherTest {
    @Test
    fun `parses and normalizes bounded JSON tags`() {
        val tags = NightlyNoteEnricher.parseTags(
            "prefix {\"tags\":[\"Machine Learning\",\"#Project-X\",\"machine learning\",\"\"]} suffix",
        )

        assertEquals(listOf("machine-learning", "project-x"), tags)
    }
}
