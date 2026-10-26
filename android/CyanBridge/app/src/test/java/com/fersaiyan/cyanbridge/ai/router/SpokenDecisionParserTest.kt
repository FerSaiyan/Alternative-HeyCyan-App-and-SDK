package com.fersaiyan.cyanbridge.ai.router

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpokenDecisionParserTest {
    @Test fun `answer keeps the text after the tag and drops a trailing second tag`() {
        assertEquals(
            SpokenDecision(SpokenRoute.ANSWER, "The socks are blue.", tagged = true),
            SpokenDecisionParser.parse("[answer] The socks are blue.\n[camera]"),
        )
    }

    @Test fun `phone and search keep their one-line payload`() {
        assertEquals(
            SpokenDecision(SpokenRoute.PHONE, "Open Spotify and play We Are the Champions.", tagged = true),
            SpokenDecisionParser.parse("[phone] Open Spotify and play We Are the Champions.\nOkay, playing it now."),
        )
        assertEquals("rain forecast tomorrow", SpokenDecisionParser.parse(" [Search]  rain forecast tomorrow").payload)
        // Host eval: the model often answers on the same line after the query.
        assertEquals(
            "next public holiday in Brazil",
            SpokenDecisionParser.parse("[search] next public holiday in Brazil [answer] The next public holiday is").payload,
        )
    }

    @Test fun `routes without payload`() {
        for (route in listOf(SpokenRoute.CAMERA, SpokenRoute.LIVE, SpokenRoute.IGNORE)) {
            assertEquals(SpokenDecision(route, "", tagged = true), SpokenDecisionParser.parse("[${route.tag}]"))
        }
    }

    @Test fun `an untagged or unknown-tag reply is an answer`() {
        assertEquals(SpokenDecision(SpokenRoute.ANSWER, "It's Friday.", tagged = false), SpokenDecisionParser.parse("It's Friday."))
        assertFalse(SpokenDecisionParser.parse("[maybe] It's Friday.").tagged)
    }

    @Test fun `streaming prefix detection`() {
        assertNull(SpokenDecisionParser.routeOf("[cam"))
        assertFalse(SpokenDecisionParser.isComplete("[cam"))
        assertEquals(SpokenRoute.CAMERA, SpokenDecisionParser.routeOf("[camera"+"]"))
        assertTrue(SpokenDecisionParser.isComplete("[camera]"))
        assertTrue(SpokenDecisionParser.isComplete("[live]"))
        assertFalse(SpokenDecisionParser.isComplete("[phone] Open Spot"))
        assertTrue(SpokenDecisionParser.isComplete("[phone] Open Spotify.\n"))
        assertTrue(SpokenDecisionParser.isComplete("[search] weather today [answer]"))
        assertFalse(SpokenDecisionParser.isComplete("[answer] The capital is Canberra.\n"))
        // An unknown tag or no tag is answered in full.
        assertFalse(SpokenDecisionParser.isComplete("[maybe] It's"))
        assertFalse(SpokenDecisionParser.isComplete("It's Friday."))
    }

    @Test fun `clean removes tags anywhere`() {
        assertEquals("Okay, see you soon.", SpokenDecisionParser.clean("Okay, see you soon.\n[camera]"))
    }

    @Test fun `a late tag without payload still decides`() {
        assertEquals(SpokenRoute.CAMERA, SpokenDecisionParser.parse("] [camera]").route)
        assertEquals(SpokenRoute.IGNORE, SpokenDecisionParser.parse("\"I'll be there in five.\"\n[ignore]").route)
        // A late [answer] or payload tag is not trusted: the whole reply is the answer.
        assertEquals(SpokenDecision(SpokenRoute.ANSWER, "Okay.", tagged = false), SpokenDecisionParser.parse("Okay. [answer]"))
    }

    @Test fun `opening Gemini Live is the live route`() {
        assertEquals(SpokenDecision(SpokenRoute.LIVE, "", tagged = true), SpokenDecisionParser.parse("[phone] Open Gemini Live."))
        assertEquals(SpokenRoute.LIVE, SpokenDecisionParser.parse("[phone] Start live mode").route)
        assertEquals(SpokenRoute.PHONE, SpokenDecisionParser.parse("[phone] Open Spotify.").route)
    }
}
