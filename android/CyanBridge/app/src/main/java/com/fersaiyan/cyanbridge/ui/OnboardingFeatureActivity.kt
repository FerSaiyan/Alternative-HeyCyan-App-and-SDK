package com.fersaiyan.cyanbridge.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.fersaiyan.cyanbridge.MainActivity
import com.fersaiyan.cyanbridge.R
import com.fersaiyan.cyanbridge.agent.LocalAgentPrefs
import com.fersaiyan.cyanbridge.ai.router.AiProviderPrefs
import com.fersaiyan.cyanbridge.ai.router.AiProviderType
import com.fersaiyan.cyanbridge.localmodels.catalog.LocalModelCatalogRepository
import com.fersaiyan.cyanbridge.localmodels.device.DeviceCapabilityService
import com.fersaiyan.cyanbridge.localmodels.download.ModelDownloadForegroundService
import com.fersaiyan.cyanbridge.localmodels.settings.LocalModelSettingsRepository
import com.fersaiyan.cyanbridge.localmodels.storage.LocalModelStorageRepository
import com.fersaiyan.cyanbridge.shared.settings.AgentProviderType
import com.fersaiyan.cyanbridge.shared.ui.onboarding.FeatureOnboardingScreen
import com.fersaiyan.cyanbridge.shared.ui.onboarding.OnboardingChoice
import com.fersaiyan.cyanbridge.ui.appearance.AppearancePreferences
import com.fersaiyan.cyanbridge.ui.appearance.rememberAppearanceSettings
import com.fersaiyan.cyanbridge.ui.theme.CyanBridgeTheme
import com.hjq.permissions.OnPermissionCallback
import java.util.Locale

class OnboardingFeatureActivity : AppCompatActivity() {

    private var featureIndex = 0
    private var glassesConnectionPermissionGranted by mutableStateOf(false)
    private var selectedModelId by mutableStateOf<String?>(null)
    private var modelChoices = emptyList<OnboardingChoice>()

    data class OnboardingFeature(
        val iconRes: Int,
        val titleRes: Int,
        val descriptionRes: Int,
        val detailsRes: Int,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        featureIndex = intent.getIntExtra(EXTRA_FEATURE_INDEX, 0)
        val ramGb = DeviceCapabilityService.totalRamGb(
            DeviceCapabilityService.snapshot(this).totalRamBytes,
        )
        val recommended = LocalModelCatalogRepository.recommendedStarterForRam(ramGb)
        val storedModelId = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ONBOARDING_MODEL_ID, null)
        selectedModelId = LocalModelCatalogRepository.findById(storedModelId)
            ?.takeIf { it.enabled && !it.comingSoon && ramGb >= it.minRamGb }
            ?.id
            ?: recommended?.id
        modelChoices = buildModelChoices(ramGb, recommended?.id)
        setupFeatureScreen()
    }

    override fun onResume() {
        super.onResume()
        glassesConnectionPermissionGranted = hasBluetooth(this)
    }

    private fun setupFeatureScreen() {
        val feature = FEATURES.getOrNull(featureIndex) ?: run {
            finishOnboarding()
            return
        }

        glassesConnectionPermissionGranted = hasBluetooth(this)
        val appearancePreferences = AppearancePreferences(this)
        setContent {
            val appearance by rememberAppearanceSettings(appearancePreferences)
            CyanBridgeTheme(appearance) {
                FeatureOnboardingScreen(
                    title = getString(feature.titleRes),
                    description = getString(feature.descriptionRes),
                    details = getString(feature.detailsRes),
                    showGlassesConnectionPermission = featureIndex == GLASSES_CONNECTION_FEATURE_INDEX,
                    glassesConnectionPermissionGranted = glassesConnectionPermissionGranted,
                    // CyanBridge uses app-private storage, MediaStore and SAF; All Files Access is not requested.
                    showStoragePermission = false,
                    storagePermissionGranted = true,
                    showOpenSourceContribution = featureIndex == OPEN_SOURCE_FEATURE_INDEX,
                    choices = if (featureIndex == LOCAL_MODEL_FEATURE_INDEX) modelChoices else emptyList(),
                    selectedChoiceId = selectedModelId,
                    backLabel = getString(
                        if (featureIndex == 0) R.string.onboarding_skip_all else R.string.onboarding_back,
                    ),
                    nextLabel = getString(
                        if (featureIndex == FEATURES.lastIndex) R.string.onboarding_get_started else R.string.onboarding_next,
                    ),
                    onRequestGlassesConnectionPermission = {
                        requestBluetoothPermission(this, OnPermissionCallback { _, allGranted ->
                            glassesConnectionPermissionGranted = allGranted && hasBluetooth(this)
                        })
                    },
                    onRequestStoragePermission = {},
                    onOpenSourceRepository = {
                        runCatching {
                            startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(GITHUB_REPOSITORY_URL)))
                        }
                    },
                    onChoiceSelected = { modelId ->
                        selectedModelId = modelId
                        getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                            .edit()
                            .putString(KEY_ONBOARDING_MODEL_ID, modelId)
                            .apply()
                    },
                    onBack = {
                        if (featureIndex == 0) skipAllOnboarding() else goToFeature(featureIndex - 1)
                    },
                    onNext = {
                        if (featureIndex == LOCAL_MODEL_FEATURE_INDEX) {
                            startSelectedModelDownload()
                        }
                        if (featureIndex == FEATURES.lastIndex) finishOnboarding() else goToFeature(featureIndex + 1)
                    },
                )
            }
        }
    }

    private fun goToFeature(index: Int) {
        startActivity(Intent(this, OnboardingFeatureActivity::class.java).apply {
            putExtra(EXTRA_FEATURE_INDEX, index)
        })
        overridePendingTransition(android.R.anim.slide_in_left, android.R.anim.slide_out_right)
        finish()
    }

    private fun finishOnboarding() {
        getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean("onboarding_completed", true)
            .apply()

        startActivity(Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        })
        finish()
    }

    private fun skipAllOnboarding() {
        getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean("onboarding_completed", true)
            .apply()

        startActivity(Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        })
        finish()
    }

    private fun startSelectedModelDownload() {
        val entry = LocalModelCatalogRepository.findById(selectedModelId) ?: return
        val assessment = DeviceCapabilityService.assess(
            snapshot = DeviceCapabilityService.snapshot(this),
            entry = entry,
            requireDownloadHeadroom = true,
        )
        if (!assessment.supported) {
            android.widget.Toast.makeText(
                this,
                assessment.blockers.joinToString("\n"),
                android.widget.Toast.LENGTH_LONG,
            ).show()
            return
        }
        LocalAgentPrefs.setProviderType(this, AgentProviderType.LOCAL_AGENT)
        AiProviderPrefs.setProvider(this, AiProviderType.LOCAL_MODELS)
        LocalModelSettingsRepository.saveForModel(
            this,
            entry.id,
            LocalModelSettingsRepository.getForModel(this, entry.id),
        )

        val installed = LocalModelStorageRepository.findByCatalogId(this, entry.id)
        if (installed != null) {
            LocalModelStorageRepository.setSelectedModelId(this, installed.id)
            return
        }
        if (ModelDownloadForegroundService.isDownloading &&
            ModelDownloadForegroundService.downloadingModelId == entry.id
        ) {
            return
        }
        ModelDownloadForegroundService.startDownload(
            context = this,
            modelId = entry.id,
            hfToken = LocalModelSettingsRepository.getHuggingFaceToken(this),
        )
    }

    companion object {
        private const val EXTRA_FEATURE_INDEX = "feature_index"
        private const val PREFS = "cyanbridge_prefs"
        private const val KEY_ONBOARDING_MODEL_ID = "onboarding_model_id"
        private const val GLASSES_CONNECTION_FEATURE_INDEX = 0
        private const val LOCAL_MODEL_FEATURE_INDEX = 1
        private const val OPEN_SOURCE_FEATURE_INDEX = 3
        private const val GITHUB_REPOSITORY_URL = "https://github.com/FerSaiyan/Alternative-HeyCyan-App-and-SDK"

        private val FEATURES = listOf(
            OnboardingFeature(
                iconRes = R.drawable.ic_device_heycyan,
                titleRes = R.string.onboarding_glasses_connection_title,
                descriptionRes = R.string.onboarding_glasses_connection_desc,
                detailsRes = R.string.onboarding_glasses_connection_details,
            ),
            OnboardingFeature(
                iconRes = R.drawable.ic_device_heycyan,
                titleRes = R.string.onboarding_local_model_title,
                descriptionRes = R.string.onboarding_local_model_desc,
                detailsRes = R.string.onboarding_local_model_details,
            ),
            OnboardingFeature(
                iconRes = R.drawable.ic_device_heycyan,
                titleRes = R.string.onboarding_optional_features_title,
                descriptionRes = R.string.onboarding_optional_features_desc,
                detailsRes = R.string.onboarding_optional_features_details,
            ),
            OnboardingFeature(
                iconRes = R.drawable.ic_device_heycyan,
                titleRes = R.string.onboarding_open_source_title,
                descriptionRes = R.string.onboarding_open_source_desc,
                detailsRes = R.string.onboarding_open_source_details,
            ),
        )

        private val MODEL_CHOICE_COPY = listOf(
            Triple(
                "gemma4-e2b-it-litert",
                "Gemma 4 E2B - Fast",
                "Fast local text, image, and audio understanding.",
            ),
            Triple(
                "gemma4-e4b-it-litert",
                "Gemma 4 E4B - Slower but smarter",
                "Higher-quality local answers for devices with more memory.",
            ),
            Triple(
                "qwen3.5-0.8b-q4",
                "Qwen3.5 0.8B - Super small",
                "A compact, quick text model for lower-memory devices.",
            ),
        )

        fun launchIfNeeded(activity: AppCompatActivity) {
            val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (prefs.getBoolean("onboarding_completed", false)) return
            activity.startActivity(Intent(activity, OnboardingFeatureActivity::class.java))
        }
    }

    private fun buildModelChoices(ramGb: Double, recommendedId: String?): List<OnboardingChoice> =
        MODEL_CHOICE_COPY.mapNotNull { (id, title, description) ->
            val entry = LocalModelCatalogRepository.findById(id) ?: return@mapNotNull null
            val enabled = ramGb >= entry.minRamGb
            val ramLabel = String.format(Locale.US, "%.0f", entry.minRamGb)
            OnboardingChoice(
                id = id,
                title = if (id == recommendedId) "$title - Recommended" else title,
                description = "$description Requires $ramLabel GB RAM.",
                enabled = enabled,
            )
        }
}
