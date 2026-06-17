package com.fersaiyan.cyanbridge.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.fersaiyan.cyanbridge.R
import com.fersaiyan.cyanbridge.ai.router.AiProviderPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object VersionUpdateChecker {

    private const val KEY_LAST_CHECK_TIME = "last_version_check_time"
    private const val KEY_REMINDED_VERSION = "reminded_version"
    private const val CHECK_INTERVAL_HOURS = 6

    fun checkForUpdates(context: Context) {
        val prefs = context.getSharedPreferences("version_check", Context.MODE_PRIVATE)
        val lastCheck = prefs.getLong(KEY_LAST_CHECK_TIME, 0)
        val currentTime = System.currentTimeMillis()
        val hoursSinceLastCheck = (currentTime - lastCheck) / (1000 * 60 * 60)

        if (hoursSinceLastCheck < CHECK_INTERVAL_HOURS && lastCheck > 0) {
            return
        }

        prefs.edit().putLong(KEY_LAST_CHECK_TIME, currentTime).apply()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val relayUrl = AiProviderPrefs.getRelayBaseUrl(context)
                val url = java.net.URL("$relayUrl/version/latest")
                val connection = url.openConnection() as java.net.HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 5000
                connection.readTimeout = 5000

                if (connection.responseCode == 200) {
                    val response = connection.inputStream.bufferedReader().readText()
                    val json = org.json.JSONObject(response)
                    val latestVersion = json.optString("version", "")
                    val downloadUrl = json.optString("download_url", "")

                    withContext(Dispatchers.Main) {
                        if (latestVersion.isNotBlank()) {
                            checkAndShowUpdateDialog(context, latestVersion, downloadUrl)
                        }
                    }
                }
                connection.disconnect()
            } catch (e: Exception) {
                // Silently fail - version check is not critical
            }
        }
    }

    private fun checkAndShowUpdateDialog(context: Context, latestVersion: String, downloadUrl: String) {
        val prefs = context.getSharedPreferences("version_check", Context.MODE_PRIVATE)
        val currentVersion = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
        } catch (e: Exception) {
            ""
        }

        val remindedVersion = prefs.getString(KEY_REMINDED_VERSION, "") ?: ""

        if (latestVersion != currentVersion && latestVersion != remindedVersion) {
            showUpdateDialog(context, latestVersion, downloadUrl)
        }
    }

    fun showUpdateDialog(context: Context, latestVersion: String, downloadUrl: String) {
        val currentVersion = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "Unknown"
        } catch (e: Exception) {
            "Unknown"
        }

        val prefs = context.getSharedPreferences("version_check", Context.MODE_PRIVATE)
        val dialogView = (context as? android.view.LayoutInflater)?.let {
            it.inflate(R.layout.dialog_version_update, null)
        } ?: return

        val alertDialog = AlertDialog.Builder(context)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        val versionInfo = "Current version: $currentVersion\nLatest version: $latestVersion"
        dialogView.findViewById<android.widget.TextView>(R.id.tv_version_info)?.text = versionInfo

        dialogView.findViewById<android.widget.Button>(R.id.btn_download_github)?.setOnClickListener {
            try {
                val url = if (downloadUrl.isNotBlank()) downloadUrl else "https://github.com/FerSaiyan/Alternative-HeyCyan-App-and-SDK/releases"
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            } catch (e: Exception) {
                Toast.makeText(context, "Unable to open link", Toast.LENGTH_SHORT).show()
            }
            alertDialog.dismiss()
        }

        dialogView.findViewById<android.widget.Button>(R.id.btn_download_playstore)?.setOnClickListener {
            Toast.makeText(context, "Play Store version coming soon!", Toast.LENGTH_SHORT).show()
        }

        dialogView.findViewById<android.widget.Button>(R.id.btn_later)?.setOnClickListener {
            prefs.edit().putString(KEY_REMINDED_VERSION, latestVersion).apply()
            alertDialog.dismiss()
        }

        alertDialog.show()
    }

    fun forceCheckForUpdates(context: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val relayUrl = AiProviderPrefs.getRelayBaseUrl(context)
                val url = java.net.URL("$relayUrl/version/latest")
                val connection = url.openConnection() as java.net.HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 5000
                connection.readTimeout = 5000

                if (connection.responseCode == 200) {
                    val response = connection.inputStream.bufferedReader().readText()
                    val json = org.json.JSONObject(response)
                    val latestVersion = json.optString("version", "")
                    val downloadUrl = json.optString("download_url", "")

                    withContext(Dispatchers.Main) {
                        if (latestVersion.isNotBlank()) {
                            showUpdateDialog(context, latestVersion, downloadUrl)
                        } else {
                            Toast.makeText(context, "Could not check for updates", Toast.LENGTH_SHORT).show()
                        }
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Server unavailable for update check", Toast.LENGTH_SHORT).show()
                    }
                }
                connection.disconnect()
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Could not check for updates", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
