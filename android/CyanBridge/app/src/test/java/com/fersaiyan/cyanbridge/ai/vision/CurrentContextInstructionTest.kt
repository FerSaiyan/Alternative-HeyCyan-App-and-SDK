package com.fersaiyan.cyanbridge.ai.vision

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class CurrentContextInstructionTest {
    @Test fun `states weekday, date, time, zone and region`() {
        val now = ZonedDateTime.of(2026, 10, 23, 14, 5, 0, 0, ZoneId.of("America/Sao_Paulo"))
        assertEquals(
            "Current local time: Friday 2026-10-23 14:05 (America/Sao_Paulo), device region BR.",
            ImageQuestionDefaults.currentContextInstruction(now, "BR"),
        )
        assertEquals(
            "Current local time: Friday 2026-10-23 14:05 (America/Sao_Paulo).",
            ImageQuestionDefaults.currentContextInstruction(now, ""),
        )
    }
}
