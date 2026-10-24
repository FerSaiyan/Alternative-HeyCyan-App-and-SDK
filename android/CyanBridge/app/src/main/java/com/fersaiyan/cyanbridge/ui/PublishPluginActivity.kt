package com.fersaiyan.cyanbridge.ui

import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.fersaiyan.cyanbridge.ai.router.AiProviderPrefs
import com.fersaiyan.cyanbridge.shared.ui.plugins.PublishPluginScreen
import com.fersaiyan.cyanbridge.shared.ui.plugins.PublishPluginViewModel
import com.fersaiyan.cyanbridge.ui.appearance.AppearancePreferences
import com.fersaiyan.cyanbridge.ui.appearance.rememberAppearanceSettings
import com.fersaiyan.cyanbridge.ui.theme.CyanBridgeTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

class PublishPluginActivity : AppCompatActivity() {

    private val viewModel by viewModels<PublishPluginViewModel> {
        viewModelFactory {
            initializer {
                val context = applicationContext
                PublishPluginViewModel(
                    platform = { title, author, description, category, link ->
                        submitPluginToServer(context, title, author, description, category, link)
                    },
                    showMessage = { Toast.makeText(context, it, Toast.LENGTH_LONG).show() },
                )
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val appearancePreferences = AppearancePreferences(this)
        setContent {
            val appearance by rememberAppearanceSettings(appearancePreferences)
            CyanBridgeTheme(appearance) {
                LaunchedEffect(viewModel.submitted) { if (viewModel.submitted) finish() }
                PublishPluginScreen(
                    state = viewModel.state,
                    categories = viewModel.categories,
                    onTitleChanged = viewModel::onTitleChanged,
                    onAuthorChanged = viewModel::onAuthorChanged,
                    onDescriptionChanged = viewModel::onDescriptionChanged,
                    onCategorySelected = viewModel::onCategorySelected,
                    onTaskerNetLinkChanged = viewModel::onTaskerNetLinkChanged,
                    onSubmit = viewModel::submit,
                    onNavigateBack = ::finish,
                )
            }
        }
    }
}

private suspend fun submitPluginToServer(
    context: Context,
    title: String,
    author: String,
    description: String,
    category: String,
    taskerNetLink: String,
): Boolean = withContext(Dispatchers.IO) {
    runCatching {
        val relayUrl = AiProviderPrefs.getRelayBaseUrl(context)
        val connection = java.net.URL("$relayUrl/plugins/submit").openConnection()
            as java.net.HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.connectTimeout = 5_000
            connection.readTimeout = 5_000
            connection.doOutput = true
            val body = JSONObject(
                mapOf(
                    "title" to title,
                    "author" to author,
                    "description" to description,
                    "category" to category,
                    "taskernet_link" to taskerNetLink,
                ),
            ).toString()
            connection.outputStream.use { it.write(body.toByteArray()) }
            connection.responseCode in 200..299
        } finally {
            connection.disconnect()
        }
    }.getOrDefault(false)
}
