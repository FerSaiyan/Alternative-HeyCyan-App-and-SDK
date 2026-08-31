package com.fersaiyan.cyanbridge.ui

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.fersaiyan.cyanbridge.ai.router.AiProviderPrefs
import com.fersaiyan.cyanbridge.R
import com.fersaiyan.cyanbridge.ui.appearance.AppearancePreferences
import com.fersaiyan.cyanbridge.ui.appearance.rememberAppearanceSettings
import com.fersaiyan.cyanbridge.ui.theme.CyanBridgeTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object VersionUpdateChecker {

    private const val KEY_LAST_CHECK_TIME = "last_version_check_time"
    private const val KEY_REMINDED_VERSION = "reminded_version"
    private const val CHECK_INTERVAL_HOURS = 6
    private const val PLAY_STORE_FALLBACK_URL = "https://play.google.com/store/apps/details?id=com.fersaiyan.cyanbridge"
    private const val GITHUB_FALLBACK_URL = "https://github.com/FerSaiyan/Alternative-HeyCyan-App-and-SDK/releases"

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
                    val playStoreUrl = json.optString("play_store_url", "").ifBlank {
                        json.optString("playStoreUrl", "")
                    }.ifBlank { downloadUrl }
                    val githubUrl = json.optString("github_url", "").ifBlank {
                        json.optString("githubUrl", "")
                    }

                    withContext(Dispatchers.Main) {
                        if (latestVersion.isNotBlank()) {
                            checkAndShowUpdateDialog(context, latestVersion, playStoreUrl, githubUrl)
                        }
                    }
                }
                connection.disconnect()
            } catch (e: Exception) {
                // Silently fail - version check is not critical
            }
        }
    }

    private fun checkAndShowUpdateDialog(
        context: Context,
        latestVersion: String,
        playStoreUrl: String,
        githubUrl: String,
    ) {
        val prefs = context.getSharedPreferences("version_check", Context.MODE_PRIVATE)
        val currentVersion = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
        } catch (e: Exception) {
            ""
        }

        val remindedVersion = prefs.getString(KEY_REMINDED_VERSION, "") ?: ""

        if (latestVersion != currentVersion && latestVersion != remindedVersion) {
            showUpdateDialog(context, latestVersion, playStoreUrl, githubUrl)
        }
    }

    fun showUpdateDialog(
        context: Context,
        latestVersion: String,
        playStoreUrl: String,
        githubUrl: String = "",
    ) {
        val activity = context as? Activity ?: return
        if (activity.isFinishing || activity.isDestroyed) return
        val currentVersion = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "Unknown"
        } catch (e: Exception) {
            "Unknown"
        }

        val prefs = context.getSharedPreferences("version_check", Context.MODE_PRIVATE)
        val dialog = Dialog(activity)
        dialog.setContentView(
            ComposeView(activity).apply {
                setContent {
                    val appearance by rememberAppearanceSettings(AppearancePreferences(activity))
                    CyanBridgeTheme(appearance) {
                        VersionUpdateDialogContent(
                            currentVersion = currentVersion,
                            latestVersion = latestVersion,
                            onPlayStore = {
                                val target = playStoreUrl.ifBlank {
                                    "https://play.google.com/store/apps/details?id=com.fersaiyan.cyanbridge"
                                }
                                openPlayStore(context, target)
                                dialog.dismiss()
                            },
                            onDownload = {
                                try {
                                    val url = githubUrl.ifBlank {
                                        playStoreUrl.ifBlank {
                                            "https://github.com/FerSaiyan/Alternative-HeyCyan-App-and-SDK/releases"
                                        }
                                    }
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    })
                                } catch (_: Exception) {
                                    Toast.makeText(context, context.getString(R.string.compose_update_open_link_failed), Toast.LENGTH_SHORT).show()
                                }
                                dialog.dismiss()
                            },
                            onLater = {
                                prefs.edit().putString(KEY_REMINDED_VERSION, latestVersion).apply()
                                dialog.dismiss()
                            },
                        )
                    }
                }
            },
        )
        dialog.setCanceledOnTouchOutside(true)
        dialog.show()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun openPlayStore(context: Context, url: String) {
        // Prefer the Play Store app via market://, fall back to https.
        val httpsUrl = if (url.startsWith("http")) url else PLAY_STORE_FALLBACK_URL
        val marketUrl = when {
            httpsUrl.contains("play.google.com/store/apps/details") -> {
                val id = Uri.parse(httpsUrl).getQueryParameter("id") ?: "com.fersaiyan.cyanbridge"
                "market://details?id=$id"
            }
            httpsUrl.startsWith("market://") -> httpsUrl
            else -> null
        }
        // Try market:// first, then https://
        if (marketUrl != null) {
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(marketUrl)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
                return
            } catch (_: Exception) { /* fall through to https */ }
        }
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(httpsUrl)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (_: Exception) {
            Toast.makeText(context, context.getString(R.string.compose_update_open_link_failed), Toast.LENGTH_SHORT).show()
        }
    }

    @Composable
    private fun VersionUpdateDialogContent(
        currentVersion: String,
        latestVersion: String,
        onPlayStore: () -> Unit,
        onDownload: () -> Unit,
        onLater: () -> Unit,
    ) {
        Card(modifier = Modifier.padding(24.dp).fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                 Text(stringResource(R.string.compose_update_available), style = MaterialTheme.typography.titleLarge)
                Text(
                    stringResource(R.string.compose_update_version_summary, currentVersion, latestVersion),
                    style = MaterialTheme.typography.bodyMedium,
                )
                FilledTonalButton(onClick = onPlayStore, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.compose_update_get_play_store))
                }
                OutlinedButton(onClick = onDownload, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.compose_update_download_github))
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    OutlinedButton(onClick = onLater) { Text(stringResource(R.string.compose_update_later)) }
                }
            }
        }
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
                    val playStoreUrl = json.optString("play_store_url", "").ifBlank {
                        json.optString("playStoreUrl", "")
                    }.ifBlank { downloadUrl }
                    val githubUrl = json.optString("github_url", "").ifBlank {
                        json.optString("githubUrl", "")
                    }

                    withContext(Dispatchers.Main) {
                        if (latestVersion.isNotBlank()) {
                            showUpdateDialog(context, latestVersion, playStoreUrl, githubUrl)
                        } else {
                             Toast.makeText(context, context.getString(R.string.compose_update_check_failed), Toast.LENGTH_SHORT).show()
                        }
                    }
                } else {
                    withContext(Dispatchers.Main) {
                         Toast.makeText(context, context.getString(R.string.compose_update_server_unavailable), Toast.LENGTH_SHORT).show()
                    }
                }
                connection.disconnect()
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, context.getString(R.string.compose_update_check_failed), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
