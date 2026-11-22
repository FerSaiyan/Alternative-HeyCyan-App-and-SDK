package com.fersaiyan.cyanbridge.ui.debug

import android.content.Context
import android.content.Intent
import android.os.Process
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fersaiyan.cyanbridge.ui.dialog.ExpressiveAlert
import com.fersaiyan.cyanbridge.ui.dialog.ExpressiveDialogs
import com.fersaiyan.cyanbridge.ui.dialog.SingleChoiceList
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.fersaiyan.cyanbridge.BuildConfig
import com.fersaiyan.cyanbridge.agent.LocalAgentPrefs as AutomationPrefs
import com.fersaiyan.cyanbridge.agent.ProSubscriptionServerPrefs
import com.fersaiyan.cyanbridge.ai.router.AiProviderPrefs
import com.fersaiyan.cyanbridge.devices.DeviceProfileStore
import com.fersaiyan.cyanbridge.devices.metarayban.MetaRaybanManager
import com.fersaiyan.cyanbridge.devices.meizumyvu.MeizuMyvuManager
import com.fersaiyan.cyanbridge.diagnostics.DiagnosticsCollector
import com.fersaiyan.cyanbridge.localagent.LocalAgentDeviceState
import com.fersaiyan.cyanbridge.agent.LocalAgentPrefs as RuntimePrefs
import com.fersaiyan.cyanbridge.localagent.TaskerLocalAgentService
import com.fersaiyan.cyanbridge.localagent.LocalAgentTaskHistory
import com.fersaiyan.cyanbridge.localmodels.remote.RemoteOpenAiPrefs
import com.fersaiyan.cyanbridge.shared.diagnostics.DiagnosticEvents
import com.fersaiyan.cyanbridge.shared.diagnostics.DiagnosticReport
import com.fersaiyan.cyanbridge.shared.diagnostics.DiagnosticReportComposer
import com.fersaiyan.cyanbridge.shared.diagnostics.DiagnosticReportInput
import com.fersaiyan.cyanbridge.shared.diagnostics.DiagnosticSubsystem
import com.fersaiyan.cyanbridge.shared.diagnostics.DiagnosticTrigger
import com.fersaiyan.cyanbridge.ui.hasAccessibilityServicePermission
import com.fersaiyan.cyanbridge.ui.hasNotificationPermission
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.LinkedHashMap
import java.util.Locale

/**
 * Support reports: what leaves the app when the user sends logs, from an error prompt or from
 * Settings → Send logs. Every path builds one [DiagnosticReport] (context header, recent events of
 * every subsystem, the last crash or ANR, this process's logcat; all redacted), shows the user
 * what it contains on request, and posts it to `{relay}/logs/submit` or exports it for email.
 *
 * History: v2.3.0 built the logcat filter from tag names; the Meta DAT tags added in 1ad4ca49
 * (`DAT:CORE:RegistrationManager`) contain colons, logcat rejected the whole filter, and every
 * report arrived without log lines. The logcat read is PID-scoped since eac40aee; per-tag caps
 * and the per-subsystem event buffer keep DAT's volume from crowding out everything else.
 */
object DebugLogSupport {
    const val SUPPORT_EMAIL = "fernandosaiyan10@gmail.com"
    const val MAX_CONTACT_EMAIL_LENGTH = 320
    const val MAX_NOTE_LENGTH = 1_000

    /** A voluntary report carries this much recent history (errors are kept regardless of age). */
    const val VOLUNTARY_EVENT_WINDOW_MS = 30 * 60_000L
    /** An error prompt carries the whole buffer. */
    private const val ERROR_EVENT_WINDOW_MS = 24 * 60 * 60_000L

    private const val TAG = "DebugLogSupport"

    private const val WHAT_IS_SENT =
        "What is sent: app and phone details (model, Android, chip, free storage and memory), the selected " +
            "glasses and their connection, your AI, routing and sync settings, the app's recent events (the last " +
            "30 minutes and earlier errors), the last crash if there was one, and this app's own system log.\n\n" +
            "Personal data is removed before sending: no photos, audio, transcripts, messages, tokens or email " +
            "addresses. Tap Preview to read everything that will be sent."

    data class FirmwarePatchRequest(
        val source: String,
        val target: String,
        val targetHardwareVersion: String,
        val targetFirmwareVersion: String,
        val wifiHardwareVersion: String,
        val wifiFirmwareVersion: String,
        val bleHardwareVersion: String,
        val bleFirmwareVersion: String,
        val relayMessage: String,
    )

    /** What a report is about; [issueTypes] non-empty lets the user pick one (Settings). */
    class ReportRequest(
        val trigger: DiagnosticTrigger,
        val title: String,
        val issueType: String,
        val description: String,
        val extraInfo: Map<String, String> = emptyMap(),
        val issueTypes: List<String> = emptyList(),
        val dismissButtonLabel: String = "Cancel",
    )

    /**
     * The prompt shown after an error: the user may add a note, preview and send, or email the file.
     * A diagnostics button the user pressed passes [DiagnosticTrigger.VOLUNTARY], so its report is
     * not filed (and its event not logged) as an error.
     */
    fun showSupportOptionsDialog(
        activity: AppCompatActivity,
        title: String,
        issueType: String,
        description: String,
        extraInfo: Map<String, String> = emptyMap(),
        dismissButtonLabel: String = "Later",
        trigger: DiagnosticTrigger = DiagnosticTrigger.ERROR_PROMPT,
    ) {
        if (trigger == DiagnosticTrigger.ERROR_PROMPT) {
            DiagnosticEvents.error(DiagnosticSubsystem.UI, "error prompt shown", mapOf("issue" to issueType))
        } else {
            DiagnosticEvents.info(DiagnosticSubsystem.UI, "diagnostics opened", mapOf("issue" to issueType))
        }
        showReportDialog(
            activity,
            ReportRequest(
                trigger = trigger,
                title = title,
                issueType = issueType,
                description = description,
                extraInfo = extraInfo,
                dismissButtonLabel = dismissButtonLabel,
            ),
        )
    }

    /** Settings → Send logs: no error needed; the recent events are included anyway. */
    fun showVoluntaryReportDialog(activity: AppCompatActivity, issueTypes: List<String>) {
        showReportDialog(
            activity,
            ReportRequest(
                trigger = DiagnosticTrigger.VOLUNTARY,
                title = "Send Debug Logs",
                issueType = issueTypes.first(),
                description = "",
                issueTypes = issueTypes,
            ),
        )
    }

    private fun showReportDialog(activity: AppCompatActivity, request: ReportRequest) {
        ExpressiveDialogs.show(activity) { close ->
            var issueIndex by remember { mutableIntStateOf(request.issueTypes.indexOf(request.issueType).coerceAtLeast(0)) }
            var note by remember { mutableStateOf("") }
            var email by remember { mutableStateOf(ProSubscriptionServerPrefs.getAccountEmail(activity).orEmpty()) }
            var emailError by remember { mutableStateOf<String?>(null) }
            fun issueType() = request.issueTypes.getOrNull(issueIndex) ?: request.issueType
            fun validEmail(): String? {
                val value = email.trim()
                if (!isValidOptionalContactEmail(value)) {
                    emailError = "Enter a valid email or leave this blank"
                    return null
                }
                return value
            }
            fun trimmedNote() = note.trim().take(MAX_NOTE_LENGTH)
            ExpressiveAlert(
                title = request.title,
                message = null,
                confirm = ExpressiveDialogs.Action("Send", dismissOnClick = false) {
                    val contact = validEmail() ?: return@Action
                    close()
                    buildThen(activity, request, issueType(), trimmedNote(), contact) { report -> upload(activity, report) }
                },
                dismiss = ExpressiveDialogs.Action(request.dismissButtonLabel),
                neutral = ExpressiveDialogs.Action("Preview", dismissOnClick = false) {
                    val contact = validEmail() ?: return@Action
                    close()
                    buildThen(activity, request, issueType(), trimmedNote(), contact) { report ->
                        showPreview(activity, request, issueType(), trimmedNote(), contact, report)
                    }
                },
                cancelable = true,
                close = close,
                body = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (request.description.isNotBlank()) Text(request.description.trim())
                        if (request.issueTypes.isNotEmpty()) {
                            SingleChoiceList(request.issueTypes, issueIndex) { issueIndex = it }
                        }
                        OutlinedTextField(
                            value = note,
                            onValueChange = { note = it.take(MAX_NOTE_LENGTH) },
                            label = {
                                Text(
                                    if (request.trigger == DiagnosticTrigger.VOLUNTARY) "What happened? (optional)"
                                    else "Anything to add? (optional)",
                                )
                            },
                            minLines = 2,
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = email,
                            onValueChange = { email = it.take(MAX_CONTACT_EMAIL_LENGTH); emailError = null },
                            label = { Text("Contact email (optional)") },
                            singleLine = true,
                            isError = emailError != null,
                            supportingText = emailError?.let { { Text(it) } },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                            modifier = Modifier
                                .fillMaxWidth()
                                .semantics { contentDescription = "Contact email for log follow-up" },
                        )
                        Text(
                            text = "Optional. Lets CyanBridge support reply to you; it is sent separately from the logs.\n\n$WHAT_IS_SENT",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
        }
    }

    /** The full report text, exactly as it will be sent. */
    private fun showPreview(
        activity: AppCompatActivity,
        request: ReportRequest,
        issueType: String,
        note: String,
        contactEmail: String,
        report: DiagnosticReport,
    ) {
        ExpressiveDialogs.alert(
            activity = activity,
            title = "What will be sent (${report.serialized.length / 1024} KB, ${report.redactionCount} items removed)",
            confirm = ExpressiveDialogs.Action("Send") { upload(activity, report) },
            dismiss = ExpressiveDialogs.Action("Back"),
            neutral = ExpressiveDialogs.Action("Email instead") {
                exportForEmail(activity, issueType, note, contactEmail, report)
            },
        ) {
            SelectionContainer {
                Text(
                    text = report.preview,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                )
            }
        }
    }

    private fun buildThen(
        activity: AppCompatActivity,
        request: ReportRequest,
        issueType: String,
        note: String,
        contactEmail: String,
        then: (DiagnosticReport) -> Unit,
    ) {
        Toast.makeText(activity, "Collecting logs...", Toast.LENGTH_SHORT).show()
        CoroutineScope(Dispatchers.Main).launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    buildReport(
                        context = activity,
                        trigger = request.trigger,
                        issueType = issueType,
                        description = request.description,
                        userNote = note,
                        contactEmail = contactEmail,
                        extraInfo = request.extraInfo,
                    )
                }
            }
            result.onSuccess(then).onFailure { err ->
                Log.e(TAG, "Failed to build the report", err)
                if (!activity.isFinishing) Toast.makeText(activity, "Failed to collect logs: ${err.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun upload(activity: AppCompatActivity, report: DiagnosticReport) {
        CoroutineScope(Dispatchers.Main).launch {
            val result = withContext(Dispatchers.IO) { postReport(activity, report) }
            result.onSuccess { logId ->
                Toast.makeText(activity, "Logs sent. Log ID: $logId", Toast.LENGTH_LONG).show()
            }.onFailure { err ->
                Log.e(TAG, "Failed to upload logs", err)
                Toast.makeText(activity, "Failed to send logs: ${err.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    /** Builds the report for [trigger]. Blocking (logcat, binder calls): call it off the main thread. */
    fun buildReport(
        context: Context,
        trigger: DiagnosticTrigger,
        issueType: String,
        description: String,
        userNote: String? = null,
        contactEmail: String? = null,
        extraInfo: Map<String, String> = emptyMap(),
        requestMetadata: String? = null,
        relayBaseUrl: String? = null,
    ): DiagnosticReport {
        val now = System.currentTimeMillis()
        val window = if (trigger == DiagnosticTrigger.VOLUNTARY) VOLUNTARY_EVENT_WINDOW_MS else ERROR_EVENT_WINDOW_MS
        val logcat = DiagnosticsCollector.currentLogcat()
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return DiagnosticReportComposer.compose(
            DiagnosticReportInput(
                trigger = trigger,
                issueType = issueType,
                description = description.ifBlank { userNote.orEmpty() }.ifBlank {
                    if (trigger == DiagnosticTrigger.VOLUNTARY) "Report sent from Settings without a description" else ""
                },
                userNote = userNote?.takeIf { it.isNotBlank() },
                contactEmail = contactEmail?.takeIf { it.isNotBlank() },
                requestMetadata = requestMetadata,
                appVersion = info.versionName.orEmpty(),
                versionCode = info.longVersionCode,
                buildType = BuildConfig.BUILD_TYPE,
                header = DiagnosticsCollector.header(context, logcat),
                extraDiagnostics = otherDiagnostics(context, extraInfo),
                events = DiagnosticEvents.log.recent(now - window),
                eventWindowMs = window,
                lastExit = runCatching { DiagnosticsCollector.lastExit(context) }.getOrNull(),
                logcat = logcat.text,
                createdAtMs = now,
                destination = runCatching { URI(relayBaseUrl(context, relayBaseUrl)).host }.getOrNull() ?: "CyanBridge server",
            ),
        )
    }

    /** Builds and sends a report in one step (flows without a dialog). */
    suspend fun submitReport(
        context: Context,
        trigger: DiagnosticTrigger,
        issueType: String,
        description: String,
        contactEmail: String? = null,
        extraInfo: Map<String, String> = emptyMap(),
        requestMetadata: String? = null,
        relayBaseUrl: String? = null,
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            buildReport(
                context, trigger, issueType, description,
                contactEmail = contactEmail, extraInfo = extraInfo,
                requestMetadata = requestMetadata, relayBaseUrl = relayBaseUrl,
            )
        }.fold(onSuccess = { postReport(context, it, relayBaseUrl) }, onFailure = { Result.failure(it) })
    }

    private fun relayBaseUrl(context: Context, override: String?): String =
        override?.trim()?.trimEnd('/')?.takeIf { it.isNotBlank() } ?: AiProviderPrefs.getRelayBaseUrl(context).trimEnd('/')

    /** Posts [report] to `{relay}/logs/submit`; returns the server's log id. */
    fun postReport(context: Context, report: DiagnosticReport, relayBaseUrl: String? = null): Result<String> = runCatching {
        // Pre-launch robots send junk reports that evict real ones (the server keeps the newest 200).
        check(!com.fersaiyan.cyanbridge.diagnostics.TestLabEnvironment.isTestLab(context)) {
            "Not sent: this is a Firebase Test Lab device"
        }
        val url = URL("${relayBaseUrl(context, relayBaseUrl)}/logs/submit")
        val token = ProSubscriptionServerPrefs.getApiToken(context)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 30_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            if (token.isNotBlank()) setRequestProperty("Authorization", "Bearer $token")
        }
        OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(report.serialized) }
        val code = conn.responseCode
        val body = BufferedReader(InputStreamReader(if (code in 200..299) conn.inputStream else conn.errorStream ?: conn.inputStream))
            .use { it.readText() }
        conn.disconnect()
        if (code !in 200..299) throw IllegalStateException("HTTP $code: ${body.take(200)}")
        Log.i(TAG, "Report sent: ${report.serialized.length} chars, ${report.redactionCount} redactions")
        org.json.JSONObject(body).optString("log_id", "submitted")
    }

    fun isLocalRuntimeIssue(message: String?, throwable: Throwable? = null): Boolean {
        val haystack = buildString {
            if (!message.isNullOrBlank()) append(message)
            if (throwable != null) {
                if (isNotBlank()) append(' ')
                append(throwable::class.java.name)
                if (!throwable.message.isNullOrBlank()) {
                    append(' ')
                    append(throwable.message)
                }
            }
        }.lowercase(Locale.US)

        if (haystack.isBlank()) return false

        return listOf(
            "litert",
            "vulkan",
            "opencl",
            "gpu",
            "llama",
            "gguf",
            "n_gpu_layers",
            "local model runtime",
            "failed to initialize local llama context",
            "inference engine",
            "gemma transcription requires local runtime",
        ).any { haystack.contains(it) }
    }

    /**
     * Free-text diagnostics that predate the structured header: automation state, the Meta DAT and
     * MYVU snapshots of the selected family, and the caller's extra info.
     */
    internal fun otherDiagnostics(context: Context, extraInfo: Map<String, String> = emptyMap()): String = buildString {
        appendAutomationDiagnostics(context)
        if (RemoteOpenAiPrefs.isActive(context)) {
            append("Remote model: ${RemoteOpenAiPrefs.getModel(context)}\n")
        }
        if (DeviceProfileStore.isMetaSelected(context)) {
            append("Meta DAT diagnostics:\n")
            append(runCatching { MetaRaybanManager.getInstance(context).diagnosticsSnapshot() }.getOrDefault("unavailable"))
            append("\n")
        }
        if (DeviceProfileStore.isMeizuMyvuSelected(context)) {
            append("MYVU diagnostics:\n")
            append(runCatching { MeizuMyvuManager.getInstance(context).diagnosticsSnapshot() }.getOrDefault("unavailable"))
            append("\n")
        }
        if (extraInfo.isNotEmpty()) {
            append("Extra info:\n")
            for ((key, value) in LinkedHashMap(extraInfo)) append("- $key: $value\n")
        }
    }

    internal fun buildAutomationDiagnostics(context: Context): String = buildString {
        appendAutomationDiagnostics(context)
    }

    private fun StringBuilder.appendAutomationDiagnostics(context: Context) {
        val accessibilityEnabled = hasAccessibilityServicePermission(context)
        val deviceAvailability = LocalAgentDeviceState.availability(context)
        val lastTask = LocalAgentTaskHistory.recent(context, limit = 1).firstOrNull()

        appendLine("Automation diagnostics:")
        appendLine("- Feature enabled: ${AutomationPrefs.isLocalAgentAutomationEnabled(context)}")
        appendLine("- AutoInput accessibility enabled: $accessibilityEnabled")
        appendLine("- Local Agent service running: ${TaskerLocalAgentService.isRunning()}")
        appendLine("- Notification permission: ${hasNotificationPermission(context)}")
        appendLine("- Device state: ${deviceAvailability.name} (${deviceAvailability.statusText})")
        appendLine("- Runtime status: ${RuntimePrefs.getStatus(context)}")
        appendLine("- Last error: ${RuntimePrefs.getLastError(context)}")
        appendLine("- Action confirmation required: ${RuntimePrefs.isRequireActionConfirmationEnabled(context)}")
        appendLine("- Shizuku fallback enabled: ${RuntimePrefs.isShizukuFallbackEnabled(context)}")
        if (lastTask == null) {
            appendLine("- Last task: none recorded")
        } else {
            appendLine(
                "- Last task: status=${lastTask.status}, steps=${lastTask.stepCount}, " +
                    "created_at_ms=${lastTask.createdAtMs}",
            )
        }
    }

    /** Sends the unavailable exact-base firmware details and focused OTA diagnostics to support. */
    suspend fun sendFirmwarePatchRequest(
        context: Context,
        contactEmail: String,
        request: FirmwarePatchRequest,
        relayBaseUrl: String? = null,
    ): Result<String> {
        val normalizedEmail = contactEmail.trim()
        if (!isValidContactEmail(normalizedEmail)) {
            return Result.failure(IllegalArgumentException("Enter a valid contact email"))
        }

        Log.i(
            TAG,
            "Submitting firmware patch request: target=${request.target}, " +
                "hardware=${request.targetHardwareVersion}, firmware=${request.targetFirmwareVersion}",
        )
        val requestMetadata = buildString {
            appendLine("Request type: exact-base firmware patch availability")
            appendLine("Requested server source: ${request.source}")
            appendLine("Requested target: ${request.target}")
            appendLine("Target hardware version: ${request.targetHardwareVersion}")
            appendLine("Target firmware version: ${request.targetFirmwareVersion}")
            appendLine("Wi-Fi hardware version: ${request.wifiHardwareVersion}")
            appendLine("Wi-Fi firmware version: ${request.wifiFirmwareVersion}")
            appendLine("BLE hardware version: ${request.bleHardwareVersion}")
            appendLine("BLE firmware version: ${request.bleFirmwareVersion}")
            appendLine("Relay response: ${request.relayMessage}")
        }.trim()
        return submitReport(
            context = context,
            trigger = DiagnosticTrigger.FIRMWARE_REQUEST,
            issueType = "Firmware patch request",
            description = "No approved exact-base firmware patch is available for the reported glasses version. " +
                "The requester asked the developer to review this specific version.",
            contactEmail = normalizedEmail,
            extraInfo = linkedMapOf(
                "Firmware request source" to request.source,
                "Firmware request target" to request.target,
                "Target hardware version" to request.targetHardwareVersion,
                "Target firmware version" to request.targetFirmwareVersion,
                "Firmware relay response" to request.relayMessage,
            ),
            requestMetadata = requestMetadata,
            relayBaseUrl = relayBaseUrl,
        )
    }

    fun manualLogRelativePath(context: Context): String {
        return "Android/data/${context.packageName}/files/debug-logs/"
    }

    /** Saves the previewed text (exactly what would be sent) and opens an email/share chooser. */
    private fun exportForEmail(
        activity: AppCompatActivity,
        issueType: String,
        note: String,
        contactEmail: String,
        report: DiagnosticReport,
    ) {
        CoroutineScope(Dispatchers.Main).launch {
            val file = withContext(Dispatchers.IO) { runCatching { writeManualLogFile(activity, issueType, report.preview) } }
            file.onSuccess { saved ->
                val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", saved)
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_EMAIL, arrayOf(SUPPORT_EMAIL))
                    putExtra(Intent.EXTRA_SUBJECT, "CyanBridge logs: $issueType")
                    putExtra(
                        Intent.EXTRA_TEXT,
                        buildString {
                            appendLine("Please find the attached CyanBridge debug logs.")
                            appendLine()
                            appendLine("Issue type: $issueType")
                            if (note.isNotBlank()) appendLine("Note: $note")
                            if (contactEmail.isNotBlank()) appendLine("Contact email: $contactEmail")
                        }.trim(),
                    )
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                try {
                    activity.startActivity(Intent.createChooser(intent, "Send debug logs"))
                } catch (err: Exception) {
                    Log.e(TAG, "No app available to email logs", err)
                    Toast.makeText(
                        activity,
                        "No email/share app found. Log saved in ${manualLogRelativePath(activity)}",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }.onFailure { err ->
                Log.e(TAG, "Failed to export logs for email", err)
                Toast.makeText(activity, "Failed to prepare logs: ${err.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun writeManualLogFile(context: Context, issueType: String, text: String): File {
        val dir = (context.getExternalFilesDir(null) ?: context.filesDir)
            .let { File(it, "debug-logs") }
            .apply { mkdirs() }
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val safeType = issueType
            .lowercase(Locale.US)
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .ifBlank { "general" }
        return File(dir, "cyanbridge_${safeType}_$timestamp.txt").apply { writeText(text) }
    }

    fun isValidOptionalContactEmail(value: String): Boolean =
        value.isBlank() || isValidContactEmail(value.trim())

    private fun isValidContactEmail(value: String): Boolean =
        value.length <= MAX_CONTACT_EMAIL_LENGTH &&
            value.matches(Regex("[^\\s@]+@[^\\s@]+\\.[^\\s@]+"))
}
