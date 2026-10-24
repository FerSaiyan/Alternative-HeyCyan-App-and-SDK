package com.fersaiyan.cyanbridge.localagent

import com.fersaiyan.cyanbridge.shared.json.optDouble
import com.fersaiyan.cyanbridge.shared.json.optInt
import com.fersaiyan.cyanbridge.shared.json.optLong
import com.fersaiyan.cyanbridge.shared.json.optString
import com.fersaiyan.cyanbridge.shared.json.parseJsonArray
import com.fersaiyan.cyanbridge.shared.json.parseJsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

object LocalAgentActionParser {

    fun parseList(json: String?): List<LocalAgentAction> {
        if (json.isNullOrBlank()) return emptyList()

        val trimmed = json.trim()
        return try {
            when {
                trimmed.startsWith("[") -> parseArray(parseJsonArray(trimmed))
                trimmed.startsWith("{") -> listOfNotNull(parseOne(parseJsonObject(trimmed)))
                else -> emptyList()
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun parseArray(arr: JsonArray): List<LocalAgentAction> {
        val out = ArrayList<LocalAgentAction>(arr.size)
        for (v in arr) {
            val obj = when {
                v is JsonObject -> v
                v is JsonPrimitive && v.isString -> runCatching { parseJsonObject(v.content) }.getOrNull()
                else -> null
            } ?: continue

            parseOne(obj)?.let(out::add)
        }
        return out
    }

    private fun parseOne(obj: JsonObject): LocalAgentAction? {
        val type = obj.optString("type", "").trim().lowercase()
        return when (type) {
            "sleep", "wait" -> LocalAgentAction.Wait(ms = obj.optLong("ms", 0L).coerceAtLeast(0L))
            "global_back", "back" -> LocalAgentAction.GlobalBack
            "global_home", "home" -> LocalAgentAction.GlobalHome
            "click_text" -> {
                val text = obj.optString("text", "")
                if (text.isBlank()) null else LocalAgentAction.ClickText(text)
            }
            "click_coord", "click_at" -> {
                if (!obj.containsKey("x") || !obj.containsKey("y")) return null
                val x = obj.optDouble("x", Double.NaN)
                val y = obj.optDouble("y", Double.NaN)
                if (!x.isFinite() || !y.isFinite()) {
                    null
                } else {
                    LocalAgentAction.ClickCoord(
                        x = x.toInt(),
                        y = y.toInt(),
                    )
                }
            }
            "type_text" -> {
                val text = obj.optString("text", "")
                val hint = obj.optString("hint", "").trim().ifBlank { null }
                if (text.isBlank()) null else LocalAgentAction.TypeText(text, hint)
            }
            "press_enter", "enter", "submit" -> LocalAgentAction.PressEnter
            "scroll" -> {
                when (obj.optString("direction", "").trim().lowercase()) {
                    "up" -> LocalAgentAction.Scroll(LocalAgentAction.Direction.UP)
                    "down" -> LocalAgentAction.Scroll(LocalAgentAction.Direction.DOWN)
                    else -> null
                }
            }
            "open_app" -> {
                val appName = obj.optString("app_name", "")
                if (appName.isBlank()) null else LocalAgentAction.OpenApp(appName)
            }
            "finish", "done" -> {
                val message = obj.optString("message", "").trim().ifBlank { null }
                LocalAgentAction.Finish(message)
            }
            "swipe" -> {
                if (!obj.containsKey("start_x") || !obj.containsKey("start_y") || !obj.containsKey("end_x") || !obj.containsKey("end_y")) return null
                val sx = obj.optDouble("start_x", Double.NaN)
                val sy = obj.optDouble("start_y", Double.NaN)
                val ex = obj.optDouble("end_x", Double.NaN)
                val ey = obj.optDouble("end_y", Double.NaN)
                if (!sx.isFinite() || !sy.isFinite() || !ex.isFinite() || !ey.isFinite()) null
                else LocalAgentAction.Swipe(
                    startX = sx.toInt(),
                    startY = sy.toInt(),
                    endX = ex.toInt(),
                    endY = ey.toInt(),
                    durationMs = obj.optLong("duration_ms", 300L).coerceIn(50L, 5000L),
                )
            }
            "long_press" -> {
                if (!obj.containsKey("x") || !obj.containsKey("y")) return null
                val x = obj.optDouble("x", Double.NaN)
                val y = obj.optDouble("y", Double.NaN)
                if (!x.isFinite() || !y.isFinite()) null
                else LocalAgentAction.LongPress(
                    x = x.toInt(),
                    y = y.toInt(),
                    durationMs = obj.optLong("duration_ms", 1000L).coerceIn(300L, 5000L),
                )
            }
            "open_notifications", "notifications" -> LocalAgentAction.OpenNotifications
            "open_recents", "recents" -> LocalAgentAction.OpenRecents
            "make_call", "call" -> {
                val number = obj.optString("number", "").trim()
                if (number.isBlank()) null else LocalAgentAction.MakeCall(number)
            }
            "send_sms", "sms" -> {
                val number = obj.optString("number", "").trim()
                val message = obj.optString("message", "").trim()
                if (number.isBlank() || message.isBlank()) null
                else LocalAgentAction.SendSms(number, message)
            }
            "set_alarm", "alarm" -> {
                val hour = obj.optInt("hour", -1)
                val minute = obj.optInt("minute", -1)
                if (hour !in 0..23 || minute !in 0..59) null
                else LocalAgentAction.SetAlarm(
                    hour = hour,
                    minute = minute,
                    label = obj.optString("label", "").trim().ifBlank { null },
                )
            }
            "open_contacts", "contacts" -> LocalAgentAction.OpenContacts
            "toggle_wifi" -> LocalAgentAction.ToggleWifi
            "toggle_bluetooth" -> LocalAgentAction.ToggleBluetooth
            "toggle_flashlight" -> LocalAgentAction.ToggleFlashlight
            "send_email", "email" -> {
                val to = obj.optString("to", "").trim()
                if (to.isBlank()) null else LocalAgentAction.SendEmail(
                    to = to,
                    subject = obj.optString("subject", ""),
                    body = obj.optString("body", ""),
                )
            }
            "read_screen_aloud", "read_screen", "speak_screen" -> LocalAgentAction.ReadScreenAloud
            else -> null
        }
    }
}
