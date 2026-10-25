package com.fersaiyan.cyanbridge.agent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.fersaiyan.cyanbridge.localmodels.download.ModelDownloadForegroundService
import com.fersaiyan.cyanbridge.plugins.PluginVoicePermissions
import com.fersaiyan.cyanbridge.shared.localmodels.LocalModelDownloadUiState
import com.fersaiyan.cyanbridge.shared.localmodels.LocalModelsAction
import com.fersaiyan.cyanbridge.shared.localmodels.LocalModelsConfigureViewModel
import com.fersaiyan.cyanbridge.shared.localmodels.OpenModelSource
import com.fersaiyan.cyanbridge.shared.localmodels.OpenModelTest
import com.fersaiyan.cyanbridge.shared.localmodels.PickModelFile
import com.fersaiyan.cyanbridge.shared.localmodels.ReportSettingsSaved
import com.fersaiyan.cyanbridge.shared.localmodels.RequestStudioPermissions
import com.fersaiyan.cyanbridge.shared.ui.localmodels.LocalModelsConfigureScreen
import com.fersaiyan.cyanbridge.ui.appearance.AppearancePreferences
import com.fersaiyan.cyanbridge.ui.appearance.rememberAppearanceSettings
import com.fersaiyan.cyanbridge.ui.collectScreenEffects
import com.fersaiyan.cyanbridge.ui.theme.CyanBridgeTheme
import kotlinx.coroutines.Dispatchers

/** Hosts the shared local models screen; [LocalModelsConfigureViewModel] owns its state and actions. */
class LocalModelsConfigureActivity : AppCompatActivity() {
    private val viewModel by viewModels<LocalModelsConfigureViewModel> {
        viewModelFactory { initializer { LocalModelsConfigureViewModel(AndroidLocalModelsPlatform(applicationContext), Dispatchers.IO) } }
    }
    private var downloadReceiver: BroadcastReceiver? = null

    private val importModelLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importModel(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        collectScreenEffects(viewModel.effectFlow) { effect ->
            when (effect) {
                PickModelFile -> importModelLauncher.launch(arrayOf("application/octet-stream", "*/*"))
                ReportSettingsSaved -> setResult(RESULT_OK)
                is OpenModelTest -> startActivity(
                    Intent(this, LocalModelTestActivity::class.java).putExtra(LocalModelTestActivity.EXTRA_MODEL_ID, effect.modelId),
                )
                is OpenModelSource -> startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(effect.url)))
                RequestStudioPermissions -> PluginVoicePermissions.ensure(this) { viewModel.saveStudioBridge() }
            }
        }
        val appearancePreferences = AppearancePreferences(this)
        setContent {
            val appearance by rememberAppearanceSettings(appearancePreferences)
            val state by viewModel.state.collectAsState()
            CyanBridgeTheme(appearance) {
                LocalModelsConfigureScreen(state = state, onAction = viewModel::handle)
            }
        }
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() = viewModel.handle(LocalModelsAction.Back)
            },
        )
        registerDownloadReceiver()
        viewModel.start()
    }

    override fun onResume() {
        super.onResume()
        // Refresh test recommendations/download results after returning from the benchmark screen.
        viewModel.onResume()
    }

    override fun onDestroy() {
        super.onDestroy()
        downloadReceiver?.let { runCatching { unregisterReceiver(it) } }
        downloadReceiver = null
    }

    private fun registerDownloadReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    ModelDownloadForegroundService.BROADCAST_PROGRESS -> {
                        val pct = intent.getIntExtra(ModelDownloadForegroundService.EXTRA_PERCENT, 0)
                        val downloaded = intent.getLongExtra(ModelDownloadForegroundService.EXTRA_DOWNLOADED_BYTES, 0L)
                        val total = intent.getLongExtra(ModelDownloadForegroundService.EXTRA_TOTAL_BYTES, 0L)
                        fun size(bytes: Long) = AndroidLocalModelsPlatform.humanSize(bytes)
                        viewModel.onDownloadProgress(
                            LocalModelDownloadUiState(
                                isInFlight = true,
                                message = "Downloading: $pct% (${size(downloaded)} / ${if (total > 0) size(total) else "?"})",
                                progressPercent = pct.takeIf { it > 0 },
                            ),
                        )
                    }
                    ModelDownloadForegroundService.BROADCAST_DOWNLOAD_FINISHED -> viewModel.onDownloadFinished(
                        success = intent.getBooleanExtra(ModelDownloadForegroundService.EXTRA_SUCCESS, false),
                        error = intent.getStringExtra(ModelDownloadForegroundService.EXTRA_ERROR),
                    )
                }
            }
        }
        downloadReceiver = receiver
        val filter = IntentFilter().apply {
            addAction(ModelDownloadForegroundService.BROADCAST_PROGRESS)
            addAction(ModelDownloadForegroundService.BROADCAST_DOWNLOAD_FINISHED)
        }
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }
}
