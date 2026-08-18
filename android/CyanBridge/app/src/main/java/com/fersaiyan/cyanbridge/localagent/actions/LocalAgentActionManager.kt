package com.fersaiyan.cyanbridge.localagent.actions

import android.content.Context
import com.fersaiyan.cyanbridge.data.local.entity.PendingAction
import com.fersaiyan.cyanbridge.localagent.LocalAgentAction
import com.fersaiyan.cyanbridge.localagent.LocalAgentDeviceState
import com.fersaiyan.cyanbridge.localagent.LocalAgentPrefs
import com.fersaiyan.cyanbridge.localagent.TaskerExecutionBackend
import com.fersaiyan.cyanbridge.ui.MyApplication
import org.json.JSONObject

/**
 * Owns Local Agent action policy/approval state.
 *
 * It intentionally does not execute Android intents, Accessibility gestures, or other
 * model-selected device effects itself. Once CyanBridge has decided that an action may
 * run, TaskerExecutionBackend is the single execution path.
 */
object LocalAgentActionManager {

    enum class Risk { LOW, MEDIUM, HIGH }

    fun serializeAction(action: LocalAgentAction): String = actionToJson(action).toString()

    fun classifyRisk(action: LocalAgentAction): Risk {
        return when (action) {
            is LocalAgentAction.Wait,
            is LocalAgentAction.GlobalBack,
            is LocalAgentAction.GlobalHome,
            is LocalAgentAction.Finish,
            is LocalAgentAction.Scroll,
            is LocalAgentAction.OpenApp,
            is LocalAgentAction.OpenNotifications,
            is LocalAgentAction.OpenRecents,
            is LocalAgentAction.OpenContacts,
            is LocalAgentAction.ToggleFlashlight -> Risk.LOW

            is LocalAgentAction.ClickText,
            is LocalAgentAction.ClickCoord,
            is LocalAgentAction.TypeText,
            is LocalAgentAction.Swipe,
            is LocalAgentAction.LongPress,
            is LocalAgentAction.ToggleWifi,
            is LocalAgentAction.ToggleBluetooth,
            LocalAgentAction.PressEnter -> Risk.MEDIUM

            LocalAgentAction.ReadScreenAloud,
            is LocalAgentAction.MakeCall,
            is LocalAgentAction.SendSms,
            is LocalAgentAction.SetAlarm,
            is LocalAgentAction.SendEmail -> Risk.HIGH
        }
    }

    /**
     * Applies CyanBridge approval policy, then delegates every permitted device action
     * to Tasker. A false result therefore means either "queued for approval" or
     * "Tasker execution failed"; callers that need to distinguish those states already
     * know whether the action required approval from the risk/confirmation settings.
     */
    suspend fun processPlannedAction(
        context: Context,
        action: LocalAgentAction,
        source: String = "agent",
    ): Boolean {
        val risk = classifyRisk(action)
        val requireConfirm = LocalAgentPrefs.isRequireActionConfirmationEnabled(context)
        val shouldAutoExecute = !requireConfirm || risk != Risk.HIGH

        if (shouldAutoExecute) {
            if (!LocalAgentDeviceState.isReady(context)) return false
            return TaskerExecutionBackend.execute(context, action).success
        }

        val dao = MyApplication.database.pendingActionDao()
        dao.insert(
            PendingAction(
                ts = System.currentTimeMillis(),
                source = source,
                actionJson = serializeAction(action),
                status = "pending",
            )
        )
        return false
    }

    private fun actionToJson(action: LocalAgentAction): JSONObject {
        val obj = JSONObject()
        when (action) {
            is LocalAgentAction.Wait -> {
                obj.put("type", "wait")
                obj.put("ms", action.ms)
            }
            is LocalAgentAction.GlobalBack -> obj.put("type", "back")
            is LocalAgentAction.GlobalHome -> obj.put("type", "home")
            is LocalAgentAction.ClickText -> {
                obj.put("type", "click_text")
                obj.put("text", action.text)
            }
            is LocalAgentAction.ClickCoord -> {
                obj.put("type", "click_coord")
                obj.put("x", action.x)
                obj.put("y", action.y)
            }
            is LocalAgentAction.TypeText -> {
                obj.put("type", "type_text")
                obj.put("text", action.text)
                action.hint?.let { obj.put("hint", it) }
            }
            LocalAgentAction.PressEnter -> obj.put("type", "press_enter")
            is LocalAgentAction.Scroll -> {
                obj.put("type", "scroll")
                obj.put("direction", action.direction.name.lowercase())
            }
            is LocalAgentAction.Swipe -> {
                obj.put("type", "swipe")
                obj.put("start_x", action.startX)
                obj.put("start_y", action.startY)
                obj.put("end_x", action.endX)
                obj.put("end_y", action.endY)
                obj.put("duration_ms", action.durationMs)
            }
            is LocalAgentAction.LongPress -> {
                obj.put("type", "long_press")
                obj.put("x", action.x)
                obj.put("y", action.y)
                obj.put("duration_ms", action.durationMs)
            }
            is LocalAgentAction.OpenNotifications -> obj.put("type", "open_notifications")
            is LocalAgentAction.OpenRecents -> obj.put("type", "open_recents")
            is LocalAgentAction.OpenApp -> {
                obj.put("type", "open_app")
                obj.put("app_name", action.appName)
            }
            is LocalAgentAction.Finish -> {
                obj.put("type", "finish")
                action.message?.let { obj.put("message", it) }
            }
            is LocalAgentAction.MakeCall -> {
                obj.put("type", "make_call")
                obj.put("number", action.number)
            }
            is LocalAgentAction.SendSms -> {
                obj.put("type", "send_sms")
                obj.put("number", action.number)
                obj.put("message", action.message)
            }
            is LocalAgentAction.SetAlarm -> {
                obj.put("type", "set_alarm")
                obj.put("hour", action.hour)
                obj.put("minute", action.minute)
                action.label?.let { obj.put("label", it) }
            }
            is LocalAgentAction.OpenContacts -> obj.put("type", "open_contacts")
            is LocalAgentAction.ToggleWifi -> obj.put("type", "toggle_wifi")
            is LocalAgentAction.ToggleBluetooth -> obj.put("type", "toggle_bluetooth")
            is LocalAgentAction.ToggleFlashlight -> obj.put("type", "toggle_flashlight")
            is LocalAgentAction.SendEmail -> {
                obj.put("type", "send_email")
                obj.put("to", action.to)
                obj.put("subject", action.subject)
                obj.put("body", action.body)
            }
            LocalAgentAction.ReadScreenAloud -> obj.put("type", "read_screen_aloud")
        }
        return obj
    }
}
