package com.fersaiyan.cyanbridge.localagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocalAgentUiControlProtocolTest {

    @Test
    fun `parses click text decision`() {
        val raw = """
            {
              "action": "click_text",
              "params": {"text": "Search"},
              "reasoning": "Tap the search field first.",
              "is_complete": false
            }
        """.trimIndent()

        val parsed = LocalAgentUiControlProtocol.parseDecision(raw)

        assertEquals("Tap the search field first.", parsed.reasoning)
        assertTrue(parsed.action is LocalAgentUiControlProtocol.ClickText)
        assertEquals("Search", (parsed.action as LocalAgentUiControlProtocol.ClickText).text)
        assertEquals(false, parsed.isComplete)
    }

    @Test
    fun `extracts fenced json and parses click coord`() {
        val raw = """
            Here's the next action.

            ```json
            {"action":"click_at","params":{"x":540,"y":960},"reasoning":"The icon has no label.","is_complete":false}
            ```
        """.trimIndent()

        val parsed = LocalAgentUiControlProtocol.parseDecision(raw)
        val action = parsed.action as LocalAgentUiControlProtocol.ClickCoord

        assertEquals(540, action.x)
        assertEquals(960, action.y)
    }

    @Test
    fun `build prompt includes structured observation`() {
        val observation = LocalAgentObservation(
            createdAtMs = 123L,
            packageName = "com.android.settings",
            screenText = "Settings\nBluetooth",
            screenSnapshot = LocalAgentScreenSnapshot(
                packageName = "com.android.settings",
                textSummary = "Settings\nBluetooth",
                nodes = listOf(
                    LocalAgentScreenNode(
                        index = 0,
                        depth = 0,
                        text = "Bluetooth",
                        contentDescription = "",
                        className = "Switch",
                        viewId = "android:id/switch_widget",
                        isClickable = true,
                        isEditable = false,
                        isScrollable = false,
                        bounds = LocalAgentNodeBounds(10, 20, 110, 70),
                    )
                ),
            ),
        )

        val prompt = LocalAgentUiControlProtocol.buildPrompt(
            LocalAgentUiControlProtocol.StepContext(
                goal = "Turn on Bluetooth",
                observation = observation,
                stepIndex = 1,
                maxSteps = 8,
                previousActionResult = "Opened Settings",
                consecutiveFailures = 3,
            )
        )

        assertTrue(prompt.user.contains("TASK:"))
        assertTrue(prompt.user.contains("Turn on Bluetooth"))
        assertTrue(prompt.user.contains("APP: com.android.settings"))
        assertTrue(prompt.user.contains("Bluetooth"))
        assertTrue(prompt.user.contains("center:(60,45)"))
        assertTrue(prompt.user.contains("WARNING:"))
    }

    @Test
    fun `rejects unsupported actions and invalid params`() {
        listOf(
            """{"action":"delete_everything","params":{},"is_complete":false}""",
            """{"action":"click_at","params":{"x":"left","y":200},"is_complete":false}""",
            """{"action":"set_alarm","params":{"hour":25,"minute":0},"is_complete":false}""",
            """{"action":"swipe","params":{"start_x":100,"start_y":500},"is_complete":false}""",
            """{"action":"send_sms","params":{"number":"","message":"Hello"},"is_complete":false}""",
        ).forEach { raw ->
            assertThrows(raw, LocalAgentUiControlProtocol.SchemaViolationException::class.java) {
                LocalAgentUiControlProtocol.parseDecision(raw)
            }
        }
    }

    @Test
    fun `parses every phone action decision with its params`() {
        listOf(
            """{"action":"swipe","params":{"start_x":540,"start_y":1500,"end_x":540,"end_y":500,"duration_ms":300},"reasoning":"Swipe up to scroll.","is_complete":false}"""
                to LocalAgentUiControlProtocol.Swipe(540, 1500, 540, 500, durationMs = 300),
            """{"action":"long_press","params":{"x":300,"y":600,"duration_ms":1200},"reasoning":"Long press for context menu.","is_complete":false}"""
                to LocalAgentUiControlProtocol.LongPress(300, 600, durationMs = 1200),
            """{"action":"open_notifications","params":{},"reasoning":"Check notifications.","is_complete":false}"""
                to LocalAgentUiControlProtocol.OpenNotifications,
            """{"action":"open_recents","params":{},"reasoning":"Switch apps.","is_complete":false}"""
                to LocalAgentUiControlProtocol.OpenRecents,
            """{"action":"make_call","params":{"number":"+15551234567"},"reasoning":"Call the contact.","is_complete":false}"""
                to LocalAgentUiControlProtocol.MakeCall("+15551234567"),
            """{"action":"send_sms","params":{"number":"+15551234567","message":"On my way"},"reasoning":"Send text.","is_complete":false}"""
                to LocalAgentUiControlProtocol.SendSms("+15551234567", "On my way"),
            """{"action":"press_enter","params":{},"is_complete":false}"""
                to LocalAgentUiControlProtocol.PressEnter,
            """{"action":"send_email","params":{"to":"person@example.com","subject":"Hi","body":"Hello"},"is_complete":false}"""
                to LocalAgentUiControlProtocol.SendEmail("person@example.com", "Hi", "Hello"),
            """{"action":"set_alarm","params":{"hour":7,"minute":30,"label":"Wake up"},"reasoning":"Set morning alarm.","is_complete":false}"""
                to LocalAgentUiControlProtocol.SetAlarm(7, 30, "Wake up"),
            """{"action":"open_contacts","params":{},"reasoning":"Open contacts.","is_complete":false}"""
                to LocalAgentUiControlProtocol.OpenContacts,
            """{"action":"toggle_wifi","params":{},"reasoning":"Toggle wifi.","is_complete":false}"""
                to LocalAgentUiControlProtocol.ToggleWifi,
            """{"action":"toggle_bluetooth","params":{},"reasoning":"Toggle bluetooth.","is_complete":false}"""
                to LocalAgentUiControlProtocol.ToggleBluetooth,
            """{"action":"toggle_flashlight","params":{},"reasoning":"Toggle flashlight.","is_complete":false}"""
                to LocalAgentUiControlProtocol.ToggleFlashlight,
        ).forEach { (raw, expected) ->
            assertEquals(raw, expected, LocalAgentUiControlProtocol.parseDecision(raw).action)
        }

        val read = LocalAgentUiControlProtocol.parseDecision(
            """{"action":"read_screen_aloud","params":{},"is_complete":true}""",
        )
        assertTrue(read.action is LocalAgentUiControlProtocol.ReadScreenAloud)
        assertTrue(read.isComplete)
    }

    @Test
    fun `parses action aliases`() {
        val click = LocalAgentUiControlProtocol.parseDecision(
            """{"action":"click","params":{"text":"OK"},"is_complete":false}"""
        )
        assertTrue(click.action is LocalAgentUiControlProtocol.ClickText)

        val back = LocalAgentUiControlProtocol.parseDecision(
            """{"action":"back","params":{},"is_complete":false}"""
        )
        assertTrue(back.action is LocalAgentUiControlProtocol.PressBack)

        val done = LocalAgentUiControlProtocol.parseDecision(
            """{"action":"done","params":{},"is_complete":true}"""
        )
        assertTrue(done.action is LocalAgentUiControlProtocol.Finish)
        assertTrue(done.isComplete)
    }

    @Test
    fun `parses open app with various param names`() {
        val p1 = LocalAgentUiControlProtocol.parseDecision(
            """{"action":"open_app","params":{"app_name":"WhatsApp"},"is_complete":false}"""
        )
        assertEquals("WhatsApp", (p1.action as LocalAgentUiControlProtocol.OpenApp).appName)

        val p2 = LocalAgentUiControlProtocol.parseDecision(
            """{"action":"open_app","params":{"package_name":"com.whatsapp"},"is_complete":false}"""
        )
        assertEquals("com.whatsapp", (p2.action as LocalAgentUiControlProtocol.OpenApp).appName)
    }

    @Test
    fun `handles truncated json by closing brace`() {
        val raw = """{"action":"press_back","params":{},"reasoning":"Go back","is_complete":false"""
        val parsed = LocalAgentUiControlProtocol.parseDecision(raw)
        assertTrue(parsed.action is LocalAgentUiControlProtocol.PressBack)
    }
}
