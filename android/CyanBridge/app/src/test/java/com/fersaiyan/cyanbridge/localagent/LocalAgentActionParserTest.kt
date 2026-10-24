package com.fersaiyan.cyanbridge.localagent

import com.fersaiyan.cyanbridge.localagent.LocalAgentAction.Direction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalAgentActionParserTest {

    @Test
    fun `parses every action type with its fields`() {
        val json = """
            [
              {"type":"wait","ms":400},
              {"type":"click_coord","x":120,"y":640},
              {"type":"type_text","text":"hello","hint":"search"},
              {"type":"press_enter"},
              {"type":"scroll","direction":"down"},
              {"type":"open_app","app_name":"Settings"},
              {"type":"finish","message":"done"},
              {"type":"swipe","start_x":100,"start_y":500,"end_x":100,"end_y":200,"duration_ms":250},
              {"type":"long_press","x":540,"y":960,"duration_ms":800},
              {"type":"open_notifications"},
              {"type":"open_recents"},
              {"type":"make_call","number":"+15551234567"},
              {"type":"send_sms","number":"+15551234567","message":"Hello!"},
              {"type":"set_alarm","hour":7,"minute":30,"label":"Wake up"},
              {"type":"open_contacts"},
              {"type":"toggle_wifi"},
              {"type":"toggle_bluetooth"},
              {"type":"toggle_flashlight"},
              {"type":"send_email","to":"person@example.com","subject":"Hi","body":"Hello"},
              {"type":"read_screen_aloud"}
            ]
        """.trimIndent()

        assertEquals(
            listOf(
                LocalAgentAction.Wait(400),
                LocalAgentAction.ClickCoord(120, 640),
                LocalAgentAction.TypeText("hello", hint = "search"),
                LocalAgentAction.PressEnter,
                LocalAgentAction.Scroll(Direction.DOWN),
                LocalAgentAction.OpenApp("Settings"),
                LocalAgentAction.Finish("done"),
                LocalAgentAction.Swipe(100, 500, 100, 200, durationMs = 250),
                LocalAgentAction.LongPress(540, 960, durationMs = 800),
                LocalAgentAction.OpenNotifications,
                LocalAgentAction.OpenRecents,
                LocalAgentAction.MakeCall("+15551234567"),
                LocalAgentAction.SendSms("+15551234567", "Hello!"),
                LocalAgentAction.SetAlarm(7, 30, "Wake up"),
                LocalAgentAction.OpenContacts,
                LocalAgentAction.ToggleWifi,
                LocalAgentAction.ToggleBluetooth,
                LocalAgentAction.ToggleFlashlight,
                LocalAgentAction.SendEmail("person@example.com", "Hi", "Hello"),
                LocalAgentAction.ReadScreenAloud,
            ),
            LocalAgentActionParser.parseList(json),
        )
    }

    @Test
    fun `rejects actions with invalid or missing fields`() {
        listOf(
            """[{"type":"click_coord","x":"left","y":640}]""",
            """{"type":"set_alarm","hour":25,"minute":0}""",
            """{"type":"swipe","start_x":100,"start_y":500}""",
            """{"type":"send_sms","number":"","message":"Hello"}""",
        ).forEach { json ->
            assertTrue(json, LocalAgentActionParser.parseList(json).isEmpty())
        }
    }
}
