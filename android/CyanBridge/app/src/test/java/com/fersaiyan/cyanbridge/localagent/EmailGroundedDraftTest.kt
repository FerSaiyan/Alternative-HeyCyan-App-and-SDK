package com.fersaiyan.cyanbridge.localagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EmailGroundedDraftTest {
    private val goal = "Open Chrome, search for 'latest smartglasses news', open the first result, " +
        "read the article, and prepare an email to ana@example.com with the exact subject 'Article summary'. " +
        "Say this is an automated fixture summary and ask approval before sending."

    private fun observation(article: Boolean): LocalAgentObservation {
        val title = if (article) "CYANBRIDGE_HIL_NEWS_ARTICLE — Cobalt Horizon 88417 smartglasses" else "News results"
        val text = if (article) "The Cobalt Horizon 88417 smartglasses pack 42 sensors and run for eight hours on a single charge." else "Cobalt Horizon 88417 smartglasses — first result"
        return LocalAgentObservation(
            createdAtMs = 1L, packageName = "com.android.chrome",
            screenText = "$title $text",
            screenSnapshot = LocalAgentScreenSnapshot(
                packageName = "com.android.chrome", textSummary = "$title $text",
                nodes = listOf(title, text).mapIndexed { i, label ->
                    LocalAgentScreenNode(
                        index = i, depth = 0, text = label, contentDescription = "", className = "TextView",
                        viewId = "", isClickable = false, isEditable = false, isScrollable = false,
                        bounds = LocalAgentNodeBounds(0, 0, 100, 50),
                    )
                },
            ),
        )
    }

    @Test fun `email prose is requested only from the opened article`() {
        assertNull(EmailGroundedDraft.from(goal, observation(article = false)))
        val request = EmailGroundedDraft.from(goal, observation(article = true))
        assertNotNull(request)
        assertEquals("ana@example.com", request!!.to)
        assertEquals("Article summary", request.subject)
        assertTrue(request.evidence.contains("42 sensors"))
        assertFalse(request.prompt.user.contains("News results"))
        assertTrue(request.prompt.user.length < 1_400)
    }

    @Test fun `draft cannot change recipient or subject and must use observed facts`() {
        val request = EmailGroundedDraft.from(goal, observation(article = true))!!
        val body = "This automated fixture summary covers the Cobalt Horizon 88417 smartglasses, " +
            "which have 42 sensors and can run for eight hours on a single charge."
        fun json(to: String, subject: String, text: String) =
            """{"action":"send_email","params":{"to":"$to","subject":"$subject","body":"$text"},"is_complete":false}"""
        assertEquals(
            LocalAgentAction.SendEmail(request.to, request.subject, body),
            EmailGroundedDraft.parse(json(request.to, request.subject, body), request),
        )
        assertThrows(IllegalArgumentException::class.java) {
            EmailGroundedDraft.parse(json("other@example.com", request.subject, body), request)
        }
        assertThrows(IllegalArgumentException::class.java) {
            EmailGroundedDraft.parse(json(request.to, "Changed subject", body), request)
        }
        assertThrows(IllegalArgumentException::class.java) {
            EmailGroundedDraft.parse(json(request.to, request.subject, "An ungrounded generic message that contains plenty of words but no article facts at all."), request)
        }
    }
}
