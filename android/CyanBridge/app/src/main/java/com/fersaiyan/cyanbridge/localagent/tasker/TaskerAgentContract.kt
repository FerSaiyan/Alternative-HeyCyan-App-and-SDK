package com.fersaiyan.cyanbridge.localagent.tasker

import com.fersaiyan.cyanbridge.localagent.LocalAgentAction
import com.fersaiyan.cyanbridge.localagent.LocalAgentNodeBounds
import com.fersaiyan.cyanbridge.localagent.LocalAgentObservation
import com.fersaiyan.cyanbridge.localagent.LocalAgentScreenNode
import com.fersaiyan.cyanbridge.localagent.LocalAgentScreenSnapshot
import org.json.JSONArray
import org.json.JSONObject

/**
 * Versioned IPC contract between CyanBridge (brain) and a user-owned Tasker workflow
 * (screen observer/executor). Tasker does not classify or cancel actions; it reports
 * execution results back to CyanBridge so policy and debugging remain centralized here.
 */
object TaskerAgentContract {
    const val VERSION = 1

    const val ACTION_OBSERVE = "com.fersaiyan.cyanbridge.TASKER_AGENT_OBSERVE"
    const val ACTION_EXECUTE = "com.fersaiyan.cyanbridge.TASKER_AGENT_EXECUTE"
    const val ACTION_RESPONSE = "com.fersaiyan.cyanbridge.TASKER_AGENT_RESPONSE"

    const val EXTRA_VERSION = "contract_version"
    const val EXTRA_REQUEST_ID = "request_id"
    const val EXTRA_CALLBACK_TOKEN = "callback_token"
    const val EXTRA_PAYLOAD = "payload"
    const val EXTRA_SUCCESS = "success"
    const val EXTRA_ERROR = "error"

    fun observationFromJson(raw: String): LocalAgentObservation {
        val json = JSONObject(raw)
        val packageName = json.optString("package_name").takeIf { it.isNotBlank() }
        val textSummary = json.optString("text_summary").takeIf { it.isNotBlank() }
        val nodesJson = json.optJSONArray("nodes") ?: JSONArray()
        val nodes = buildList {
            for (i in 0 until nodesJson.length()) {
                val node = nodesJson.optJSONObject(i) ?: continue
                val bounds = node.optJSONObject("bounds")
                add(
                    LocalAgentScreenNode(
                        index = node.optInt("index", i),
                        depth = node.optInt("depth", 0),
                        text = node.optString("text"),
                        contentDescription = node.optString("content_description"),
                        hintText = node.optString("hint_text"),
                        className = node.optString("class_name"),
                        viewId = node.optString("view_id"),
                        isClickable = node.optBoolean("clickable"),
                        isEditable = node.optBoolean("editable"),
                        isScrollable = node.optBoolean("scrollable"),
                        bounds = LocalAgentNodeBounds(
                            left = bounds?.optInt("left") ?: node.optInt("left"),
                            top = bounds?.optInt("top") ?: node.optInt("top"),
                            right = bounds?.optInt("right") ?: node.optInt("right"),
                            bottom = bounds?.optInt("bottom") ?: node.optInt("bottom"),
                        ),
                        isPassword = node.optBoolean("password"),
                        isCheckable = node.optBoolean("checkable"),
                        isChecked = node.optBoolean("checked"),
                        isFocused = node.optBoolean("focused"),
                    )
                )
            }
        }
        val snapshot = LocalAgentScreenSnapshot(packageName, textSummary, nodes)
        return LocalAgentObservation(
            createdAtMs = json.optLong("created_at_ms", System.currentTimeMillis()),
            packageName = packageName,
            screenText = textSummary,
            screenSnapshot = snapshot,
        )
    }

    fun actionToJson(action: LocalAgentAction): String = JSONObject().apply {
        when (action) {
            is LocalAgentAction.Wait -> put("type", "wait").put("ms", action.ms)
            LocalAgentAction.GlobalBack -> put("type", "global_back")
            LocalAgentAction.GlobalHome -> put("type", "global_home")
            is LocalAgentAction.ClickText -> put("type", "click_text").put("text", action.text)
            is LocalAgentAction.ClickCoord -> put("type", "click_coord").put("x", action.x).put("y", action.y)
            is LocalAgentAction.TypeText -> put("type", "type_text").put("text", action.text).put("hint", action.hint)
            LocalAgentAction.PressEnter -> put("type", "press_enter")
            is LocalAgentAction.Scroll -> put("type", "scroll").put("direction", action.direction.name.lowercase())
            is LocalAgentAction.OpenApp -> put("type", "open_app").put("app_name", action.appName)
            is LocalAgentAction.Finish -> put("type", "finish").put("message", action.message)
            is LocalAgentAction.Swipe -> put("type", "swipe")
                .put("start_x", action.startX).put("start_y", action.startY)
                .put("end_x", action.endX).put("end_y", action.endY)
                .put("duration_ms", action.durationMs)
            is LocalAgentAction.LongPress -> put("type", "long_press")
                .put("x", action.x).put("y", action.y).put("duration_ms", action.durationMs)
            LocalAgentAction.OpenNotifications -> put("type", "open_notifications")
            LocalAgentAction.OpenRecents -> put("type", "open_recents")
            is LocalAgentAction.MakeCall -> put("type", "make_call").put("number", action.number)
            is LocalAgentAction.SendSms -> put("type", "send_sms").put("number", action.number).put("message", action.message)
            is LocalAgentAction.SetAlarm -> put("type", "set_alarm").put("hour", action.hour).put("minute", action.minute).put("label", action.label)
            LocalAgentAction.OpenContacts -> put("type", "open_contacts")
            LocalAgentAction.ToggleWifi -> put("type", "toggle_wifi")
            LocalAgentAction.ToggleBluetooth -> put("type", "toggle_bluetooth")
            LocalAgentAction.ToggleFlashlight -> put("type", "toggle_flashlight")
            is LocalAgentAction.SendEmail -> put("type", "send_email").put("to", action.to).put("subject", action.subject).put("body", action.body)
            LocalAgentAction.ReadScreenAloud -> put("type", "read_screen_aloud")
        }
        put("contract_version", VERSION)
    }.toString()
}
