package com.fersaiyan.cyanbridge.localagent.context

import org.junit.Assert.assertEquals
import org.junit.Test

class SpokenMemoryContextTest {
    @Test fun `seeded templates add nothing`() {
        assertEquals(
            "",
            SpokenMemoryContext.compact(
                userFacts = "# User Facts\n\n- Name: \n- Preferences: \n\n",
                confirmedDailyFacts = "# Confirmed daily facts (2026-10-25)\n\n- \n",
                dailySummary = "# Daily Summary (2026-10-25)\n\n(Generate from Settings -> View today's daily summary -> Regenerate)\n",
            ),
        )
    }

    @Test fun `real facts are listed under one header`() {
        assertEquals(
            "${SpokenMemoryContext.HEADER}\n- Name: Rafael\n- Wife: Ana.\n- Today: Dentist on Thursday at 3 pm.\n" +
                "- Today's summary: Worked on the glasses app.",
            SpokenMemoryContext.compact(
                userFacts = "# User Facts\n\n- Name: Rafael\n- Preferences: \nWife: Ana.\n",
                confirmedDailyFacts = "# Confirmed\n- Dentist on Thursday at 3 pm.\n",
                dailySummary = "# Daily Summary\n\nWorked on the\nglasses app.",
            ),
        )
    }

    @Test fun `the block stops at the cap`() {
        val block = SpokenMemoryContext.compact((1..100).joinToString("\n") { "- Fact number $it." }, "", "", maxChars = 120)
        assertEquals(true, block.length <= 120)
        assertEquals(true, Regex("- Fact number \\d+\\.").matches(block.lines().last()))
    }
}
