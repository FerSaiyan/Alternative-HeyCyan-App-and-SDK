package com.fersaiyan.cyanbridge.agent

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.fersaiyan.cyanbridge.localmodels.benchmark.LocalModelBenchmarkProgress
import com.fersaiyan.cyanbridge.localmodels.benchmark.LocalModelBenchmarkRunner
import com.fersaiyan.cyanbridge.localmodels.catalog.LocalModelCatalogRepository
import com.fersaiyan.cyanbridge.localmodels.session.LocalChatSessionManager
import com.fersaiyan.cyanbridge.localmodels.settings.LocalModelRuntime
import com.fersaiyan.cyanbridge.localmodels.settings.LocalModelSettingsRepository
import com.fersaiyan.cyanbridge.localmodels.storage.LocalModelStorageRepository
import com.fersaiyan.cyanbridge.shared.localmodels.LocalModelBaseline
import com.fersaiyan.cyanbridge.shared.localmodels.LocalModelTestRunner
import com.fersaiyan.cyanbridge.shared.localmodels.LocalModelTestTarget
import com.fersaiyan.cyanbridge.shared.localmodels.LocalModelTestViewModel
import com.fersaiyan.cyanbridge.shared.prefs.AgentPrefs
import com.fersaiyan.cyanbridge.shared.prefs.set
import com.fersaiyan.cyanbridge.shared.ui.localmodels.LocalModelTestScreen
import com.fersaiyan.cyanbridge.ui.appearance.AppearancePreferences
import com.fersaiyan.cyanbridge.ui.appearance.rememberAppearanceSettings
import com.fersaiyan.cyanbridge.ui.theme.CyanBridgeTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Hosts the shared model performance test; [LocalModelTestViewModel] runs it. */
class LocalModelTestActivity : ComponentActivity() {
    private val viewModel by viewModels<LocalModelTestViewModel> {
        viewModelFactory {
            initializer {
                LocalModelTestViewModel(AndroidLocalModelTestRunner(applicationContext, intent.getStringExtra(EXTRA_MODEL_ID)))
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val appearancePreferences = AppearancePreferences(this)
        setContent {
            val appearance by rememberAppearanceSettings(appearancePreferences)
            val state by viewModel.state.collectAsState()
            CyanBridgeTheme(appearance) {
                LocalModelTestScreen(state = state, onBack = { finish() })
            }
        }
        viewModel.start()
    }

    companion object {
        const val EXTRA_MODEL_ID = "model_id"
    }
}

/** Runs the performance test on the installed model with its saved settings. */
private class AndroidLocalModelTestRunner(private val context: Context, requestedId: String?) : LocalModelTestRunner {
    private val model = (requestedId ?: LocalModelStorageRepository.getSelectedModelId(context)).let { id ->
        LocalModelStorageRepository.listInstalled(context).firstOrNull { it.id == id }
    }
    private val settings by lazy { LocalModelSettingsRepository.getForModel(context, model!!.id) }

    override fun target(): LocalModelTestTarget? {
        val model = model ?: return null
        return LocalModelTestTarget(
            modelName = model.displayName,
            runtime = settings.modelRuntime.label,
            backend = settings.computeBackend.label,
            liteRt = settings.modelRuntime == LocalModelRuntime.LITERT,
        )
    }

    override suspend fun releaseActiveModel() = LocalChatSessionManager.unload()

    override suspend fun runComparison(onProgress: (LocalModelBenchmarkProgress) -> Unit) {
        LocalModelBenchmarkRunner.runLiteRtComparison(
            context = context,
            model = model!!,
            backend = settings.computeBackend,
            cpuThreads = settings.cpuThreads,
            onProgress = onProgress,
        )
    }

    override suspend fun runBaseline(): LocalModelBaseline = withContext(Dispatchers.IO) {
        LocalChatSessionManager.ensureModelLoaded(
            context = context,
            model = model!!,
            catalogEntry = LocalModelCatalogRepository.findById(model.catalogId),
            settings = settings,
        )
        val probe = LocalChatSessionManager.runWarmupProbe(settings) { }
        LocalModelBaseline(probe.generatedTokens, probe.elapsedMs, probe.fallbackReason)
    }

    override fun saveSummary(summary: String) {
        AgentPrefs.LocalModels.lastBenchmark[context] = summary
    }
}
