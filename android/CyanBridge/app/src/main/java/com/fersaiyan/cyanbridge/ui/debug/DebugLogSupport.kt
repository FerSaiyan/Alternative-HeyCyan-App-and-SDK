package com.fersaiyan.cyanbridge.ui.debug

import android.content.Context
import android.content.Intent
import android.text.InputFilter
import android.text.InputType
import android.util.Log
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.fersaiyan.cyanbridge.agent.LocalAgentPrefs as AutomationPrefs
import com.fersaiyan.cyanbridge.agent.ProSubscriptionServerPrefs
import com.fersaiyan.cyanbridge.ai.router.AiProviderPrefs
import com.fersaiyan.cyanbridge.devices.DeviceProfileStore
import com.fersaiyan.cyanbridge.devices.metarayban.MetaRaybanManager
import com.fersaiyan.cyanbridge.devices.meizumyvu.MeizuMyvuManager
import com.fersaiyan.cyanbridge.localagent.LocalAgentAccessibilityBridge
import com.fersaiyan.cyanbridge.localagent.LocalAgentDeviceState
import com.fersaiyan.cyanbridge.localagent.LocalAgentPrefs as RuntimePrefs
import com.fersaiyan.cyanbridge.localagent.LocalAgentService
import com.fersaiyan.cyanbridge.localagent.LocalAgentTaskHistory
import com.fersaiyan.cyanbridge.localmodels.remote.RemoteOpenAiPrefs
import com.fersaiyan.cyanbridge.localmodels.settings.LocalModelSettingsRepository
import com.fersaiyan.cyanbridge.localmodels.storage.LocalModelStorageRepository
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
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.LinkedHashMap
import java.util.Locale

object DebugLogSupport {
    const val SUPPORT_EMAIL = "fernandosaiyan10@gmail.com"
    const val MAX_CONTACT_EMAIL_LENGTH = 320

    private const val TAG = "DebugLogSupport"
    private const val MAX_LOGCAT_CHARS = 120_000
    internal val LOG_TAGS = listOf(
        "AIHijack",
        "ImageQuestionAudio",
        "DataDownload",
        "DeviceNotify",
        "WifiP2pManagerSingleton",
        "WifiP2pBroadcastReceiver",
        "BleIpBridge",
        "FirmwareClient",
        "Ota",
        "OtaManager",
        "BleDfuManager",
        "DebugLogSupport",
        "CliRelayRouter",
        "LocalAgent",
        "LocalAgentAccSvc",
        "LocalAgentBridge",
        "LocalAgentController",
        "LocalAgentRecovery",
        "LocalAgentService",
        "LocalAgentSteps",
        "MainActivity",
        "MetaRaybanManager",
        "DAT:CORE:RegistrationManager",
        "DAT:CORE:BluetoothDeviceDetection",
        "MeizuMyvu",
        "MeizuMyvuService",
        "myvu",
        "VisualDiaryService",
        "WalkingAidService",
        "CommunityPluginsActivity",
        "ChatThreadActivity",
        "SettingsActivity",
        "LocalModelsConfigureActivity",
        "RecordingsListActivity",
        "LocalModelsProvider",
        "RemoteOpenAiClient",
        "LocalChatSession",
        "LiteRtLocalEngine",
        "LlamaCppLocalEngine",
        "GemmaLiteRtTranscribe",
    )

    data class DebugReport(
        val logs: String,
        val deviceInfo: String,
        val file: File,
    )

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

    fun showSupportOptionsDialog(
        activity: AppCompatActivity,
        title: String,
        issueType: String,
        description: String,
        extraInfo: Map<String, String> = emptyMap(),
        dismissButtonLabel: String = "Later",
    ) {
        if (activity.isFinishing || activity.isDestroyed) return

        val manualPath = manualLogRelativePath(activity)
        val message = buildString {
            append(description.trim())
            append("\n\nYou can either send the detailed logs straight to the CyanBridge server or export an email-ready log file.\n\n")
            append("Manual log files are saved in:\n")
            append(manualPath)
            append("\n\nEmail logs to:\n")
            append(SUPPORT_EMAIL)
        }
        val contactEmailInput = EditText(activity).apply {
            hint = "Contact email (optional)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            filters = arrayOf(InputFilter.LengthFilter(MAX_CONTACT_EMAIL_LENGTH))
            setText(ProSubscriptionServerPrefs.getAccountEmail(activity))
            contentDescription = "Contact email for log follow-up"
        }
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (24 * resources.displayMetrics.density).toInt()
            setPadding(padding, 0, padding, 0)
            addView(contactEmailInput)
            addView(TextView(activity).apply {
                text = "Optional. This lets CyanBridge support reply to you about these logs."
            })
        }

        val dialog = AlertDialog.Builder(activity)
            .setTitle(title)
            .setMessage(message)
            .setView(content)
            .setNegativeButton(dismissButtonLabel, null)
            .setNeutralButton("Email logs", null)
            .setPositiveButton("Send to server", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                val contactEmail = contactEmailInput.text?.toString().orEmpty().trim()
                if (!isValidOptionalContactEmail(contactEmail)) {
                    contactEmailInput.error = "Enter a valid email or leave this blank"
                    return@setOnClickListener
                }
                dialog.dismiss()
                exportLogsForEmail(
                    activity = activity,
                    issueType = issueType,
                    description = description,
                    extraInfo = extraInfo,
                    contactEmail = contactEmail,
                )
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val contactEmail = contactEmailInput.text?.toString().orEmpty().trim()
                if (!isValidOptionalContactEmail(contactEmail)) {
                    contactEmailInput.error = "Enter a valid email or leave this blank"
                    return@setOnClickListener
                }
                dialog.dismiss()
                uploadLogsToServer(
                    activity = activity,
                    issueType = issueType,
                    description = description,
                    extraInfo = extraInfo,
                    contactEmail = contactEmail,
                )
            }
        }
        dialog.show()
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

    fun collectLogcat(): String {
        val collected = try {
            val filter = LOG_TAGS.joinToString(" ") { "$it:*" }
            val process = Runtime.getRuntime().exec("logcat -d -t 1200 -s $filter")
            process.inputStream.bufferedReader().use { it.readText() }.take(MAX_LOGCAT_CHARS)
        } catch (e: Exception) {
            "Failed to collect logcat: ${e.message}"
        }
        return normalizeCollectedLogs(collected)
    }

    internal fun normalizeCollectedLogs(logs: String): String =
        logs.trim().ifEmpty {
            "No matching Android logcat entries were available. See Device Info and Extra Info for structured diagnostics."
        }

    fun buildDeviceInfo(
        context: Context,
        extraInfo: Map<String, String> = emptyMap(),
    ): String {
        return buildString {
            append("Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}\n")
            append("Android: ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})\n")
            append("App: ${context.packageManager.getPackageInfo(context.packageName, 0).versionName}\n")
            append("Provider: ${AutomationPrefs.getProviderType(context)}\n")
            append("Relay: ${AiProviderPrefs.getRelayBaseUrl(context)}\n")
            val remoteModelActive = RemoteOpenAiPrefs.isActive(context)
            append("Local-model backend: ${if (remoteModelActive) "Remote OpenAI-compatible" else "On-device"}\n")
            if (remoteModelActive) {
                append("Remote model: ${RemoteOpenAiPrefs.getModel(context)}\n")
                append("Remote base URL: ${RemoteOpenAiPrefs.getBaseUrl(context)}\n")
            }
            appendAutomationDiagnostics(context)

            if (DeviceProfileStore.isMetaSelected(context)) {
                append("Meta Ray-Ban profile: selected\n")
                append("Meta DAT diagnostics:\n")
                append(MetaRaybanManager.getInstance(context).diagnosticsSnapshot())
                append("\n")
            }

            if (DeviceProfileStore.isMeizuMyvuSelected(context)) {
                append("Meizu MYVU profile: selected\n")
                append("MYVU diagnostics:\n")
                append(MeizuMyvuManager.getInstance(context).diagnosticsSnapshot())
                append("\n")
            }

            val selectedModel = runCatching {
                LocalModelStorageRepository.resolveSelectedModel(context)
            }.getOrNull()
            if (selectedModel != null) {
                val localModelLabel = if (remoteModelActive) "Installed local model (inactive)" else "Local model"
                append("$localModelLabel: ${selectedModel.displayName}\n")
                append("Local model path: ${selectedModel.absolutePath}\n")
                val settings = runCatching {
                    LocalModelSettingsRepository.getForModel(context, selectedModel.id)
                }.getOrNull()
                if (settings != null) {
                    append("Local runtime: ${settings.modelRuntime}\n")
                    append("Local backend: ${settings.computeBackend}\n")
                    append("CPU threads: ${settings.cpuThreads}\n")
                    append("GPU layers: ${settings.gpuLayers}\n")
                    append("Context size: ${settings.contextSize}\n")
                    append("Max tokens: ${settings.maxTokens}\n")
                }
            }

            if (extraInfo.isNotEmpty()) {
                append("Extra info:\n")
                for ((key, value) in LinkedHashMap(extraInfo)) {
                    append("- $key: $value\n")
                }
            }
        }
    }

    internal fun buildAutomationDiagnostics(context: Context): String = buildString {
        appendAutomationDiagnostics(context)
    }

    private fun StringBuilder.appendAutomationDiagnostics(context: Context) {
        val accessibilityEnabled = hasAccessibilityServicePermission(context)
        val accessibilityConnected = LocalAgentAccessibilityBridge.isConnected()
        val deviceAvailability = LocalAgentDeviceState.availability(context)
        val lastTask = LocalAgentTaskHistory.recent(context, limit = 1).firstOrNull()

        appendLine("Automation diagnostics:")
        appendLine("- Feature enabled: ${AutomationPrefs.isLocalAgentAutomationEnabled(context)}")
        appendLine("- Accessibility enabled in Android settings: $accessibilityEnabled")
        appendLine("- Accessibility service connected: $accessibilityConnected")
        appendLine(
            "- Accessibility state: " + when {
                accessibilityEnabled && accessibilityConnected -> "ready"
                accessibilityEnabled -> "enabled_but_not_connected"
                else -> "disabled"
            },
        )
        appendLine("- Local Agent service running: ${LocalAgentService.isRunning()}")
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

    suspend fun sendLogsToServer(
        context: Context,
        issueType: String,
        description: String,
        logs: String,
        deviceInfo: String,
        contactEmail: String? = null,
        requestMetadata: String? = null,
        relayBaseUrl: String? = null,
    ): Result<String> = runCatching {
        val baseUrl = relayBaseUrl?.trim()?.trimEnd('/')?.takeIf { it.isNotBlank() }
            ?: AiProviderPrefs.getRelayBaseUrl(context).trimEnd('/')
        val url = URL("$baseUrl/logs/submit")
        val token = ProSubscriptionServerPrefs.getApiToken(context)
        val normalizedContactEmail = contactEmail?.trim()?.takeIf { it.isNotEmpty() }
        if (normalizedContactEmail != null && !isValidContactEmail(normalizedContactEmail)) {
            throw IllegalArgumentException("Enter a valid contact email")
        }

        val payload = org.json.JSONObject()
            .put("issue_type", issueType)
            .put("description", description)
            .put("logs", logs)
            .put("device_info", deviceInfo)
            .put("app_version", context.packageManager.getPackageInfo(context.packageName, 0).versionName)
            .apply {
                normalizedContactEmail?.let { put("contact_email", it) }
                requestMetadata?.trim()?.takeIf { it.isNotEmpty() }?.let { put("request_metadata", it) }
            }

        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 30_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            if (token.isNotBlank()) {
                setRequestProperty("Authorization", "Bearer $token")
            }
        }

        OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(payload.toString()) }
        val code = conn.responseCode
        val body = if (code in 200..299) {
            BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
        } else {
            BufferedReader(InputStreamReader(conn.errorStream ?: conn.inputStream)).use { it.readText() }
        }
        conn.disconnect()

        if (code !in 200..299) {
            throw IllegalStateException("HTTP $code: ${body.take(200)}")
        }

        org.json.JSONObject(body).optString("log_id", "submitted")
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
        val deviceInfo = buildDeviceInfo(
            context = context,
            extraInfo = linkedMapOf(
                "Firmware request source" to request.source,
                "Firmware request target" to request.target,
                "Target hardware version" to request.targetHardwareVersion,
                "Target firmware version" to request.targetFirmwareVersion,
                "Wi-Fi hardware version" to request.wifiHardwareVersion,
                "Wi-Fi firmware version" to request.wifiFirmwareVersion,
                "BLE hardware version" to request.bleHardwareVersion,
                "BLE firmware version" to request.bleFirmwareVersion,
                "Firmware relay response" to request.relayMessage,
            ),
        )
        return sendLogsToServer(
            context = context,
            issueType = "Firmware patch request",
            description = "No approved exact-base firmware patch is available for the reported glasses version. " +
                "The requester asked the developer to review this specific version.",
            logs = collectLogcat(),
            deviceInfo = deviceInfo,
            contactEmail = normalizedEmail,
            requestMetadata = requestMetadata,
            relayBaseUrl = relayBaseUrl,
        )
    }

    suspend fun buildDebugReport(
        context: Context,
        issueType: String,
        description: String,
        extraInfo: Map<String, String> = emptyMap(),
    ): DebugReport {
        val logs = collectLogcat()
        val deviceInfo = buildDeviceInfo(context, extraInfo)
        val file = writeManualLogFile(
            context = context,
            issueType = issueType,
            description = description,
            logs = logs,
            deviceInfo = deviceInfo,
        )
        return DebugReport(logs = logs, deviceInfo = deviceInfo, file = file)
    }

    fun manualLogRelativePath(context: Context): String {
        return "Android/data/${context.packageName}/files/debug-logs/"
    }

    private fun uploadLogsToServer(
        activity: AppCompatActivity,
        issueType: String,
        description: String,
        extraInfo: Map<String, String>,
        contactEmail: String,
    ) {
        Toast.makeText(activity, "Collecting logs...", Toast.LENGTH_SHORT).show()
        CoroutineScope(Dispatchers.Main).launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val report = buildDebugReport(
                        context = activity,
                        issueType = issueType,
                        description = description,
                        extraInfo = extraInfo,
                    )
                    val serverResult = sendLogsToServer(
                        context = activity,
                        issueType = issueType,
                        description = description,
                        logs = report.logs,
                        deviceInfo = report.deviceInfo,
                        contactEmail = contactEmail.ifBlank { null },
                    ).getOrThrow()
                    report to serverResult
                }
            }

            result.onSuccess { (report, logId) ->
                Toast.makeText(
                    activity,
                    "Logs sent. Log ID: $logId\nSaved copy: ${report.file.name}",
                    Toast.LENGTH_LONG,
                ).show()
            }.onFailure { err ->
                Log.e(TAG, "Failed to upload logs", err)
                Toast.makeText(
                    activity,
                    "Failed to send logs: ${err.message}",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    private fun exportLogsForEmail(
        activity: AppCompatActivity,
        issueType: String,
        description: String,
        extraInfo: Map<String, String>,
        contactEmail: String,
    ) {
        Toast.makeText(activity, "Preparing log file...", Toast.LENGTH_SHORT).show()
        CoroutineScope(Dispatchers.Main).launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    buildDebugReport(
                        context = activity,
                        issueType = issueType,
                        description = description,
                        extraInfo = extraInfo,
                    )
                }
            }

            result.onSuccess { report ->
                val intent = buildEmailIntent(activity, issueType, description, contactEmail, report.file)
                try {
                    activity.startActivity(Intent.createChooser(intent, "Send debug logs"))
                    Toast.makeText(
                        activity,
                        "Saved log file: ${report.file.name}",
                        Toast.LENGTH_LONG,
                    ).show()
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
                Toast.makeText(
                    activity,
                    "Failed to prepare logs: ${err.message}",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    private fun buildEmailIntent(
        context: Context,
        issueType: String,
        description: String,
        contactEmail: String,
        file: File,
    ): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_EMAIL, arrayOf(SUPPORT_EMAIL))
            putExtra(Intent.EXTRA_SUBJECT, "CyanBridge logs: $issueType")
            putExtra(
                Intent.EXTRA_TEXT,
                buildString {
                    appendLine("Please find the attached CyanBridge debug logs.")
                    appendLine()
                    appendLine("Issue type: $issueType")
                    appendLine("Description: ${description.trim()}")
                    if (contactEmail.isNotBlank()) appendLine("Contact email: $contactEmail")
                }.trim(),
            )
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun writeManualLogFile(
        context: Context,
        issueType: String,
        description: String,
        logs: String,
        deviceInfo: String,
    ): File {
        val dir = (context.getExternalFilesDir(null) ?: context.filesDir)
            .let { File(it, "debug-logs") }
            .apply { mkdirs() }

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val safeType = issueType
            .lowercase(Locale.US)
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .ifBlank { "general" }
        val file = File(dir, "cyanbridge_${safeType}_$timestamp.txt")

        file.writeText(
            buildString {
                appendLine("=== CyanBridge Debug Report ===")
                appendLine("Issue Type: $issueType")
                appendLine("Collected At: ${Date()}")
                appendLine()
                appendLine("Description:")
                appendLine(description.trim().ifBlank { "No description provided" })
                appendLine()
                appendLine("Device Info:")
                appendLine(deviceInfo.trim())
                appendLine()
                appendLine("Logs:")
                appendLine(logs.trim())
            },
        )
        return file
    }

    fun isValidOptionalContactEmail(value: String): Boolean =
        value.isBlank() || isValidContactEmail(value.trim())

    private fun isValidContactEmail(value: String): Boolean =
        value.length <= MAX_CONTACT_EMAIL_LENGTH &&
            value.matches(Regex("[^\\s@]+@[^\\s@]+\\.[^\\s@]+"))
}
