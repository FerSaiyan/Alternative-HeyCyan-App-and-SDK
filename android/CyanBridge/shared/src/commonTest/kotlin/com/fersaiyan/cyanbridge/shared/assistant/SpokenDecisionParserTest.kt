package com.fersaiyan.cyanbridge.shared.assistant

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.Test

class SpokenDecisionParserTest {
    @Test fun answerKeepsTheTextAfterTheTagAndDropsATrailingSecondTag() {
        assertEquals(
            SpokenDecision(SpokenRoute.ANSWER, "The socks are blue.", tagged = true),
            SpokenDecisionParser.parse("[answer] The socks are blue.\n[camera]"),
        )
    }

    @Test fun phoneAndSearchKeepTheirOnelinePayload() {
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

    @Test fun routesWithoutPayload() {
        for (route in listOf(SpokenRoute.CAMERA, SpokenRoute.LIVE, SpokenRoute.IGNORE)) {
            assertEquals(SpokenDecision(route, "", tagged = true), SpokenDecisionParser.parse("[${route.tag}]"))
        }
    }

    @Test fun anUntaggedOrUnknowntagReplyIsAnAnswer() {
        assertEquals(SpokenDecision(SpokenRoute.ANSWER, "It's Friday.", tagged = false), SpokenDecisionParser.parse("It's Friday."))
        assertFalse(SpokenDecisionParser.parse("[maybe] It's Friday.").tagged)
    }

    @Test fun streamingPrefixDetection() {
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

    @Test fun cleanRemovesTagsAnywhere() {
        assertEquals("Okay, see you soon.", SpokenDecisionParser.clean("Okay, see you soon.\n[camera]"))
    }

    @Test fun aLateTagWithoutPayloadStillDecides() {
        assertEquals(SpokenRoute.CAMERA, SpokenDecisionParser.parse("] [camera]").route)
        assertEquals(SpokenRoute.IGNORE, SpokenDecisionParser.parse("\"I'll be there in five.\"\n[ignore]").route)
        // A late [answer] or payload tag is not trusted: the whole reply is the answer.
        assertEquals(SpokenDecision(SpokenRoute.ANSWER, "Okay.", tagged = false), SpokenDecisionParser.parse("Okay. [answer]"))
    }

    @Test fun openingGeminiLiveIsTheLiveRoute() {
        assertEquals(SpokenDecision(SpokenRoute.LIVE, "", tagged = true), SpokenDecisionParser.parse("[phone] Open Gemini Live."))
        assertEquals(SpokenRoute.LIVE, SpokenDecisionParser.parse("[phone] Start live mode").route)
        assertEquals(SpokenRoute.PHONE, SpokenDecisionParser.parse("[phone] Open Spotify.").route)
    }
}
