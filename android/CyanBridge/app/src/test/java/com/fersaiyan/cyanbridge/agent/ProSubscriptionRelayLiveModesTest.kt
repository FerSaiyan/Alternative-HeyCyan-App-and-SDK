package com.fersaiyan.cyanbridge.agent

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ProSubscriptionRelayLiveModesTest {
    @Test
    fun parsesServerAdvertisedLiveModesAndMultipliers() {
        val payload = JSONObject(
            """
            {
              "live_modes": [
                {
                  "id": "economy",
                  "label": "Gemini Live Economy",
                  "description": "Economy from server",
                  "quota_multiplier": 9
                },
                {
                  "id": "private",
                  "label": "Gemini Live Private",
                  "description": "Private from server",
                  "quota_multiplier": 41
                }
              ]
            }
            """.trimIndent(),
        )

        val modes = ProSubscriptionRelayClient.resolveLiveModes(
            ProSubscriptionRelayClient.parseLiveModes(payload),
        )

        assertEquals(listOf("economy", "private"), modes.map { it.id })
        assertEquals("Gemini Live Economy · 9×", modes[0].displayLabel)
        assertEquals("Economy from server", modes[0].description)
        assertEquals("Gemini Live Private · 41×", modes[1].displayLabel)
        assertEquals("Private from server", modes[1].description)
    }

    @Test
    fun fillsMissingLiveModeFromLocalFallbackForOlderRelays() {
        val modes = ProSubscriptionRelayClient.resolveLiveModes(
            listOf(
                ProSubscriptionRelayClient.LiveModeOption(
                    id = "economy",
                    label = "Economy",
                    description = "Server economy",
                    quotaMultiplier = 8,
                ),
            ),
        )

        assertEquals(8, modes[0].quotaMultiplier)
        assertEquals("private", modes[1].id)
        assertEquals(36, modes[1].quotaMultiplier)
    }
}
