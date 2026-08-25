package com.fersaiyan.cyanbridge.analytics

import android.content.Context
import android.os.Build
import android.util.Log
import com.fersaiyan.cyanbridge.BuildConfig
import com.fersaiyan.cyanbridge.agent.ProSubscriptionServerPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicBoolean

object AnalyticsClient {
    private const val TAG = "ProductAnalytics"
    private const val SERVICE_BASE_URL = "https://cyanbridge.vercel.app"
    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val READ_TIMEOUT_MS = 8_000
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val heartbeatInFlight = AtomicBoolean(false)
    private val acquisitionInFlight = AtomicBoolean(false)

    fun recordDailyHeartbeat(context: Context) {
        val appContext = context.applicationContext
        // A submitted survey is independent of the optional daily heartbeat preference.
        flushPendingAcquisition(appContext)
        if (!AnalyticsPreferences.isSharingEnabled(appContext)) return
        val today = utcDay()
        if (AnalyticsPreferences.getLastHeartbeatDay(appContext) == today) {
            flushPendingAcquisition(appContext)
            return
        }
        if (!heartbeatInFlight.compareAndSet(false, true)) return

        scope.launch {
            try {
                val body = JSONObject()
                    .put("installation_id", InstallationIdentity.getOrCreate(appContext))
                    .put("app_version", BuildConfig.VERSION_NAME)
                    .put("platform", "android")
                    .put("distribution", distribution(appContext))
                val responseCode = postJson(appContext, "/api/analytics/heartbeat", body)
                if (responseCode in 200..299) {
                    AnalyticsPreferences.setLastHeartbeatDay(appContext, today)
                }
            } catch (_: Exception) {
                // Analytics must never interfere with app startup or local-first features.
            } finally {
                heartbeatInFlight.set(false)
                flushPendingAcquisition(appContext)
            }
        }
    }

    fun queueAcquisitionResponse(
        context: Context,
        primaryReason: String,
        secondaryReasons: Collection<String>,
        otherText: String?,
    ) {
        val appContext = context.applicationContext
        val pending = JSONObject()
            .put("installation_id", InstallationIdentity.getOrCreate(appContext))
            .put("primary_reason", primaryReason)
            .put("secondary_reasons", JSONArray(secondaryReasons.distinct().take(2)))
            .put("other_text", otherText?.trim().orEmpty().take(500))
        AnalyticsPreferences.setPendingAcquisition(appContext, pending.toString())
        AnalyticsPreferences.markAcquisitionComplete(appContext)
        flushPendingAcquisition(appContext)
    }

    fun skipAcquisition(context: Context) {
        AnalyticsPreferences.markAcquisitionComplete(context.applicationContext)
    }

    fun flushPendingAcquisition(context: Context) {
        val appContext = context.applicationContext
        val pending = AnalyticsPreferences.getPendingAcquisition(appContext)
        if (pending.isBlank() || !acquisitionInFlight.compareAndSet(false, true)) return

        scope.launch {
            try {
                val payload = JSONObject(pending)
                val responseCode = postJson(appContext, "/api/analytics/acquisition", payload)
                if (responseCode in 200..299) {
                    AnalyticsPreferences.setPendingAcquisition(appContext, null)
                }
            } catch (error: Exception) {
                // Keep the queued response for a later foreground session.
                Log.w(TAG, "Acquisition delivery failed; response remains queued", error)
            } finally {
                acquisitionInFlight.set(false)
            }
        }
    }

    private fun postJson(context: Context, path: String, body: JSONObject): Int {
        val connection = URL("$SERVICE_BASE_URL$path").openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Accept", "application/json")
            runCatching { ProSubscriptionServerPrefs.getApiToken(context) }
                .getOrDefault("")
                .trim()
                .takeIf { it.isNotBlank() }
                ?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
            connection.outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
                writer.write(body.toString())
            }
            val code = connection.responseCode
            val responseBody = runCatching {
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                stream?.bufferedReader()?.use { it.readText() }
            }.getOrNull()
            if (code !in 200..299) {
                Log.w(TAG, "POST $path failed with HTTP $code: ${responseBody.orEmpty().take(200)}")
            }
            code
        } finally {
            connection.disconnect()
        }
    }

    private fun distribution(context: Context): String {
        val installer = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getInstallerPackageName(context.packageName)
            }
        }.getOrNull().orEmpty()
        return if (installer == "com.android.vending") "google_play" else "direct"
    }

    private fun utcDay(nowMs: Long = System.currentTimeMillis()): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date(nowMs))
}
