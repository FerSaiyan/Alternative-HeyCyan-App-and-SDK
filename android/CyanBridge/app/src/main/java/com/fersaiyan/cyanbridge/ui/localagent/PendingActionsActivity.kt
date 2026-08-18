package com.fersaiyan.cyanbridge.ui.localagent

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.fersaiyan.cyanbridge.data.local.entity.PendingAction
import com.fersaiyan.cyanbridge.localagent.LocalAgentActionParser
import com.fersaiyan.cyanbridge.localagent.LocalAgentDeviceState
import com.fersaiyan.cyanbridge.localagent.LocalAgentIntents
import com.fersaiyan.cyanbridge.localagent.LocalAgentPrefs
import com.fersaiyan.cyanbridge.localagent.TaskerExecutionBackend
import com.fersaiyan.cyanbridge.localagent.TaskerLocalAgentService
import com.fersaiyan.cyanbridge.shared.ui.localagent.PendingActionsScreen
import com.fersaiyan.cyanbridge.ui.MyApplication
import com.fersaiyan.cyanbridge.ui.appearance.AppearancePreferences
import com.fersaiyan.cyanbridge.ui.appearance.rememberAppearanceSettings
import com.fersaiyan.cyanbridge.ui.theme.CyanBridgeTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PendingActionsActivity : AppCompatActivity() {

    private var current: PendingAction? = null
    private var pendingCount by mutableStateOf(0)
    private var renderedAction by mutableStateOf("(no pending actions)")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val appearancePreferences = AppearancePreferences(this)
        setContent {
            val appearance by rememberAppearanceSettings(appearancePreferences)
            CyanBridgeTheme(appearance) {
                PendingActionsScreen(
                    pendingCount = pendingCount,
                    renderedAction = renderedAction,
                    hasPendingAction = current != null,
                    onRefresh = ::loadPending,
                    onApprove = ::approveCurrent,
                    onReject = ::rejectCurrent,
                    onBack = ::finish,
                )
            }
        }
        loadPending()
    }

    private fun loadPending() {
        lifecycleScope.launch {
            val dao = MyApplication.database.pendingActionDao()
            val pending = withContext(Dispatchers.IO) { dao.getActionsByStatus("pending") }
            pendingCount = pending.size
            current = pending.firstOrNull()
            renderedAction = current?.let(::renderPendingAction) ?: "(no pending actions)"
        }
    }

    private fun renderPendingAction(p: PendingAction): String {
        val tsText = if (p.ts > 0L) {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(p.ts))
        } else "(no-ts)"

        val actions = LocalAgentActionParser.parseList(p.actionJson)
        val humanSummary = actions.joinToString("\n") { action -> describeAction(action) }
        val prettyJson = runCatching {
            val trimmed = p.actionJson.trim()
            if (trimmed.startsWith("{")) JSONObject(trimmed).toString(2) else trimmed
        }.getOrDefault(p.actionJson)

        return buildString {
            appendLine("id=${p.id}")
            appendLine("ts=$tsText")
            appendLine("source=${p.source}")
            appendLine("status=${p.status}")
            if (!p.result.isNullOrBlank()) appendLine("result=${p.result}")
            appendLine("---")
            if (humanSummary.isNotBlank()) {
                appendLine(humanSummary)
                appendLine("---")
            }
            appendLine(prettyJson)
        }.trimEnd()
    }

    private fun describeAction(action: com.fersaiyan.cyanbridge.localagent.LocalAgentAction): String = when (action) {
        is com.fersaiyan.cyanbridge.localagent.LocalAgentAction.OpenApp -> "Open ${action.appName}."
        is com.fersaiyan.cyanbridge.localagent.LocalAgentAction.ClickText -> "Click ${action.text}."
        is com.fersaiyan.cyanbridge.localagent.LocalAgentAction.ClickCoord -> "Tap the highlighted control."
        is com.fersaiyan.cyanbridge.localagent.LocalAgentAction.TypeText -> "Type ${action.text}."
        is com.fersaiyan.cyanbridge.localagent.LocalAgentAction.PressEnter -> "Press enter."
        is com.fersaiyan.cyanbridge.localagent.LocalAgentAction.Scroll -> "Scroll the screen."
        is com.fersaiyan.cyanbridge.localagent.LocalAgentAction.Swipe -> "Swipe the screen."
        is com.fersaiyan.cyanbridge.localagent.LocalAgentAction.LongPress -> "Long press the selected control."
        is com.fersaiyan.cyanbridge.localagent.LocalAgentAction.GlobalBack -> "Press back."
        is com.fersaiyan.cyanbridge.localagent.LocalAgentAction.GlobalHome -> "Go home."
        is com.fersaiyan.cyanbridge.localagent.LocalAgentAction.OpenNotifications -> "Open notifications."
        is com.fersaiyan.cyanbridge.localagent.LocalAgentAction.OpenRecents -> "Open recent apps."
        is com.fersaiyan.cyanbridge.localagent.LocalAgentAction.OpenContacts -> "Open contacts."
        is com.fersaiyan.cyanbridge.localagent.LocalAgentAction.MakeCall -> "Call ${action.number}."
        is com.fersaiyan.cyanbridge.localagent.LocalAgentAction.SendSms -> "Send a message to ${action.number}."
        is com.fersaiyan.cyanbridge.localagent.LocalAgentAction.SendEmail -> "Send an email to ${action.to}."
        is com.fersaiyan.cyanbridge.localagent.LocalAgentAction.SetAlarm -> "Set an alarm."
        is com.fersaiyan.cyanbridge.localagent.LocalAgentAction.ReadScreenAloud -> "Read the screen aloud."
        is com.fersaiyan.cyanbridge.localagent.LocalAgentAction.ToggleWifi -> "Open Wi-Fi settings."
        is com.fersaiyan.cyanbridge.localagent.LocalAgentAction.ToggleBluetooth -> "Open Bluetooth settings."
        is com.fersaiyan.cyanbridge.localagent.LocalAgentAction.ToggleFlashlight -> "Open flashlight settings."
        is com.fersaiyan.cyanbridge.localagent.LocalAgentAction.Wait -> "Wait briefly."
        is com.fersaiyan.cyanbridge.localagent.LocalAgentAction.Finish -> "Finish the task."
    }

    private fun rejectCurrent() {
        val p = current ?: return
        lifecycleScope.launch {
            val dao = MyApplication.database.pendingActionDao()
            withContext(Dispatchers.IO) {
                p.status = "rejected"
                p.result = "rejected_by_user"
                dao.update(p)
            }
            notifyServiceResumeAfterApproval(rejected = true)
            Toast.makeText(this@PendingActionsActivity, "Rejected action #${p.id}", Toast.LENGTH_SHORT).show()
            loadPending()
        }
    }

    private fun approveCurrent() {
        val p = current ?: return
        lifecycleScope.launch {
            val dao = MyApplication.database.pendingActionDao()
            withContext(Dispatchers.IO) {
                p.status = "approved"
                p.result = null
                dao.update(p)
            }

            val actions = LocalAgentActionParser.parseList(p.actionJson)
            if (actions.isEmpty()) {
                withContext(Dispatchers.IO) {
                    p.status = "executed"
                    p.result = "parse_failed"
                    dao.update(p)
                }
                Toast.makeText(this@PendingActionsActivity, "Could not parse action JSON", Toast.LENGTH_SHORT).show()
                loadPending()
                return@launch
            }

            val results = mutableListOf<String>()
            var allSucceeded = true
            for (action in actions) {
                val availability = LocalAgentDeviceState.availability(this@PendingActionsActivity)
                if (availability != LocalAgentDeviceState.Availability.READY) {
                    LocalAgentPrefs.setStatus(this@PendingActionsActivity, "Action blocked: ${availability.statusText}")
                    LocalAgentPrefs.setLastError(this@PendingActionsActivity, availability.errorCode)
                    results += "${action.javaClass.simpleName}: blocked_device_state"
                    allSucceeded = false
                    break
                }

                val execution = runCatching {
                    TaskerExecutionBackend.execute(this@PendingActionsActivity, action)
                }.getOrElse {
                    com.fersaiyan.cyanbridge.localagent.LocalAgentBackendExecutionResult(
                        success = false,
                        detail = "tasker_exception:${it.javaClass.simpleName}:${it.message.orEmpty()}",
                    )
                }
                results += "${action.javaClass.simpleName}: ${execution.detail}"
                if (!execution.success) {
                    allSucceeded = false
                    break
                }
            }

            withContext(Dispatchers.IO) {
                p.status = "executed"
                p.result = results.joinToString("; ")
                dao.update(p)
            }

            Toast.makeText(
                this@PendingActionsActivity,
                if (allSucceeded) "Executed action #${p.id}" else "Tasker failed action #${p.id}",
                Toast.LENGTH_SHORT,
            ).show()

            // Resume the CyanBridge planner. The exact Tasker result remains in the pending
            // action record, so a rejection/failure can be traced from one place.
            notifyServiceResumeAfterApproval(rejected = !allSucceeded)
            loadPending()
        }
    }

    private fun notifyServiceResumeAfterApproval(rejected: Boolean) {
        val intent = Intent(this, TaskerLocalAgentService::class.java).apply {
            action = LocalAgentIntents.ACTION_RESUME_AFTER_APPROVAL
            putExtra(LocalAgentIntents.EXTRA_REJECTED, rejected)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
        else startService(intent)
    }
}
