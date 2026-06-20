package com.fersaiyan.cyanbridge.ui.debug

import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.fersaiyan.cyanbridge.agent.LocalAgentPrefs as AutomationPrefs
import com.fersaiyan.cyanbridge.agent.ProSubscriptionServerPrefs
import com.fersaiyan.cyanbridge.ai.router.AiProviderPrefs
import com.fersaiyan.cyanbridge.localmodels.settings.LocalModelSettingsRepository
import com.fersaiyan.cyanbridge.localmodels.storage.LocalModelStorageRepository
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

    private const val TAG = "DebugLogSupport"
    private const val MAX_LOGCAT_CHARS = 120_000
    private val LOG_TAGS = listOf(
        "AIHijack",
        "DataDownload",
        "DeviceNotify",
        "WifiP2pManagerSingleton",
        "WifiP2pBroadcastReceiver",
        "BleIpBridge",
        "CliRelayRouter",
        "LocalAgent",
        "MainActivity",
        "ChatThreadActivity",
        "SettingsActivity",
        "LocalModelsConfigureActivity",
        "RecordingsListActivity",
        "LocalModelsProvider",
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

        AlertDialog.Builder(activity)
            .setTitle(title)
            .setMessage(message)
            .setNegativeButton(dismissButtonLabel, null)
            .setNeutralButton("Email logs") { _, _ ->
                exportLogsForEmail(
                    activity = activity,
                    issueType = issueType,
                    description = description,
                    extraInfo = extraInfo,
                )
            }
            .setPositiveButton("Send to server") { _, _ ->
                uploadLogsToServer(
                    activity = activity,
                    issueType = issueType,
                    description = description,
                    extraInfo = extraInfo,
                )
            }
            .show()
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
        return try {
            val filter = LOG_TAGS.joinToString(" ") { "$it:*" }
            val process = Runtime.getRuntime().exec("logcat -d -t 1200 -s $filter")
            process.inputStream.bufferedReader().use { it.readText() }.take(MAX_LOGCAT_CHARS)
        } catch (e: Exception) {
            "Failed to collect logcat: ${e.message}"
        }
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

            val selectedModel = runCatching {
                LocalModelStorageRepository.resolveSelectedModel(context)
            }.getOrNull()
            if (selectedModel != null) {
                append("Local model: ${selectedModel.displayName}\n")
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

    suspend fun sendLogsToServer(
        context: Context,
        issueType: String,
        description: String,
        logs: String,
        deviceInfo: String,
    ): Result<String> = runCatching {
        val baseUrl = AiProviderPrefs.getRelayBaseUrl(context).trimEnd('/')
        val url = URL("$baseUrl/logs/submit")
        val token = ProSubscriptionServerPrefs.getApiToken(context)

        val payload = org.json.JSONObject()
            .put("issue_type", issueType)
            .put("description", description)
            .put("logs", logs)
            .put("device_info", deviceInfo)
            .put("app_version", context.packageManager.getPackageInfo(context.packageName, 0).versionName)

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
                val intent = buildEmailIntent(activity, issueType, description, report.file)
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
}
