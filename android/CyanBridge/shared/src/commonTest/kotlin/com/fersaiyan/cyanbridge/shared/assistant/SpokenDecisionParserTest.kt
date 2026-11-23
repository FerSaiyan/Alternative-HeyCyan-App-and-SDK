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

    @Test fun withThePhotoAttachedCameraIsAnAnswerAboutIt() {
        // Host eval 2026-11-23: with the photo attached Gemma still often writes [camera], then the answer.
        assertEquals(
            SpokenDecision(SpokenRoute.ANSWER, "The socks are yellow.", tagged = true, askedCamera = true),
            SpokenDecisionParser.parse("[camera]\n[answer] The socks are yellow.", photoAttached = true),
        )
        assertEquals(SpokenDecision(SpokenRoute.ANSWER, "", tagged = true, askedCamera = true), SpokenDecisionParser.parse("[camera]", photoAttached = true))
        assertFalse(SpokenDecisionParser.isComplete("[camera]", photoAttached = true))
        // Without the photo nothing changes, and the other routes are the same with it.
        assertEquals(SpokenRoute.CAMERA, SpokenDecisionParser.parse("[camera]").route)
        assertEquals(SpokenRoute.SEARCH, SpokenDecisionParser.parse("[search] bus 24 route\n", photoAttached = true).route)
        assertTrue(SpokenDecisionParser.isComplete("[ignore]", photoAttached = true))
        assertEquals(SpokenRoute.ANSWER, SpokenDecisionParser.parse("They are yellow. [camera]", photoAttached = true).route)
    }

    @Test fun theSpeakablePrefixOnlyGrowsAsTheReplyStreams() {
        val reply = "[camera]\n[answer] The socks are yellow.\nThey look new. [camera] Anything else?"
        var spoken = ""
        for (end in 1..reply.length) {
            val now = SpokenDecisionParser.speakablePrefix(reply.substring(0, end))
            assertTrue(now.startsWith(spoken), "'$now' does not extend '$spoken'")
            spoken = now
        }
        assertEquals("The socks are yellow.\nThey look new. Anything else?", spoken)
        assertEquals("", SpokenDecisionParser.speakablePrefix("[answ"))
        assertEquals("Twelve", SpokenDecisionParser.speakablePrefix("[answer] Twelve [ca"))
        assertEquals("It's [1] of 3", SpokenDecisionParser.speakablePrefix("[answer] It's [1] of 3"))
    }
}
